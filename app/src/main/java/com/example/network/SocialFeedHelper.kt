package com.example.network

import android.net.Uri
import android.util.LruCache
import com.example.util.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class SocialFeedKind {
    REDDIT,
    X
}

data class RedditPost(
    val id: String,
    val title: String,
    val subreddit: String,
    val author: String,
    val ups: Int,
    val commentCount: Int,
    val selfText: String,
    val imageUrl: String?,
    val permalink: String
)

data class RedditComment(
    val id: String,
    val author: String,
    val body: String,
    val ups: Int
)

data class RedditPage(
    val posts: List<RedditPost> = emptyList(),
    val error: String? = null,
    val openUrl: String = ""
)

data class RedditCommentsPage(
    val comments: List<RedditComment> = emptyList(),
    val error: String? = null,
    val openUrl: String = ""
)

data class XPost(
    val id: String,
    val author: String,
    val text: String,
    val url: String
)

data class XFeedResult(
    val shared: List<XPost> = emptyList(),
    val recent: List<XPost> = emptyList(),
    val recentNote: String? = null
)

/**
 * Recent X search is best-effort. Implementations must return an empty list
 * instead of throwing when a public bridge is blocked.
 */
interface XRecentSearchSource {
    val name: String
    suspend fun search(query: String): List<String>
}

private const val SOCIAL_UA = "Ek-i-yeni/1.0 (Android; social-feed; +https://eksisozluk.com)"
private const val SOCIAL_UA_HEADER = "X-Ek-Social-Ua"

private val STOPWORDS = setOf(
    "ve", "ile", "de", "da", "ki", "bir", "için", "icin", "gibi", "olan",
    "olması", "olmasi", "bu", "şu", "ama", "fakat", "ancak", "çok", "cok",
    "daha", "nasıl", "nasil", "neden", "diye", "kadar", "sonra", "önce", "once",
    "hem", "veya", "ya", "mi", "mı", "mu", "mü"
)

private val TURKISH_MONTH =
    "(?:ocak|şubat|subat|mart|nisan|mayıs|mayis|haziran|temmuz|ağustos|agustos|eylül|eylul|ekim|kasım|kasim|aralık|aralik)"

private val FULL_DATE = Regex("""\b\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\b""")
private val DAY_MONTH = Regex("""\b\d{1,2}\s+$TURKISH_MONTH(?:\s+(?:19|20)\d{2})?\b""", RegexOption.IGNORE_CASE)
private val MONTH_YEAR = Regex("""\b$TURKISH_MONTH\s+(?:19|20)\d{2}\b""", RegexOption.IGNORE_CASE)
private val PAREN_NUMBER = Regex("""\(\s*#?\d[\d.\s]*\)""")
private val HASH_NUMBER = Regex("""#\d+""")
private val YEAR = Regex("""\b(?:19|20)\d{2}\b""")
private val STATUS_LINK = Regex(
    """(?:https?://)?(?:www\.)?(?:twitter\.com|x\.com)/([A-Za-z0-9_]{1,15})/status/(\d+)""",
    RegexOption.IGNORE_CASE
)

fun extractSmartSearchQuery(topicTitle: String): String {
    val original = topicTitle.trim()
    if (original.isEmpty()) return ""
    var text = original.lowercase(Locale.forLanguageTag("tr"))
    text = FULL_DATE.replace(text, " ")
    text = DAY_MONTH.replace(text, " ")
    text = MONTH_YEAR.replace(text, " ")
    text = PAREN_NUMBER.replace(text, " ")
    text = HASH_NUMBER.replace(text, " ")
    text = YEAR.replace(text, " ")
    text = text.replace(Regex("[^a-z0-9çğıöşü\\s]"), " ")
    val words = text.split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length > 1 && it !in STOPWORDS }
    return words.take(4).joinToString(" ").ifBlank { original.take(48) }
}

fun extractStatusLinks(texts: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    val seenIds = HashSet<String>()
    texts.forEach { raw ->
        STATUS_LINK.findAll(raw).forEach { match ->
            val user = match.groupValues[1]
            val id = match.groupValues[2]
            if (user.equals("share", ignoreCase = true) || user.equals("intent", ignoreCase = true)) {
                return@forEach
            }
            if (!seenIds.add(id)) return@forEach
            seen.add("https://x.com/$user/status/$id")
            if (seen.size >= 8) return seen.toList()
        }
    }
    return seen.toList()
}

