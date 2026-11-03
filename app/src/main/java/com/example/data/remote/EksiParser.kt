package com.example.data.remote

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.example.data.model.EntryItem
import com.example.data.model.SearchFilter
import com.example.data.model.SearchPageData
import com.example.data.model.TopicFilterMode
import com.example.data.model.TopicItem
import com.example.data.model.TopicPageData
import com.example.network.WebViewCookieJar
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ekşi Sözlük gerçek zamanlı HTML yanıtlarını (hem Masaüstü hem Mobil DOM) Jsoup ile ayrıştıran sınıf.
 *
 * Özellikler:
 * 1. Sol frame (`ul.topic-list.partial li`, `ul.topic-list li`) içerisinden reklamları filtreleyerek
 *    başlık adı, URL, slug ve entry sayısını (`small`) ayrıştırır.
 * 2. "Olaylar" sekmesinde okunan başlığa tekrar tıklandığında `?a=tracked` yüzünden boş dönme hatasını yönetir.
 * 3. Başlık sayfasındaki (`#entry-item-list li`) tüm entry'leri, yazar, tarih, düzenleme tarihi (`editDate`),
 *    fav sayısı ve toplam sayfa sayısını (`div.pager` -> `data-pagecount`) ayrıştırır.
 * 4. Entry içindeki `(bkz: ...)`, gizli bkz `*` ve harici URL'leri Compose [AnnotatedString] yapısına dönüştürür.
 * 5. Arama sonuçlarını (`/basliklar/ara?SearchForm.Keywords=...&SearchForm.SortOrder=Count&p=N`) sayfalama hatası
 *    olmadan oluşturur ve ayrıştırır.
 */
@Singleton
class EksiParser @Inject constructor() {

    /**
     * 1. Sol frame (`ul.topic-list.partial li` veya yedek olarak `ul.topic-list li`) içerisinden
     * başlık adı, URL, slug ve okunmamış/toplam entry sayısını (`small`) ayrıştırır.
     */
    fun parseLeftFrameTopics(
        html: String,
        isTrackedSection: Boolean = false
    ): List<TopicItem> {
        if (html.isBlank()) return emptyList()
        val doc: Document = Jsoup.parse(html, BASE_URL)

        val listItems = doc.select("ul.topic-list.partial li").ifEmpty {
            doc.select("ul.topic-list li, #partial-index ul li, #content-body ul.topic-list li, ul#threads li")
        }

        val topics = mutableListOf<TopicItem>()
        for (li in listItems) {
            if (li.hasClass("sponsored") || li.id().contains("sponsored", ignoreCase = true)) {
                continue
            }

            val anchor = li.selectFirst("a[href]") ?: continue
            if (anchor.hasClass("sponsored") || anchor.hasClass("ad-banner")) {
                continue
            }

            val rawHref = anchor.attr("href").trim()
            if (rawHref.isBlank() ||
                rawHref.startsWith("javascript:") ||
                rawHref.contains("doubleclick.net", ignoreCase = true)
            ) {
                continue
            }

            val smallElement = anchor.selectFirst("small") ?: li.selectFirst("small")
            val detailElement = li.selectFirst("div.detail, span.detail")
            val countText = smallElement?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: detailElement?.text()?.trim().orEmpty()
            val entryCount = parseEntryCount(smallElement?.text()?.trim().orEmpty())

            val captionElement = anchor.selectFirst("span.caption")
            val title = if (captionElement != null && captionElement.text().isNotBlank()) {
                captionElement.text().trim()
            } else {
                val anchorClone = anchor.clone()
                anchorClone.select("small, div.detail, span.detail").remove()
                anchorClone.text().trim()
            }
            if (title.isBlank()) continue

            val normalizedUrl = normalizeUrl(rawHref)
            val slug = extractSlug(normalizedUrl)
            val topicId = extractTopicId(normalizedUrl).ifBlank { slug.ifBlank { normalizedUrl } }
            val isTracked = isTrackedSection || containsTrackedParam(normalizedUrl)

            topics.add(
                TopicItem(
                    id = topicId,
                    title = title,
                    slug = slug,
                    url = normalizedUrl,
                    entryCount = entryCount,
                    entryCountText = countText,
                    isTracked = isTracked
                )
            )
        }

        return topics.distinctBy { it.url }
    }

