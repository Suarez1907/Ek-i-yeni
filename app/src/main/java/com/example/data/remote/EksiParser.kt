package com.example.data.remote

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.example.data.model.EksiChannel
import com.example.data.model.EntryItem
import com.example.data.model.PeriodicTimeRange
import com.example.data.model.RankedTopicOrEntryItem
import com.example.data.model.SearchFilter
import com.example.data.model.SearchPageData
import com.example.data.model.TopicFilterMode
import com.example.data.model.TopicItem
import com.example.data.model.TopicPageData
import com.example.network.WebViewCookieJar
import com.example.ui.theme.ActiveThemeState
import com.example.ui.theme.AppThemeMode
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tekil bir entry detay sayfasından çekilen beğeni (favori) ve yazar özeti.
 */
data class EntryDetailMetadata(
    val entryId: String,
    val favoriteCount: Int,
    val author: String?,
    val topicTitle: String?,
    val entryDate: String = ""
)

/**
 * Ekşi Sözlük gerçek zamanlı HTML ve JSON yanıtlarını (Masaüstü ve Mobil DOM) Jsoup ve JSONObject ile ayrıştıran sınıf.
 *
 * Özellikler:
 * 1. Sol frame (`ul.topic-list.partial li`, `ul.topic-list li`) ve Kanallar (`/basliklar/kanal/{slug}?p={page}`)
 *    içerisinden reklamları filtreleyerek başlık adı, URL, slug ve entry sayısını (`small`) ayrıştırır.
 * 2. "Olaylar" sekmesinde okunan başlığa tekrar tıklandığında `?a=tracked` yüzünden boş dönme hatasını
 *    ve "Sadece Bugün" (`?a=popular` / `?day=`) modunda 2..N sayfalarında filtrenin korunmasını yönetir.
 * 3. Başlık sayfasındaki (`#entry-item-list li`) tüm entry'leri, yazar, tarih, düzenleme tarihi (`editDate`),
 *    fav sayısı ve toplam sayfa sayısını (`div.pager` -> `data-pagecount`) ayrıştırır.
 * 4. DEBE (`/debe` - dünün en beğenilen 50 entry'si) ve Dönemsel Enler (`/istatistik/...`) listelerini ayrıştırır.
 * 5. Entry içindeki `(bkz: ...)`, gizli bkz `*` ve harici URL'leri aktif temaya duyarlı Compose [AnnotatedString] yapısına dönüştürür.
 * 6. Arama sonuçlarını (`/basliklar/ara?SearchForm.Keywords=...&SearchForm.SortOrder=Count&p=N`) ve
 *    anlık arama önerilerini (`/autocomplete/query?q=...` -> `{"Titles":[...],"Nicks":[...]}`) ayrıştırır.
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

            val favAttr = li.attr("data-favorite-count").toIntOrNull()
                ?: li.selectFirst(".favorite-count, span.favorite-count")?.text()?.trim()?.let { parseEntryCount(it) }
                ?: 0

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
                    favoriteCount = favAttr,
                    isTracked = isTracked
                )
            )
        }

        return topics.distinctBy { it.url }
    }

    /**
     * Kanal başlıkları URL oluşturucu:
     * `https://eksisozluk.com/basliklar/kanal/{kanal_slug}?p={page}`
     */
    fun buildChannelTopicsUrl(
        baseDomain: String = BASE_URL,
        channel: EksiChannel,
        page: Int,
        useRawTurkishSlug: Boolean = false
    ): String {
        val safeDomain = baseDomain.ifBlank { BASE_URL }.trimEnd('/')
        val safePage = page.coerceAtLeast(1)
        val slugToUse = if (useRawTurkishSlug) {
            Uri.encode(channel.rawSlug)
        } else {
            Uri.encode(channel.slug)
        }
        return "$safeDomain/basliklar/kanal/$slugToUse?p=$safePage"
    }

    /**
     * Kanal sayfasındaki başlıkları ve entry sayılarını ayrıştırır.
     */
    fun parseChannelTopics(html: String): List<TopicItem> {
        return parseLeftFrameTopics(html = html, isTrackedSection = false)
    }

    /**
     * DEBE (`/debe` veya `/basliklar/debe`) sayfasından dünün en beğenilen 50 entry'sini ayrıştırır.
     * Hem tam sayfa (`#content-body ul.topic-list li a`, `.debe-list a`, `ol.stats-list li a`, `a[href^="/entry/"]`)
     * hem de AJAX sol frame (`#partial-index ul.topic-list li a`, `ul.topic-list.partial li a`) DOM yapılarını destekler.
     * Eğer HTML'de favori sayısı doğrudan varsa `favoriteCount` ve `♥ {fav}` rozetini doldurur.
     */
    fun parseDebeEntriesList(html: String): List<RankedTopicOrEntryItem> {
        if (html.isBlank()) return emptyList()
        val doc: Document = Jsoup.parse(html, BASE_URL)

        val candidateElements = mutableListOf<Element>()

        val primaryLiSelectors = listOf(
            "#content-body ul.topic-list li",
            "#partial-index ul.topic-list li",
            "ul.topic-list.partial li",
            ".debe-list li",
            "ol.debe-list li",
            "ol.stats-list li",
            "#partial-index ul li",
            "ul.topic-list li",
            "ul#threads li"
        )
        for (selector in primaryLiSelectors) {
            val found = doc.select(selector).filter { el ->
                el.parents().none { p ->
                    p.id().equals("entry-item-list", ignoreCase = true) ||
                        p.hasClass("entry-footer") ||
                        p.tagName().equals("footer", ignoreCase = true)
                }
            }
            if (found.isNotEmpty()) {
                candidateElements.addAll(found)
                break
            }
        }

        if (candidateElements.isEmpty()) {
            val directAnchors = doc.select(
                ".debe-list a[href], #content-body a[href^=\"/entry/\"], #content-body a[href*=\"/entry/\"], #partial-index a[href*=\"/entry/\"], a[href*=\"a=debe\"]"
            ).filter { anchor ->
                anchor.parents().none { p ->
                    p.id().equals("entry-item-list", ignoreCase = true) ||
                        p.hasClass("info") ||
                        p.tagName().equals("footer", ignoreCase = true)
                }
            }
            candidateElements.addAll(directAnchors)
        }

        val result = mutableListOf<RankedTopicOrEntryItem>()
        val seenKeys = LinkedHashSet<String>()
        var rankCounter = 1

        for (element in candidateElements) {
            if (rankCounter > 50) break
            if (element.hasClass("sponsored") || element.id().contains("sponsored", ignoreCase = true)) {
                continue
            }

            val anchor = if (element.tagName().equals("a", ignoreCase = true)) {
                element
            } else {
                element.selectFirst("a[href^=\"/entry/\"], a[href*=\"/entry/\"], a[href*=\"a=debe\"], a[href]")
            } ?: continue

            val rawHref = anchor.attr("href").trim()
            if (rawHref.isBlank() ||
                rawHref.startsWith("javascript:") ||
                rawHref.startsWith("/biri/", ignoreCase = true) ||
                rawHref.contains("/iletisim", ignoreCase = true) ||
                rawHref.contains("/reklam", ignoreCase = true)
            ) {
                continue
            }

            val caption = anchor.selectFirst("span.caption")?.text()?.trim()
                ?: element.selectFirst("span.caption")?.text()?.trim()
            val detailAuthor = element.selectFirst("div.detail, span.detail, a.entry-author")?.text()?.trim()
            val smallText = (anchor.selectFirst("small") ?: element.selectFirst("small"))?.text()?.trim().orEmpty()

            val explicitFav = element.attr("data-favorite-count").toIntOrNull()
                ?: anchor.attr("data-favorite-count").toIntOrNull()
                ?: element.selectFirst(".favorite-count, span.favorite-count")?.text()?.trim()?.let { parseEntryCount(it) }
                ?: parseEntryCount(smallText)

            val rawTitle = if (!caption.isNullOrBlank()) {
                caption
            } else {
                val clone = anchor.clone()
                clone.select("small, div.detail, span.detail").remove()
                clone.text().trim()
            }
            val cleanedTitle = rawTitle
                .replace(Regex("""^#\d+\s*[-–—:]?\s*"""), "")
                .trim()
            if (cleanedTitle.isBlank()) continue

            val normalizedUrl = normalizeUrl(rawHref)
            val entryIdFromHref = if (normalizedUrl.contains("/entry/")) {
                normalizedUrl.substringAfter("/entry/").substringBefore("?").substringBefore("/").filter { it.isDigit() }
            } else {
                ""
            }
            val entryIdFromAttr = element.attr("data-entry-id").filter { it.isDigit() }
                .ifBlank { anchor.attr("data-entry-id").filter { it.isDigit() } }
                .ifBlank { element.attr("data-id").filter { it.isDigit() } }

            val entryId = entryIdFromHref.ifBlank { entryIdFromAttr }.takeIf { it.isNotBlank() }
            val finalUrl = if (entryId != null) {
                "$BASE_URL/entry/$entryId"
            } else {
                normalizedUrl
            }
            val dedupeKey = entryId ?: finalUrl
            if (!seenKeys.add(dedupeKey)) continue

            val isDirectEntry = entryId != null || finalUrl.contains("a=debe", ignoreCase = true)

            val badgeText = when {
                explicitFav > 0 -> "♥ $explicitFav"
                smallText.isNotBlank() && smallText.all { it.isDigit() || it == '.' || it == ',' || it == 'b' } -> "♥ $smallText"
                smallText.isNotBlank() -> smallText
                else -> "♥ ..."
            }

            result.add(
                RankedTopicOrEntryItem(
                    rank = rankCounter,
                    id = entryId ?: extractTopicId(finalUrl).ifBlank { "debe_$rankCounter" },
                    title = cleanedTitle,
                    url = finalUrl,
                    badgeText = badgeText,
                    favoriteCount = explicitFav,
                    author = detailAuthor?.takeIf { it.isNotBlank() },
                    entryId = entryId,
                    isDirectEntry = isDirectEntry
                )
            )
            rankCounter++
        }

        return result.take(50)
    }

    /**
     * Entry tarihinin (`.entry-date`) içinde bulunduğumuz güncel yıla (2026) ait olup olmadığını kontrol eder.
     * 2010-2025 gibi eski yıllara ait entry'leri kesin olarak reddeder.
     */
    fun isRecent2026EntryDate(dateText: String): Boolean {
        val createdPart = dateText.substringBefore("~").trim()
        if (createdPart.isBlank()) return false
        if (OLD_YEARS_REGEX.containsMatchIn(createdPart)) {
            return false
        }
        return createdPart.contains("2026")
    }

    /**
     * Bir DEBE veya tekil entry detay sayfasının HTML'inden `data-favorite-count` (veya `.favorite-count`)
     * değerini, yazar bilgisini, oluşturma tarihini ve o entry'nin ait olduğu gerçek başlık adını
     * (`#topic h1#title` / `data-title`) ayrıştırır.
     */
    fun parseEntryDetailMetadata(
        html: String,
        targetEntryId: String? = null
    ): EntryDetailMetadata? {
        if (html.isBlank()) return null
        val doc: Document = Jsoup.parse(html, BASE_URL)
        val cleanTargetId = targetEntryId?.trim()?.filter { it.isDigit() }.orEmpty()

        val entrySelector = if (cleanTargetId.isNotBlank()) {
            "#entry-item-list li[data-id=$cleanTargetId], li#entry-item-$cleanTargetId, #entry-item-list li[data-id], ul#entry-item-list > li"
        } else {
            "#entry-item-list li[data-id], ul#entry-item-list > li, li[id^=entry-item][data-id]"
        }

        val li = doc.selectFirst(entrySelector)
        if (li != null) {
            val resolvedEntryId = li.attr("data-id")
                .ifBlank { li.id().removePrefix("entry-item-") }
                .ifBlank { cleanTargetId }
                .trim()
                .filter { it.isDigit() }

            val favCount = li.attr("data-favorite-count").toIntOrNull()
                ?: li.selectFirst("span.favorite-count, .favorite-count")
                    ?.text()
                    ?.trim()
                    ?.let { parseEntryCount(it) }
                ?: 0

            val author = li.attr("data-author")
                .ifBlank { li.selectFirst("a.entry-author, footer a.entry-author")?.text().orEmpty() }
                .trim()
                .takeIf { it.isNotBlank() }

            val rawDateText = li.selectFirst("a.entry-date, footer div.info a.entry-date, a.permalink")
                ?.text()
                ?.trim()
                .orEmpty()
            val createdDate = splitEntryDates(rawDateText).first

            val itemScope = li.parents().firstOrNull { it.hasClass("topic-item") || it.id() == "topic" } ?: doc
            val titleEl = itemScope.selectFirst("#title, h1#title")
                ?: doc.selectFirst("#topic h1#title, #title, h1#title")

            val topicTitle = titleEl?.attr("data-title")?.trim()?.takeIf { it.isNotBlank() }
                ?: titleEl?.selectFirst("a span[itemprop=name], a")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: titleEl?.text()?.trim()?.takeIf { it.isNotBlank() }

            return EntryDetailMetadata(
                entryId = resolvedEntryId,
                favoriteCount = favCount,
                author = author,
                topicTitle = topicTitle,
                entryDate = createdDate
            )
        }

        val fallbackFavElement = doc.selectFirst("[data-favorite-count], .favorite-count, span.favorite-count")
        if (fallbackFavElement != null) {
            val favCount = fallbackFavElement.attr("data-favorite-count").toIntOrNull()
                ?: parseEntryCount(fallbackFavElement.text())
            val author = doc.selectFirst("[data-author], a.entry-author")?.let {
                it.attr("data-author").ifBlank { it.text() }.trim()
            }?.takeIf { it.isNotBlank() }
            val titleEl = doc.selectFirst("#topic h1#title, #title, h1#title")
            val topicTitle = titleEl?.attr("data-title")?.trim()?.takeIf { it.isNotBlank() }
                ?: titleEl?.selectFirst("a span[itemprop=name], a")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: titleEl?.text()?.trim()?.takeIf { it.isNotBlank() }
            return EntryDetailMetadata(
                entryId = cleanTargetId,
                favoriteCount = favCount,
                author = author,
                topicTitle = topicTitle,
                entryDate = ""
            )
        }

        return null
    }

    /**
     * Dönemsel Enler (`/istatistik/gecen-haftanin-en-begenilen-entryleri`,
     * `/istatistik/gecen-ayin-en-begenilen-entryleri`, vb.) tam sayfa (`isAjax = false`)
     * istatistik HTML yanıtını ayrıştırır.
     */
    fun parsePeriodicStatsList(
        html: String,
        isEntriesMode: Boolean,
        period: PeriodicTimeRange
    ): List<RankedTopicOrEntryItem> {
        if (html.isBlank()) return emptyList()
        val fullDoc: Document = Jsoup.parse(html, BASE_URL)

        if (fullDoc.selectFirst("form#login-form-container, input#Password, #login-form") != null) {
            return emptyList()
        }

        if (fullDoc.select("#entry-item-list li[data-id], div.topic-item").isNotEmpty()) {
            return emptyList()
        }

        val clone = fullDoc.clone()
        clone.select(
            "#partial-index, nav#partial-index, #top-navigation, #sub-navigation, aside, header, footer, #entry-item-list, div.topic-item"
        ).remove()
        val scopeDoc: Element = clone.selectFirst("#content-body, #content, main, article") ?: return emptyList()

        val candidateRows = scopeDoc.select(
            "ul.topic-list li, ol.stats-list li, div.stats-container li, table tr"
        )
        if (candidateRows.isEmpty()) return emptyList()

        val parsedItems = mutableListOf<RankedTopicOrEntryItem>()
        var rank = 1

        for (row in candidateRows) {
            if (rank > 50) break
            if (row.hasClass("sponsored") || row.id().contains("sponsored", ignoreCase = true)) continue
            if (row.tagName().equals("tr", ignoreCase = true) && row.select("th").isNotEmpty()) continue

            if (isEntriesMode) {
                val entryAnchor = row.selectFirst("a[href*='/entry/']") ?: continue
                val rawEntryHref = entryAnchor.attr("href").trim()
                val normalizedEntryUrl = normalizeUrl(rawEntryHref).substringBefore('?')
                val realEntryId = normalizedEntryUrl
                    .substringAfter("/entry/", "")
                    .filter { it.isDigit() }
                if (realEntryId.isBlank()) continue

                val topicAnchor = row.select("a[href]").firstOrNull { a ->
                    val h = a.attr("href").trim()
                    h.isNotBlank() &&
                        !h.contains("/entry/", ignoreCase = true) &&
                        !h.contains("/biri/", ignoreCase = true) &&
                        !h.startsWith("javascript:") &&
                        !h.startsWith("#")
                } ?: entryAnchor

                val smallElement = entryAnchor.selectFirst("small")
                    ?: topicAnchor.selectFirst("small")
                    ?: row.selectFirst("small, span.favorite-count, .favorite-count, td.count, td:last-child")

                val smallText = smallElement?.takeIf { it != entryAnchor && it != topicAnchor }
                    ?.text()?.trim().orEmpty()

                val favCount = row.attr("data-favorite-count").toIntOrNull()
                    ?: entryAnchor.attr("data-favorite-count").toIntOrNull()
                    ?: parseEntryCount(smallText)

                val authorElement = row.selectFirst("a[href*='/biri/'], a.entry-author, span.author, div.detail, span.detail")
                val detailAuthor = authorElement?.text()?.trim()?.removePrefix("@")?.takeIf {
                    it.isNotBlank() && !it.equals(smallText, ignoreCase = true)
                }

                val caption = topicAnchor.selectFirst("span.caption")?.text()?.trim()
                    ?: entryAnchor.selectFirst("span.caption")?.text()?.trim()

                val extractedTitle = if (!caption.isNullOrBlank()) {
                    caption
                } else {
                    val anchorClone = topicAnchor.clone()
                    anchorClone.select("small, div.detail, span.detail, span.author").remove()
                    anchorClone.text().trim()
                }.removePrefix("#").trim()

                if (extractedTitle.isBlank() || extractedTitle.all { it.isDigit() }) continue

                parsedItems.add(
                    RankedTopicOrEntryItem(
                        rank = rank,
                        id = realEntryId,
                        title = extractedTitle,
                        url = "$BASE_URL/entry/$realEntryId",
                        badgeText = if (favCount > 0) "♥ $favCount" else "♥ ...",
                        favoriteCount = favCount,
                        author = detailAuthor,
                        entryId = realEntryId,
                        isDirectEntry = true
                    )
                )
                rank++
            } else {
                val topicAnchor = row.select("a[href]").firstOrNull { a ->
                    val h = a.attr("href").trim()
                    h.isNotBlank() &&
                        !h.startsWith("javascript:") &&
                        !h.startsWith("#") &&
                        !h.contains("/entry/", ignoreCase = true) &&
                        !h.contains("/biri/", ignoreCase = true) &&
                        !h.contains("/giris", ignoreCase = true) &&
                        !h.contains("/kayit", ignoreCase = true) &&
                        !h.contains("/iletisim", ignoreCase = true)
                } ?: continue

                val rawTopicHref = topicAnchor.attr("href").trim()
                val cleanTopicUrl = stripTrackedParameters(rawTopicHref)
                val topicId = extractTopicId(cleanTopicUrl)
                val slug = extractSlug(cleanTopicUrl)
                if (slug.isBlank()) continue

                val smallElement = topicAnchor.selectFirst("small")
                    ?: row.selectFirst("small, td.count, td:last-child")
                val smallText = smallElement?.takeIf { it != topicAnchor }?.text()?.trim().orEmpty()
                val entryCount = parseEntryCount(smallText)

                val caption = topicAnchor.selectFirst("span.caption")?.text()?.trim()
                val extractedTitle = if (!caption.isNullOrBlank()) {
                    caption
                } else {
                    val anchorClone = topicAnchor.clone()
                    anchorClone.select("small, div.detail, span.detail, span.author").remove()
                    anchorClone.text().trim()
                }.trim()

                if (extractedTitle.isBlank() || extractedTitle.length < 2) continue

                val badge = when {
                    entryCount > 0 -> "${smallText.ifBlank { entryCount.toString() }} entry"
                    smallText.isNotBlank() -> "$smallText entry"
                    else -> "Popüler"
                }

                parsedItems.add(
                    RankedTopicOrEntryItem(
                        rank = rank,
                        id = topicId.ifBlank { slug },
                        title = extractedTitle,
                        url = cleanTopicUrl,
                        badgeText = badge,
                        favoriteCount = entryCount,
                        author = null,
                        entryId = null,
                        isDirectEntry = false
                    )
                )
                rank++
            }
        }

        if (parsedItems.isNotEmpty()) {
            val distinctList = if (isEntriesMode) {
                parsedItems
                    .distinctBy { it.entryId ?: it.url }
                    .distinctBy { it.title.trim().lowercase() }
                    .take(50)
            } else {
                parsedItems
                    .distinctBy { it.url }
                    .distinctBy { it.title.trim().lowercase() }
                    .take(50)
            }
            return if (isEntriesMode && distinctList.any { it.favoriteCount > 0 }) {
                distinctList
                    .sortedByDescending { it.favoriteCount }
                    .mapIndexed { idx, item -> item.copy(rank = idx + 1) }
            } else {
                distinctList.mapIndexed { idx, item -> item.copy(rank = idx + 1) }
            }
        }

        return emptyList()
    }

    /**
     * 2. "Olaylar" sekmesinde (`?a=tracked` veya `snapshot=`) başlık okunduktan sonra tekrar açıldığında
     * boş gelme hatasını önlemek için URL yönetimi.
     */
    fun containsTrackedParam(url: String): Boolean {
        return url.contains("a=tracked", ignoreCase = true) ||
            url.contains("snapshot=", ignoreCase = true)
    }

    fun containsTodayPopularParam(url: String): Boolean {
        return url.contains("a=popular", ignoreCase = true) ||
            url.contains("a=today", ignoreCase = true) ||
            url.contains("day=", ignoreCase = true)
    }

    fun isDirectSingleEntryUrl(url: String): Boolean {
        val clean = url.substringBefore('?')
        return clean.contains("/entry/")
    }

    fun stripTrackedParameters(url: String): String {
        val normalized = normalizeUrl(url)
        return normalized.substringBefore('?')
    }

    /**
     * URL içinde `?day=YYYY-MM-DD` parametresi varsa değerini döndürür.
     */
    fun extractDayParameter(url: String): String? {
        return runCatching {
            Uri.parse(normalizeUrl(url)).getQueryParameter("day")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Başlık içi sayfalama URL'sini oluşturur:
     * - Seçili olan filtre modunu (`Bugün`, `Tümü`, `Şükela Bugün`, `Şükela Tümü`, `Başlıkta Ara`)
     *   ve varsa `day=YYYY-MM-DD` parametresini 2..N sayfalarında da KESİNLİKLE KORUR.
     * - `Uri.parse(cleanPathUrl).buildUpon()` kullanarak `?a=popular&p=2` veya `?day=YYYY-MM-DD&p=2`
     *   üretir; asla `?a=popular?p=2` gibi çift soru işareti hatası oluşturmaz.
     */
    fun buildTopicPageUrl(
        baseOrRawUrl: String,
        page: Int,
        filterMode: TopicFilterMode = TopicFilterMode.ALL,
        searchKeywordInTopic: String = "",
        forceStripTracked: Boolean = false,
        forceAllEntriesFallback: Boolean = false
    ): String {
        val normalized = normalizeUrl(baseOrRawUrl)
        val cleanPathUrl = normalized.substringBefore('?')
        val safePage = page.coerceAtLeast(1)

        // Eğer DEBE veya doğrudan tekil `/entry/{id}` linkine tıklandıysa ve ilk sayfadaysak:
        if (safePage == 1 && isDirectSingleEntryUrl(cleanPathUrl) && !forceStripTracked && !forceAllEntriesFallback) {
            return cleanPathUrl
        }

        // Olaylar sekmesinden gelen `?a=tracked` URL'si 1. sayfada korunur (boş dönerse fallback yapılır)
        if (safePage == 1 &&
            (filterMode == TopicFilterMode.ALL || filterMode == TopicFilterMode.TODAY_POPULAR) &&
            searchKeywordInTopic.isBlank() &&
            !forceStripTracked &&
            !forceAllEntriesFallback &&
            containsTrackedParam(normalized)
        ) {
            return normalized
        }

        val existingDayParam = extractDayParameter(normalized)
        val uriBuilder = Uri.parse(cleanPathUrl).buildUpon()

        when (filterMode) {
            TopicFilterMode.TODAY_POPULAR -> {
                if (!forceAllEntriesFallback) {
                    if (!existingDayParam.isNullOrBlank()) {
                        uriBuilder.appendQueryParameter("day", existingDayParam)
                    } else {
                        uriBuilder.appendQueryParameter("a", "popular")
                    }
                }
            }
            TopicFilterMode.ALL,
            TopicFilterMode.LINKS_ONLY -> {
                // "Tümü" veya "Tümünü Göster" seçildiğinde `a=` ve `day=` filtre parametreleri eklenmez
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

        if (safePage > 1) {
            uriBuilder.appendQueryParameter("p", safePage.toString())
        }

        return uriBuilder.build().toString()
    }

    /**
     * 3. Başlık sayfasındaki (`#entry-item-list li`) tüm entry'leri, yazar adını, tarihini,
     * düzenleme tarihini (`editDate`), fav sayısını, fav durumunu ve toplam sayfa sayısını
     * (`div.pager` -> `data-pagecount`) ayrıştırır.
     *
     * ÖNEMLİ: Kullanıcı "Bugün" (`?a=popular` veya `?day=YYYY-MM-DD`) ya da Şükela modundayken
     * HTML içindeki parametresiz canonical URL'nin aktif filtreyi ezmesine izin vermez;
     * `activeFilterQueryUrl` içinde aktif filtre sorgusunu korur.
     */
    fun parseTopicPage(
        html: String,
        requestedUrl: String,
        fallbackPage: Int = 1,
        usedTrackedFallback: Boolean = false,
        usedTodayFallback: Boolean = false
    ): TopicPageData {
        val doc: Document = Jsoup.parse(html, BASE_URL)

        val titleContainer = doc.selectFirst("#topic #title, #topic h1#title, #title, h1#title")
        val titleAnchor = doc.selectFirst("#topic #title a[href], #topic h1#title a[href], #title a[href], h1#title a[href]")
        val topicTitle = titleContainer?.attr("data-title")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("#topic #title a span[itemprop=name], #title a span[itemprop=name], #title a, h1#title")
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

        val requestedTopicId = if (!isDirectSingleEntryUrl(requestedUrl)) extractTopicId(requestedUrl) else ""
        val isRedirectedToDifferentTopic = (containsTodayPopularParam(requestedUrl) || containsTrackedParam(requestedUrl)) &&
            (doc.select("div.topic-item").size > 1 ||
                (requestedTopicId.isNotBlank() && topicId.isNotBlank() && requestedTopicId != topicId))

        if (isRedirectedToDifferentTopic) {
            val cleanReq = stripTrackedParameters(requestedUrl)
            return TopicPageData(
                topicId = requestedTopicId.ifBlank { topicId },
                topicTitle = extractReadableTitleFromUrl(requestedUrl),
                topicSlug = extractSlug(requestedUrl),
                cleanTopicUrl = cleanReq,
                activeFilterQueryUrl = cleanReq,
                currentPage = fallbackPage,
                pageCount = 1,
                entries = emptyList(),
                usedTrackedFallback = usedTrackedFallback,
                usedTodayFallback = usedTodayFallback,
                isSingleEntryView = false
            )
        }

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
        val isSingleEntry = isDirectSingleEntryUrl(requestedUrl) ||
            lowerReqUrl.contains("a=debe")
        val requestedSingleEntryId = if (isDirectSingleEntryUrl(requestedUrl)) {
            requestedUrl.substringBefore('?').substringAfter("/entry/", "").filter { it.isDigit() }
        } else {
            ""
        }

        val entries = when {
            requestedSingleEntryId.isNotBlank() -> {
                rawEntries.filter { it.id == requestedSingleEntryId }
                    .ifEmpty { rawEntries.take(1) }
            }
            isSingleEntry -> {
                rawEntries.take(1)
            }
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

        val resolvedTopicTitle = entries.firstOrNull()?.topicTitle?.takeIf { it.isNotBlank() } ?: topicTitle
        val resolvedCanonicalUrl = entries.firstOrNull()?.topicUrl?.takeIf {
            it.isNotBlank() && !isDirectSingleEntryUrl(it)
        } ?: canonicalUrl

        // Aktif filtre sorgu URL'si: Eğer istek `?a=popular` veya `?day=YYYY-MM-DD` (veya `?a=dailynice`, `?a=nice`)
        // ile yapıldıysa ve tümüne fallback yapılmadıysa, kanonik URL üzerinde bu sorgu parametresini koru!
        val activeFilterQueryUrl = buildPreservedFilterUrl(
            cleanCanonicalUrl = resolvedCanonicalUrl,
            requestedUrl = requestedUrl,
            usedTodayFallback = usedTodayFallback
        )

        return TopicPageData(
            topicId = topicId,
            topicTitle = resolvedTopicTitle,
            topicSlug = topicSlug,
            cleanTopicUrl = resolvedCanonicalUrl,
            activeFilterQueryUrl = activeFilterQueryUrl,
            currentPage = if (isSingleEntry) 1 else currentPage,
            pageCount = if (isSingleEntry) 1 else pageCount,
            entries = entries,
            usedTrackedFallback = usedTrackedFallback,
            usedTodayFallback = usedTodayFallback,
            isSingleEntryView = isSingleEntry
        )
    }

    private fun buildPreservedFilterUrl(
        cleanCanonicalUrl: String,
        requestedUrl: String,
        usedTodayFallback: Boolean
    ): String {
        val cleanBase = stripTrackedParameters(cleanCanonicalUrl)
        if (usedTodayFallback || isDirectSingleEntryUrl(cleanBase)) {
            return cleanBase
        }
        return runCatching {
            val reqUri = Uri.parse(normalizeUrl(requestedUrl))
            val dayParam = reqUri.getQueryParameter("day")
            val aParam = reqUri.getQueryParameter("a")
            val keywordsParam = reqUri.getQueryParameter("keywords")
            val builder = Uri.parse(cleanBase).buildUpon()
            when {
                !dayParam.isNullOrBlank() -> builder.appendQueryParameter("day", dayParam)
                aParam.equals("popular", ignoreCase = true) -> builder.appendQueryParameter("a", "popular")
                aParam.equals("dailynice", ignoreCase = true) -> builder.appendQueryParameter("a", "dailynice")
                aParam.equals("nice", ignoreCase = true) -> builder.appendQueryParameter("a", "nice")
                aParam.equals("search", ignoreCase = true) && !keywordsParam.isNullOrBlank() -> {
                    builder.appendQueryParameter("a", "search")
                    builder.appendQueryParameter("keywords", keywordsParam)
                }
            }
            builder.build().toString()
        }.getOrDefault(cleanBase)
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
            .filter { it.isDigit() }
        if (entryId.isBlank()) return null

        val contentElement = li.selectFirst("div.content") ?: return null
        val contentHtml = contentElement.html().trim()
        val annotatedContent = buildEntryAnnotatedString(contentElement)

        val itemTopicScope = li.parents().firstOrNull { it.hasClass("topic-item") || it.id() == "topic" }
        val itemTitleEl = itemTopicScope?.selectFirst("#title, h1#title")
        val itemTitleAnchor = itemTopicScope?.selectFirst("#title a[href], h1#title a[href]")

        val resolvedTopicTitle = itemTitleEl?.attr("data-title")?.trim()?.takeIf { it.isNotBlank() }
            ?: itemTitleEl?.selectFirst("a span[itemprop=name], a")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: topicTitle

        val resolvedTopicUrl = itemTitleAnchor?.attr("href")?.takeIf { it.isNotBlank() }
            ?.let { stripTrackedParameters(it) }
            ?: topicUrl

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
            ?: li.selectFirst("span.favorite-count, .favorite-count")
                ?.text()
                ?.trim()
                ?.let { parseEntryCount(it) }
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
            topicTitle = resolvedTopicTitle,
            topicUrl = resolvedTopicUrl,
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

        val isLight = ActiveThemeState.currentMode == AppThemeMode.CLASSIC_LIGHT
        val bkzColor = if (isLight) Color(0xFF2E7D32) else COLOR_BKZ_GREEN
        val bkzBg = if (isLight) Color(0xFFE8F5E9) else Color(0xFF112415)

        val start = builder.length
        builder.pushStringAnnotation(tag = TAG_HIDDEN_BKZ, annotation = targetQuery)
        builder.pushStyle(
            SpanStyle(
                color = bkzColor,
                background = bkzBg,
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

        val isLight = ActiveThemeState.currentMode == AppThemeMode.CLASSIC_LIGHT
        val bkzColor = if (isLight) Color(0xFF2E7D32) else COLOR_BKZ_GREEN
        val extColor = if (isLight) Color(0xFF1565C0) else COLOR_EXTERNAL_LINK

        if (isInternalBkz) {
            val targetQuery = extractBkzQueryFromAnchor(anchor)
            builder.pushStringAnnotation(tag = TAG_BKZ, annotation = targetQuery)
            builder.pushStyle(
                SpanStyle(
                    color = bkzColor,
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
                    color = extColor,
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
     * 6. ANLIK ARAMA ÖNERİLERİ (AUTOCOMPLETE):
     * `https://eksisozluk.com/autocomplete/query?q={query}` yanıtındaki JSON (`{"Titles":[...], "Nicks":[...]}`)
     * verisini `org.json.JSONObject` ile ayrıştırır.
     * - Eğer yanıt HTML içine sarılı gelirse Jsoup `.text()` ile içindeki JSON nesnesini çıkarır.
     * - Eğer yanıt doğrudan HTML arama listesi ise `parseLeftFrameTopics` ile başlıkları çıkarır.
     */
    fun parseAutocompleteResponse(
        rawResponse: String,
        baseDomain: String = BASE_URL
    ): Pair<List<TopicItem>, List<String>> {
        if (rawResponse.isBlank()) return emptyList<TopicItem>() to emptyList()
        val safeDomain = baseDomain.ifBlank { BASE_URL }.trimEnd('/')

        val trimmed = rawResponse.trim()
        val candidateJsonText = when {
            trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
            trimmed.contains("\"Titles\"") || trimmed.contains("\"Nicks\"") -> {
                val extracted = if (trimmed.startsWith("<")) {
                    Jsoup.parse(trimmed).text().trim()
                } else {
                    trimmed
                }
                val firstBrace = extracted.indexOf('{')
                val lastBrace = extracted.lastIndexOf('}')
                if (firstBrace >= 0 && lastBrace > firstBrace) {
                    extracted.substring(firstBrace, lastBrace + 1)
                } else {
                    ""
                }
            }
            else -> ""
        }

        if (candidateJsonText.isNotBlank()) {
            try {
                val jsonObject = JSONObject(candidateJsonText)
                val titles = mutableListOf<TopicItem>()
                val nicks = mutableListOf<String>()

                val titlesArray = jsonObject.optJSONArray("Titles")
                    ?: jsonObject.optJSONArray("titles")
                if (titlesArray != null) {
                    for (i in 0 until titlesArray.length()) {
                        val rawTitle = titlesArray.optString(i).trim()
                        if (rawTitle.isNotBlank()) {
                            val slug = rawTitle.lowercase().replace(" ", "-")
                            val url = "$safeDomain/?q=${Uri.encode(rawTitle)}"
                            titles.add(
                                TopicItem(
                                    id = "auto_title_${slug}_$i",
                                    title = rawTitle,
                                    slug = slug,
                                    url = url
                                )
                            )
                        }
                    }
                }

                val nicksArray = jsonObject.optJSONArray("Nicks")
                    ?: jsonObject.optJSONArray("nicks")
                if (nicksArray != null) {
                    for (i in 0 until nicksArray.length()) {
                        val rawNick = nicksArray.optString(i).trim().removePrefix("@")
                        if (rawNick.isNotBlank()) {
                            nicks.add(rawNick)
                        }
                    }
                }

                if (titles.isNotEmpty() || nicks.isNotEmpty()) {
                    return titles.distinctBy { it.title.lowercase() } to nicks.distinctBy { it.lowercase() }
                }
            } catch (_: Throwable) {
                // JSON parse hatası olursa HTML fallback'e geç
            }
        }

        val fallbackTopics = parseLeftFrameTopics(html = rawResponse, isTrackedSection = false)
        return fallbackTopics to emptyList()
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
    fun parseEntryCount(rawCount: String): Int {
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

        private val OLD_YEARS_REGEX = Regex("\\b(199\\d|200\\d|201\\d|202[0-5])\\b")

        private val COLOR_BKZ_GREEN = Color(0xFF81C784)
        private val COLOR_HIDDEN_BKZ = Color(0xFFFFB74D)
        private val COLOR_EXTERNAL_LINK = Color(0xFF64B5F6)
    }
}