fun parseRedditPosts(raw: String): List<RedditPost> {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{")) return emptyList()
    return try {
        val children = JSONObject(trimmed)
            .optJSONObject("data")
            ?.optJSONArray("children")
            ?: return emptyList()
        val posts = ArrayList<RedditPost>(children.length())
        for (index in 0 until children.length()) {
            val child = children.optJSONObject(index) ?: continue
            val kind = child.optString("kind")
            if (kind.isNotBlank() && kind != "t3") continue
            val data = child.optJSONObject("data") ?: continue
            val id = data.optString("id")
            if (id.isBlank()) continue
            val title = Entities.unescape(data.optString("title")).trim()
            if (title.isBlank()) continue
            val authorName = data.optString("author").ifBlank { "silinmiş" }
            posts.add(
                RedditPost(
                    id = id,
                    title = title.take(300),
                    subreddit = data.optString("subreddit_name_prefixed").ifBlank {
                        val sub = data.optString("subreddit")
                        if (sub.isBlank()) "r/reddit" else "r/$sub"
                    },
                    author = if (authorName.startsWith("u/")) authorName else "u/$authorName",
                    ups = data.optInt("ups"),
                    commentCount = data.optInt("num_comments"),
                    selfText = Entities.unescape(data.optString("selftext")).take(2000),
                    imageUrl = redditPreviewUrl(data),
                    permalink = data.optString("permalink").ifBlank { "/comments/$id" }
                )
            )
            if (posts.size >= 25) break
        }
        posts
    } catch (_: Throwable) {
        emptyList()
    }
}

fun parseRedditComments(raw: String): List<RedditComment> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()
    return try {
        val listing = when {
            trimmed.startsWith("[") -> {
                val array = JSONArray(trimmed)
                if (array.length() > 1) array.optJSONObject(1) else array.optJSONObject(0)
            }
            trimmed.startsWith("{") -> JSONObject(trimmed)
            else -> null
        } ?: return emptyList()
        val children = listing.optJSONObject("data")?.optJSONArray("children") ?: return emptyList()
        val comments = ArrayList<RedditComment>()
        for (index in 0 until children.length()) {
            val child = children.optJSONObject(index) ?: continue
            if (child.optString("kind") != "t1") continue
            val data = child.optJSONObject("data") ?: continue
            val body = Entities.unescape(data.optString("body")).trim()
            if (body.isBlank() || body == "[removed]" || body == "[deleted]") continue
            val authorName = data.optString("author").ifBlank { "silinmiş" }
            comments.add(
                RedditComment(
                    id = data.optString("id").ifBlank { "c$index" },
                    author = if (authorName.startsWith("u/")) authorName else "u/$authorName",
                    body = body.take(1200),
                    ups = data.optInt("ups")
                )
            )
            if (comments.size >= 20) break
        }
        comments
    } catch (_: Throwable) {
        emptyList()
    }
}

fun parseOEmbed(raw: String, sourceUrl: String): XPost? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{")) return null
    return try {
        val root = JSONObject(trimmed)
        if (root.has("error")) return null
        val html = root.optString("html")
        val text = Jsoup.parse(html).selectFirst("p")?.text()?.trim().orEmpty()
        val author = root.optString("author_name").trim().ifBlank { "X" }
        val url = root.optString("url").trim().ifBlank { sourceUrl }
        if (text.isBlank()) return null
        XPost(
            id = statusIdOf(url) ?: statusIdOf(sourceUrl) ?: url,
            author = author,
            text = text.take(600),
            url = canonicalStatusUrl(url) ?: sourceUrl
        )
    } catch (_: Throwable) {
        null
    }
}

fun parseSyndicationTweet(raw: String, sourceUrl: String): XPost? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{")) return null
    return try {
        val root = JSONObject(trimmed)
        val text = root.optString("text").trim()
        if (text.isBlank() || root.optString("__typename") == "TweetTombstone") return null
        val user = root.optJSONObject("user")
        val author = user?.optString("screen_name").orEmpty().ifBlank {
            user?.optString("name").orEmpty()
        }.ifBlank { "X" }
        val id = root.optString("id_str").ifBlank { statusIdOf(sourceUrl) ?: return null }
        XPost(
            id = id,
            author = author,
            text = text.take(600),
            url = canonicalStatusUrl(sourceUrl) ?: "https://x.com/i/status/$id"
        )
    } catch (_: Throwable) {
        null
    }
}

fun parseFeedStatusLinks(raw: String): List<String> {
    val lowered = raw.lowercase()
    if (lowered.contains("making sure you're not a bot") || lowered.contains("anubis_challenge")) {
        return emptyList()
    }
    return extractStatusLinks(listOf(raw))
}

fun xLiveSearchUrl(query: String): String {
    return "https://x.com/search?q=${Uri.encode(query)}&f=live"
}

fun redditSearchUrl(query: String): String {
    return "https://www.reddit.com/search?q=${Uri.encode(query)}&sort=relevance&t=year"
}