    /**
     * 2. "Olaylar" sekmesinde (`?a=tracked` veya `snapshot=`) başlık okunduktan sonra tekrar açıldığında
     * boş gelme hatasını önlemek için URL yönetimi.
     */
    fun containsTrackedParam(url: String): Boolean {
        return url.contains("a=tracked", ignoreCase = true) ||
            url.contains("snapshot=", ignoreCase = true)
    }

    fun stripTrackedParameters(url: String): String {
        val normalized = normalizeUrl(url)
        return normalized.substringBefore('?')
    }

    fun buildTopicPageUrl(
        baseOrRawUrl: String,
        page: Int,
        filterMode: TopicFilterMode = TopicFilterMode.ALL,
        searchKeywordInTopic: String = "",
        forceStripTracked: Boolean = false
    ): String {
        val normalized = normalizeUrl(baseOrRawUrl)
        val cleanPathUrl = normalized.substringBefore('?')

        if (page == 1 &&
            filterMode == TopicFilterMode.ALL &&
            searchKeywordInTopic.isBlank() &&
            !forceStripTracked &&
            containsTrackedParam(normalized)
        ) {
            return normalized
        }

        val uriBuilder = Uri.parse(cleanPathUrl).buildUpon()

        when (filterMode) {
            TopicFilterMode.ALL,
            TopicFilterMode.LINKS_ONLY -> {
                // Ekstra `a=` parametresi yok
            }
            TopicFilterMode.SUKELA_ALL -> {
                uriBuilder.appendQueryParameter("a", "nice")
            }
            TopicFilterMode.SUKELA_TODAY -> {
                uriBuilder.appendQueryParameter("a", "dailynice")
            }
            TopicFilterMode.SEARCH_IN_TOPIC -> {
                if (searchKeywordInTopic.isNotBlank()) {
                    uriBuilder.appendQueryParameter("a", "search")
                    uriBuilder.appendQueryParameter("keywords", searchKeywordInTopic.trim())
                }
            }
        }

        if (page > 1) {
            uriBuilder.appendQueryParameter("p", page.toString())
        }

        return uriBuilder.build().toString()
    }

