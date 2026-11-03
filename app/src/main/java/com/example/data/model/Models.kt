package com.example.data.model

import androidx.compose.ui.text.AnnotatedString

/**
 * Sol frame (Gündem, Bugün, Debe, Olaylar) ve Arama sonuçlarındaki başlık modeli.
 */
data class TopicItem(
    val id: String,
    val title: String,
    val slug: String,
    val url: String,
    val entryCount: Int = 0,
    val entryCountText: String = "",
    val isTracked: Boolean = false
) {
    /**
     * Sorgu parametrelerinden (`?a=popular`, `?a=tracked`, `?p=...`) arındırılmış
     * kanonik başlık URL'si.
     */
    val cleanUrl: String
        get() = url.substringBefore('?')
}

/**
 * Başlık sayfasındaki tekil Entry modeli.
 * - [contentHtml]: Orijinal HTML içerik
 * - [annotatedContent]: `(bkz: ...)`, gizli bkz (`*`) ve harici linkleri işlenmiş Compose [AnnotatedString]
 * - [date]: Oluşturulma tarihi
 * - [editDate]: Varsa düzenlenme tarihi (`~` sonrası kısım), yoksa `null`
 */
data class EntryItem(
    val id: String,
    val contentHtml: String,
    val annotatedContent: AnnotatedString,
    val author: String,
    val date: String,
    val editDate: String?,
    val favoriteCount: Int,
    val isFavorited: Boolean,
    val topicTitle: String,
    val topicUrl: String = "",
    val authorProfileUrl: String = "",
    val avatarUrl: String? = null,
    val pageNumber: Int = 1,
    val hasExternalLink: Boolean = false
) {
    val plainText: String
        get() = annotatedContent.text
}

/**
 * Başlık içi filtreleme modları:
 * - [ALL]: Tüm entry'ler (varsayılan)
 * - [SUKELA_ALL]: Şükela (Tümü) -> `?a=nice`
 * - [SUKELA_TODAY]: Şükela (Bugün) -> `?a=dailynice`
 * - [LINKS_ONLY]: Sadece harici link (`http`/`https`) içeren entry'ler
 * - [SEARCH_IN_TOPIC]: Başlık içinde kelime/yazar araması (`?a=search&keywords=...`)
 */
enum class TopicFilterMode(
    val displayTitle: String,
    val queryParamKey: String?,
    val queryParamValue: String?
) {
    ALL(
        displayTitle = "Tümü",
        queryParamKey = null,
        queryParamValue = null
    ),
    SUKELA_ALL(
        displayTitle = "Şükela (Tümü)",
        queryParamKey = "a",
        queryParamValue = "nice"
    ),
    SUKELA_TODAY(
        displayTitle = "Şükela (Bugün)",
        queryParamKey = "a",
        queryParamValue = "dailynice"
    ),
    LINKS_ONLY(
        displayTitle = "Sadece Linkler",
        queryParamKey = null,
        queryParamValue = null
    ),
    SEARCH_IN_TOPIC(
        displayTitle = "Başlıkta Ara",
        queryParamKey = "a",
        queryParamValue = "search"
    )
}

/**
 * Arama sıralama ölçütleri.
 */
enum class SearchSortOrder(val apiValue: String, val label: String) {
    COUNT("Count", "Entry Sayısı (Çoktan Aza)"),
    DATE("Date", "Son Güncellenen"),
    TOPIC("Topic", "Alfabetik (A-Z)")
}

/**
 * `/basliklar/ara` uç noktası için gelişmiş arama filtresi.
 */
data class SearchFilter(
    val keywords: String,
    val author: String = "",
    val sortOrder: SearchSortOrder = SearchSortOrder.COUNT,
    val niceOnly: Boolean = false
)

/**
 * Bir başlık sayfasının Jsoup ile ayrıştırılmış tam sonucu.
 */
data class TopicPageData(
    val topicId: String,
    val topicTitle: String,
    val topicSlug: String,
    val cleanTopicUrl: String,
    val currentPage: Int,
    val pageCount: Int,
    val entries: List<EntryItem>,
    val usedTrackedFallback: Boolean = false
)

/**
 * Arama sayfasının Jsoup ile ayrıştırılmış tam sonucu.
 */
data class SearchPageData(
    val query: String,
    val currentPage: Int,
    val pageCount: Int,
    val topics: List<TopicItem>
)

/**
 * Sol frame sekmeleri.
 */
enum class LeftFrameSection(val title: String, val endpointPath: String) {
    GUNDEM("Gündem", "/basliklar/gundem"),
    BUGUN("Bugün", "/basliklar/bugun"),
    DEBE("Debe", "/basliklar/debe"),
    OLAYLAR("Olaylar", "/basliklar/olay")
}