private fun redditPreviewUrl(data: JSONObject): String? {
    val encoded = data.optJSONObject("preview")
        ?.optJSONArray("images")
        ?.optJSONObject(0)
        ?.optJSONObject("source")
        ?.optString("url")
        .orEmpty()
    if (encoded.isBlank()) return null
    val clean = Entities.unescape(encoded).trim()
    return clean.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

private fun statusIdOf(url: String): String? {
    return STATUS_LINK.find(url)?.groupValues?.getOrNull(2)
}

private fun canonicalStatusUrl(url: String): String? {
    val match = STATUS_LINK.find(url) ?: return null
    return "https://x.com/${match.groupValues[1]}/status/${match.groupValues[2]}"
}

/**
 * X and Reddit fetches. Nothing here runs until a caller asks.
 * The client is the app's shared OkHttp client (same pool and cookie jar).
 * A network interceptor restores a descriptive User-Agent after the Ekşi
 * user-agent interceptor, and only for requests that opt in.
 */
@Singleton
class SocialFeedHelper @Inject constructor(
    httpClient: OkHttpClient
) {
    private val socialClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val override = request.header(SOCIAL_UA_HEADER)
                if (override.isNullOrBlank()) {
                    chain.proceed(request)
                } else {
                    chain.proceed(
                        request.newBuilder()
                            .header("User-Agent", override)
                            .removeHeader(SOCIAL_UA_HEADER)
                            .build()
                    )
                }
            }
            .build()
    }

    private val redditCache = LruCache<String, RedditPage>(16)
    private val commentCache = LruCache<String, RedditCommentsPage>(24)
    private val oembedCache = LruCache<String, XPost>(48)
    private val recentCache = LruCache<String, XFeedResult>(16)
    private val oembedSlots = Semaphore(3)

    suspend fun fetchRedditPosts(query: String, force: Boolean = false): RedditPage = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) {
            return@withContext RedditPage(error = "Arama terimi boş.", openUrl = "https://www.reddit.com")
        }
        val key = cleaned.lowercase(Locale.forLanguageTag("tr"))
        if (!force) redditCache.get(key)?.let { return@withContext it }
        val openUrl = redditSearchUrl(cleaned)
        val hosts = listOf("https://www.reddit.com", "https://old.reddit.com")
        var lastError = "Reddit yanıt vermedi."
        for (host in hosts) {
            currentCoroutineContext().ensureActive()
            val url = "$host/search.json?q=${Uri.encode(cleaned)}&sort=relevance&t=year&limit=25"
            val response = runCatching { get(url, "application/json") }
            val page = response.fold(
                onSuccess = { http ->
                    http.use { resp ->
                        when (resp.code) {
                            200 -> {
                                val body = resp.body?.string().orEmpty()
                                val posts = parseRedditPosts(body)
                                if (posts.isEmpty() && !body.trim().startsWith("{")) {
                                    RedditPage(
                                        error = "Reddit JSON yerine başka bir sayfa döndürdü.",
                                        openUrl = openUrl
                                    )
                                } else {
                                    RedditPage(posts = posts, openUrl = openUrl)
                                }
                            }
                            403, 429 -> RedditPage(
                                error = "Reddit bu isteği engelledi (HTTP ${resp.code}). " +
                                    "Giriş yapılmamış istemcilerde bu sık olur.",
                                openUrl = openUrl
                            )
                            else -> RedditPage(
                                error = "Reddit yanıt vermedi (HTTP ${resp.code}).",
                                openUrl = openUrl
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    RedditPage(
                        error = "Reddit'e bağlanılamadı.",
                        openUrl = openUrl
                    )
                }
            )
            if (page.error == null) {
                redditCache.put(key, page)
                return@withContext page
            }
            lastError = page.error
        }
        RedditPage(error = lastError, openUrl = openUrl)
    }

    suspend fun fetchRedditComments(permalink: String): RedditCommentsPage = withContext(Dispatchers.IO) {
        val path = permalink.trim()
        if (path.isBlank()) {
            return@withContext RedditCommentsPage(error = "Yorum adresi yok.")
        }
        val normalized = if (path.startsWith("http")) path else "https://www.reddit.com$path"
        val openUrl = normalized.substringBefore(".json")
        commentCache.get(openUrl)?.let { return@withContext it }
        val jsonPath = if (openUrl.endsWith("/")) "${openUrl.dropLast(1)}.json" else "$openUrl.json"
        val hosts = listOf(
            jsonPath,
            jsonPath.replace("https://www.reddit.com", "https://old.reddit.com")
        )
        var lastError = "Yorumlar alınamadı."
        for (url in hosts.distinct()) {
            currentCoroutineContext().ensureActive()
            val withSort = if (url.contains("?")) "$url&sort=top&limit=20" else "$url?sort=top&limit=20"
            val page = runCatching { get(withSort, "application/json") }.fold(
                onSuccess = { http ->
                    http.use { resp ->
                        if (resp.code == 200) {
                            RedditCommentsPage(
                                comments = parseRedditComments(resp.body?.string().orEmpty()),
                                openUrl = openUrl
                            )
                        } else if (resp.code == 403 || resp.code == 429) {
                            RedditCommentsPage(
                                error = "Yorumlar engellendi (HTTP ${resp.code}).",
                                openUrl = openUrl
                            )
                        } else {
                            RedditCommentsPage(
                                error = "Yorumlar alınamadı (HTTP ${resp.code}).",
                                openUrl = openUrl
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    RedditCommentsPage(error = "Yorumlara bağlanılamadı.", openUrl = openUrl)
                }
            )
            if (page.error == null) {
                commentCache.put(openUrl, page)
                return@withContext page
            }
            lastError = page.error
        }
        RedditCommentsPage(error = lastError, openUrl = openUrl)
    }

    suspend fun fetchXPosts(
        query: String,
        entryTexts: List<String>,
        force: Boolean = false
    ): XFeedResult = withContext(Dispatchers.IO) {
        val shared = resolveStatusUrls(extractStatusLinks(entryTexts))
        val cleaned = query.trim()
        if (cleaned.isEmpty()) {
            return@withContext XFeedResult(
                shared = shared,
                recentNote = "X araması için bir terim yazın."
            )
        }
        val key = cleaned.lowercase(Locale.forLanguageTag("tr"))
        if (!force) {
            recentCache.get(key)?.let { cached ->
                return@withContext cached.copy(shared = shared)
            }
        }
        val recent = fetchRecentLinks(cleaned)
        val note = if (recent.isEmpty()) {
            "Canlı X araması bu ağdan sonuç vermedi. Nitter ve site:x.com köprüleri sık sık boş veya engelli döner. " +
                "Başlıkta geçen tweetler yukarıda kalır; uygulama içi arama X'in kendi sayfasını açar."
        } else {
            null
        }
        val result = XFeedResult(shared = shared, recent = recent, recentNote = note)
        recentCache.put(key, result)
        result
    }

    private suspend fun fetchRecentLinks(query: String): List<XPost> {
        val sources = recentSources(query)
        val links = LinkedHashSet<String>()
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            val found = runCatching { source.search(query) }.getOrElse { error ->
                if (error is CancellationException) throw error
                emptyList()
            }
            links.addAll(found)
            if (links.size >= 5) break
        }
        return resolveStatusUrls(links.take(5))
    }

    private fun recentSources(query: String): List<XRecentSearchSource> {
        val encoded = Uri.encode(query)
        val nitterHosts = listOf(
            "https://nitter.tiekoetter.com",
            "https://nitter.space",
            "https://lightbrd.com"
        )
        val nitter = nitterHosts.map { host ->
            UrlSearchSource(
                name = host,
                url = "$host/search/rss?f=tweets&q=$encoded"
            )
        }
        val web = UrlSearchSource(
            name = "bing",
            url = "https://www.bing.com/search?q=${Uri.encode("site:x.com $query")}&format=rss"
        )
        return nitter + web
    }

    private suspend fun resolveStatusUrls(urls: List<String>): List<XPost> = coroutineScope {
        urls.distinct().take(8).map { url ->
            async {
                currentCoroutineContext().ensureActive()
                oembedCache.get(url) ?: resolveOne(url)?.also { oembedCache.put(url, it) }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun resolveOne(url: String): XPost? {
        oembedSlots.withPermit {
            currentCoroutineContext().ensureActive()
            val oembedUrl = "https://publish.twitter.com/oembed?url=${Uri.encode(url)}&omit_script=true"
            val fromOembed = runCatching { get(oembedUrl, "application/json") }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }?.use { response ->
                if (!response.isSuccessful) null
                else parseOEmbed(response.body?.string().orEmpty(), url)
            }
            if (fromOembed != null) return fromOembed
            val id = statusIdOf(url) ?: return null
            val syndication = "https://cdn.syndication.twimg.com/tweet-result?id=$id&token=0"
            return runCatching { get(syndication, "application/json") }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }?.use { response ->
                if (!response.isSuccessful) null
                else parseSyndicationTweet(response.body?.string().orEmpty(), url)
            }
        }
    }

    private suspend fun get(url: String, accept: String) = withTimeout(12_000) {
        val request = Request.Builder()
            .url(url)
            .header(SOCIAL_UA_HEADER, SOCIAL_UA)
            .header("Accept", accept)
            .build()
        socialClient.newCall(request).await()
    }

    private inner class UrlSearchSource(
        override val name: String,
        private val url: String
    ) : XRecentSearchSource {
        override suspend fun search(query: String): List<String> {
            val response = runCatching { get(url, "application/rss+xml, application/xml, text/html;q=0.8") }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    return emptyList()
                }
            response.use { resp ->
                if (!resp.isSuccessful) return emptyList()
                return parseFeedStatusLinks(resp.body?.string().orEmpty())
            }
        }
    }
}