    /**
     * 3. Başlık sayfasındaki (`#entry-item-list li`) tüm entry'leri, yazar adını, tarihini,
     * düzenleme tarihini (`editDate`), fav sayısını, fav durumunu ve toplam sayfa sayısını
     * (`div.pager` -> `data-pagecount`) ayrıştırır.
     */
    fun parseTopicPage(
        html: String,
        requestedUrl: String,
        fallbackPage: Int = 1,
        usedTrackedFallback: Boolean = false
    ): TopicPageData {
        val doc: Document = Jsoup.parse(html, BASE_URL)

        val titleContainer = doc.selectFirst("#title, h1#title")
        val titleAnchor = doc.selectFirst("#title a[href], h1#title a[href]")
        val topicTitle = titleContainer?.attr("data-title")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("#title a span[itemprop=name], #title a, h1#title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: extractReadableTitleFromUrl(requestedUrl)

        val canonicalUrl = titleAnchor?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.let { stripTrackedParameters(it) }
            ?: stripTrackedParameters(requestedUrl)

        val topicId = titleContainer?.attr("data-id")
            ?.takeIf { it.isNotBlank() }
            ?: extractTopicId(canonicalUrl)

        val topicSlug = titleContainer?.attr("data-slug")
            ?.takeIf { it.isNotBlank() }
            ?: extractSlug(canonicalUrl)

        // Sayfalama bilgisi: div.pager -> data-currentpage & data-pagecount
        val pagerElement = doc.selectFirst("div.pager")
        val currentPage = pagerElement?.attr("data-currentpage")?.toIntOrNull() ?: fallbackPage
        val pageCount = pagerElement?.attr("data-pagecount")?.toIntOrNull()
            ?: currentPage.coerceAtLeast(1)

        // Entry listesi: #entry-item-list li
        val entryNodes = doc.select(
            "#entry-item-list li[data-id], ul#entry-item-list > li, li[id^=entry-item][data-id]"
        )
        val rawEntries = entryNodes.mapNotNull { li ->
            parseEntryElement(
                li = li,
                topicTitle = topicTitle,
                topicUrl = canonicalUrl,
                pageNumber = currentPage
            )
        }

        val lowerReqUrl = requestedUrl.lowercase()
        val entries = when {
            lowerReqUrl.contains("a=nice") || lowerReqUrl.contains("a=dailynice") -> {
                rawEntries.sortedByDescending { it.favoriteCount }
            }
            lowerReqUrl.contains("a=search") && lowerReqUrl.contains("keywords=") -> {
                val rawKw = requestedUrl.substringAfter("keywords=", "").substringBefore("&")
                val decodedKw = try {
                    Uri.decode(rawKw)
                } catch (_: Throwable) {
                    rawKw
                }.removePrefix("@").trim()
                if (decodedKw.isNotBlank()) {
                    rawEntries.filter {
                        it.author.contains(decodedKw, ignoreCase = true) ||
                            it.plainText.contains(decodedKw, ignoreCase = true)
                    }.ifEmpty { rawEntries }
                } else {
                    rawEntries
                }
            }
            else -> rawEntries
        }

        return TopicPageData(
            topicId = topicId,
            topicTitle = topicTitle,
            topicSlug = topicSlug,
            cleanTopicUrl = canonicalUrl,
            currentPage = currentPage,
            pageCount = pageCount,
            entries = entries,
            usedTrackedFallback = usedTrackedFallback
        )
    }

    private fun parseEntryElement(
        li: Element,
        topicTitle: String,
        topicUrl: String,
        pageNumber: Int
    ): EntryItem? {
        val entryId = li.attr("data-id")
            .ifBlank { li.id().removePrefix("entry-item-") }
            .trim()
        if (entryId.isBlank()) return null

        val contentElement = li.selectFirst("div.content") ?: return null
        val contentHtml = contentElement.html().trim()
        val annotatedContent = buildEntryAnnotatedString(contentElement)

        val author = li.attr("data-author")
            .ifBlank { li.selectFirst("a.entry-author, footer a.entry-author")?.text().orEmpty() }
            .trim()
            .ifBlank { "anonim" }

        val authorHref = li.selectFirst("a.entry-author")?.attr("href").orEmpty()
        val authorProfileUrl = if (authorHref.isNotBlank()) {
            normalizeUrl(authorHref)
        } else {
            "$BASE_URL/biri/${Uri.encode(author)}"
        }

        val avatarUrl = li.selectFirst("img.avatar, div.avatar-container img")
            ?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("src").takeIf { it.isNotBlank() }
            }
            ?.let { normalizeUrl(it) }

        val rawDateText = li.selectFirst("a.entry-date, footer div.info a.entry-date, a.permalink")
            ?.text()
            ?.trim()
            .orEmpty()
        val (createdDate, editedDate) = splitEntryDates(rawDateText)

        val favoriteCount = li.attr("data-favorite-count").toIntOrNull()
            ?: li.selectFirst("span.favorite-count")?.text()?.trim()?.toIntOrNull()
            ?: 0

        val isFavorited = li.attr("data-isfavorite").equals("true", ignoreCase = true)

        val hasExternalLink = contentElement.select("a.url, a[href^=http], a[href^=https]")
            .any { a ->
                val href = a.attr("href")
                href.startsWith("http", ignoreCase = true) &&
                    !href.contains("eksisozluk", ignoreCase = true)
            }

        return EntryItem(
            id = entryId,
            contentHtml = contentHtml,
            annotatedContent = annotatedContent,
            author = author,
            date = createdDate,
            editDate = editedDate,
            favoriteCount = favoriteCount,
            isFavorited = isFavorited,
            topicTitle = topicTitle,
            topicUrl = topicUrl,
            authorProfileUrl = authorProfileUrl,
            avatarUrl = avatarUrl,
            pageNumber = pageNumber,
            hasExternalLink = hasExternalLink
        )
    }

    /**
     * 4. Entry içindeki `(bkz: ...)`, gizli bkz `*` ve harici URL'leri Compose [AnnotatedString]
     * yapısına uygun şekilde ayrıştıran yardımcı fonksiyon.
     */
    fun buildEntryAnnotatedString(contentElement: Element): AnnotatedString {
        return buildAnnotatedString {
            appendNodesRecursively(contentElement.childNodes(), this)
        }
    }

    fun parseHtmlToAnnotatedString(contentHtml: String): AnnotatedString {
        val fragment = Jsoup.parseBodyFragment(contentHtml, BASE_URL).body()
        return buildEntryAnnotatedString(fragment)
    }

    private fun appendNodesRecursively(
        nodes: List<Node>,
        builder: AnnotatedString.Builder
    ) {
        for (node in nodes) {
            when (node) {
                is TextNode -> {
                    val text = node.wholeText
                    if (text.isNotEmpty()) {
                        builder.append(text)
                    }
                }
                is Element -> {
                    when (node.tagName().lowercase()) {
                        "br" -> builder.append("\n")
                        "p" -> {
                            if (builder.length > 0) builder.append("\n")
                            appendNodesRecursively(node.childNodes(), builder)
                            builder.append("\n")
                        }
                        "sup" -> {
                            val innerAnchor = node.selectFirst("a")
                            if (node.hasClass("ab") && innerAnchor != null) {
                                appendHiddenBkzAnnotation(innerAnchor, builder)
                            } else {
                                appendNodesRecursively(node.childNodes(), builder)
                            }
                        }
                        "a" -> {
                            appendAnchorAnnotation(node, builder)
                        }
                        else -> {
                            appendNodesRecursively(node.childNodes(), builder)
                        }
                    }
                }
            }
        }
    }

    private fun appendHiddenBkzAnnotation(
        anchor: Element,
        builder: AnnotatedString.Builder
    ) {
        val targetQuery = extractBkzQueryFromAnchor(anchor)
            .removePrefix("(bkz:")
            .removeSuffix(")")
            .trim()
        val label = if (targetQuery.isNotBlank()) {
            " [✨ bkz: $targetQuery]"
        } else {
            " [✨ bkz]"
        }

        val start = builder.length
        builder.pushStringAnnotation(tag = TAG_HIDDEN_BKZ, annotation = targetQuery)
        builder.pushStyle(
            SpanStyle(
                color = COLOR_BKZ_GREEN,
                background = Color(0xFF112415),
                fontWeight = FontWeight.SemiBold,
                fontStyle = FontStyle.Normal
            )
        )
        builder.append(label)
        builder.pop()
        builder.pop()
        val end = builder.length
        builder.addStringAnnotation(
            tag = TAG_BKZ,
            annotation = targetQuery,
            start = start,
            end = end
        )
    }

    private fun appendAnchorAnnotation(
        anchor: Element,
        builder: AnnotatedString.Builder
    ) {
        val href = anchor.attr("href").trim()
        val cssClass = anchor.className().lowercase()
        val visibleText = anchor.text()

        val isHiddenBkz = anchor.parent()?.hasClass("ab") == true ||
            visibleText.trim() == "*" && (cssClass.contains("b") || href.contains("?q="))

        if (isHiddenBkz) {
            appendHiddenBkzAnnotation(anchor, builder)
            return
        }

        val isInternalBkz = cssClass.split(" ").contains("b") ||
            href.startsWith("/?q=", ignoreCase = true) ||
            href.contains("/?q=", ignoreCase = true) ||
            href.startsWith("/entry/", ignoreCase = true)

        if (isInternalBkz) {
            val targetQuery = extractBkzQueryFromAnchor(anchor)
            builder.pushStringAnnotation(tag = TAG_BKZ, annotation = targetQuery)
            builder.pushStyle(
                SpanStyle(
                    color = COLOR_BKZ_GREEN,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.None
                )
            )
            builder.append(visibleText.ifBlank { targetQuery })
            builder.pop()
            builder.pop()
        } else {
            val fullExternalUrl = normalizeUrl(href)
            builder.pushStringAnnotation(tag = TAG_EXTERNAL_URL, annotation = fullExternalUrl)
            builder.pushStyle(
                SpanStyle(
                    color = COLOR_EXTERNAL_LINK,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium
                )
            )
            builder.append(visibleText.ifBlank { fullExternalUrl })
            builder.pop()
            builder.pop()
        }
    }

    private fun extractBkzQueryFromAnchor(anchor: Element): String {
        val dataQuery = anchor.attr("data-query").trim()
        if (dataQuery.isNotBlank()) return dataQuery

        val titleAttr = anchor.attr("title").trim()
        if (titleAttr.isNotBlank() && !titleAttr.startsWith("http")) return titleAttr

        val href = anchor.attr("href").trim()
        if (href.contains("?q=")) {
            val rawQuery = href.substringAfter("?q=").substringBefore("&")
            return Uri.decode(rawQuery).replace("+", " ").trim()
        }

        return anchor.text().trim()
    }

    /**
     * 5. Arama sonuçlarını (`/basliklar/ara?SearchForm.Keywords=...&SearchForm.SortOrder=Count&p=N`)
     * sayfalama hatası olmadan çeken URL oluşturucu ve HTML ayrıştırıcı.
     */
    fun buildSearchUrl(filter: SearchFilter, page: Int): String {
        val safePage = page.coerceAtLeast(1)
        val encodedKeywords = Uri.encode(filter.keywords.trim())
        return buildString {
            append(BASE_URL)
            append("/basliklar/ara")
            append("?SearchForm.Keywords=").append(encodedKeywords)
            if (filter.author.isNotBlank()) {
                append("&SearchForm.Author=").append(Uri.encode(filter.author.trim()))
            }
            if (filter.niceOnly) {
                append("&SearchForm.NiceOnly=true")
            }
            append("&SearchForm.SortOrder=").append(filter.sortOrder.apiValue)
            append("&p=").append(safePage)
        }
    }

    fun parseSearchResults(
        html: String,
        query: String,
        requestedPage: Int
    ): SearchPageData {
        val doc = Jsoup.parse(html, BASE_URL)
        val pager = doc.selectFirst("div.pager")
        val currentPage = pager?.attr("data-currentpage")?.toIntOrNull() ?: requestedPage
        val topics = parseLeftFrameTopics(html = html, isTrackedSection = false)
        val pageCount = pager?.attr("data-pagecount")?.toIntOrNull()
            ?: if (topics.size >= 10) requestedPage + 1 else requestedPage

        return SearchPageData(
            query = query,
            currentPage = currentPage,
            pageCount = pageCount,
            topics = topics
        )
    }

    /**
     * Entry tarih metnini oluşturulma (`date`) ve düzenlenme (`editDate`) olarak ikiye ayırır.
     */
    private fun splitEntryDates(rawDate: String): Pair<String, String?> {
        if (!rawDate.contains("~")) {
            return rawDate.trim() to null
        }
        val parts = rawDate.split("~", limit = 2)
        val created = parts[0].trim()
        val edited = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        return created to edited
    }

    /**
     * Sol frame `<small>` içerisindeki `"1,2b"`, `"45"`, `"3b"` gibi değerleri tam sayıya çevirir.
     */
    private fun parseEntryCount(rawCount: String): Int {
        val cleaned = rawCount.trim().lowercase()
        if (cleaned.isEmpty()) return 0
        if (cleaned.endsWith("b")) {
            val numberPart = cleaned.removeSuffix("b").replace(",", ".").toDoubleOrNull() ?: 0.0
            return (numberPart * 1000).toInt()
        }
        return cleaned.filter { it.isDigit() }.toIntOrNull() ?: 0
    }

    fun normalizeUrl(href: String): String {
        val trimmed = href.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$BASE_URL$trimmed"
            else -> "$BASE_URL/$trimmed"
        }
    }

    private fun extractSlug(url: String): String {
        val withoutQuery = url.substringBefore('?').trim()
        val path = if (withoutQuery.contains("://")) {
            withoutQuery.substringAfter("://").substringAfter('/', "").trim('/')
        } else {
            withoutQuery.trim('/')
        }
        return path.substringBefore("--").ifBlank { path }
    }

    private fun extractTopicId(url: String): String {
        val path = url.substringBefore('?')
        return if (path.contains("--")) {
            path.substringAfterLast("--").filter { it.isDigit() }
        } else if (path.contains("/entry/")) {
            path.substringAfter("/entry/").filter { it.isDigit() }
        } else {
            ""
        }
    }

    private fun extractReadableTitleFromUrl(url: String): String {
        return extractSlug(url)
            .replace("-", " ")
            .trim()
            .ifBlank { "ekşi sözlük" }
    }

    companion object {
        const val BASE_URL = WebViewCookieJar.DEFAULT_BASE_URL

        const val TAG_BKZ = "BKZ_TOPIC"
        const val TAG_HIDDEN_BKZ = "HIDDEN_BKZ_TOPIC"
        const val TAG_EXTERNAL_URL = "EXTERNAL_URL"

        private val COLOR_BKZ_GREEN = Color(0xFF81C784)
        private val COLOR_HIDDEN_BKZ = Color(0xFFFFB74D)
        private val COLOR_EXTERNAL_LINK = Color(0xFF64B5F6)
    }
}
