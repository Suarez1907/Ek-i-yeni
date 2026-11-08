package com.example.data.model

import androidx.compose.ui.text.AnnotatedString

/**
 * Sol frame (Gündem, Bugün, Debe, Olaylar, Kanallar) ve Arama sonuçlarındaki başlık modeli.
 */
data class TopicItem(
    val id: String,
    val title: String,
    val slug: String,
    val url: String,
    val entryCount: Int = 0,
    val entryCountText: String = "",
    val favoriteCount: Int = 0,
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
 * - [TODAY_POPULAR]: Sadece Bugün (son 24 saatte yazılan entry'ler -> `?a=popular` veya `?day=YYYY-MM-DD`, boşsa tümüne fallback)
 * - [ALL]: Tüm entry'ler (1. sayfadan itibaren tüm başlık -> `?p=1`)
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
    TODAY_POPULAR(
        displayTitle = "Bugün",
        queryParamKey = "a",
        queryParamValue = "popular"
    ),
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
 * Ekşin tarzı Kanal Başlıkları (`https://eksisozluk.com/basliklar/kanal/{kanal_slug}?p={page}`).
 */
enum class EksiChannel(
    val hashtag: String,
    val slug: String,
    val rawSlug: String,
    val displayName: String
) {
    SPOR("#spor", "spor", "spor", "Spor"),
    TEKNOLOJI("#teknoloji", "teknoloji", "teknoloji", "Teknoloji"),
    SIYASET("#siyaset", "siyaset", "siyaset", "Siyaset"),
    EKONOMI("#ekonomi", "ekonomi", "ekonomi", "Ekonomi"),
    SINEMA("#sinema", "sinema", "sinema", "Sinema"),
    OYUN("#oyun", "oyun", "oyun", "Oyun"),
    BILIM("#bilim", "bilim", "bilim", "Bilim"),
    MUZIK("#müzik", "muzik", "müzik", "Müzik"),
    EDEBIYAT("#edebiyat", "edebiyat", "edebiyat", "Edebiyat"),
    TARIH("#tarih", "tarih", "tarih", "Tarih"),
    OTOMOTIV("#otomotiv", "otomotiv", "otomotiv", "Otomotiv"),
    HAVACILIK("#havacılık", "havacilik", "havacılık", "Havacılık"),
    ILISKILER("#ilişkiler", "iliskiler", "ilişkiler", "İlişkiler"),
    SAGLIK("#sağlık", "saglik", "sağlık", "Sağlık")
}

/**
 * Gelişmiş DEBE ve Dönemsel Enler ana kategorisi:
 * - [DEBE]: Dünün En Beğenilen 50 Entry'si (`/debe`)
 * - [PERIODIC_BESTS]: Dönemsel Enler (Önceki Gün / Önceki Hafta / Önceki Ay)
 */
enum class DebeAndStatsCategory(val title: String) {
    DEBE("Dünün En Beğenilenleri (DEBE)"),
    PERIODIC_BESTS("Dönemsel Enler")
}

/**
 * Dönemsel Enler için zaman aralığı seçici çipler:
 * - [YESTERDAY]: Önceki Gün
 * - [LAST_WEEK]: Önceki Hafta
 * - [LAST_MONTH]: Önceki Ay
 */
enum class PeriodicTimeRange(
    val label: String,
    val entriesStatsPath: String,
    val topicsStatsPath: String,
    val fallbackPage: Int
) {
    YESTERDAY(
        label = "Önceki Gün",
        entriesStatsPath = "/istatistik/dunun-en-begenilen-entryleri",
        topicsStatsPath = "/istatistik/dunun-en-populer-basliklari",
        fallbackPage = 1
    ),
    LAST_WEEK(
        label = "Önceki Hafta",
        entriesStatsPath = "/istatistik/gecen-haftanin-en-begenilen-entryleri",
        topicsStatsPath = "/istatistik/gecen-haftanin-en-populer-basliklari",
        fallbackPage = 2
    ),
    LAST_MONTH(
        label = "Önceki Ay",
        entriesStatsPath = "/istatistik/gecen-ayin-en-begenilen-entryleri",
        topicsStatsPath = "/istatistik/gecen-ayin-en-populer-basliklari",
        fallbackPage = 3
    )
}

/**
 * Dönemsel Enler alt sekmesi:
 * - [TOP_ENTRIES]: En Beğenilen Entry'ler
 * - [TOP_TOPICS]: En Popüler Konular (Başlıklar)
 */
enum class PeriodicRankingTab(val title: String) {
    TOP_ENTRIES("En Beğenilen Entry'ler"),
    TOP_TOPICS("En Popüler Konular")
}

/**
 * DEBE veya Dönemsel Enler listesinde gösterilen sıralı entry/başlık öğesi.
 */
data class RankedTopicOrEntryItem(
    val rank: Int,
    val id: String,
    val title: String,
    val url: String,
    val badgeText: String,
    val favoriteCount: Int = 0,
    val author: String? = null,
    val entryId: String? = null,
    val snippet: String? = null,
    val isDirectEntry: Boolean = false
) {
    fun toTopicItem(): TopicItem {
        val cleanSlug = title.lowercase().replace(" ", "-")
        val formattedBadge = if (favoriteCount > 0) {
            if (isDirectEntry || !entryId.isNullOrBlank()) "♥ $favoriteCount" else "$favoriteCount entry"
        } else {
            badgeText
        }
        val validEntryId = entryId?.trim()?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
        val resolvedUrl = when {
            validEntryId != null -> "https://eksisozluk.com/entry/$validEntryId"
            isDirectEntry -> url
            else -> url.substringBefore('?')
        }
        return TopicItem(
            id = validEntryId ?: id,
            title = title,
            slug = cleanSlug,
            url = resolvedUrl,
            entryCount = favoriteCount,
            entryCountText = formattedBadge,
            favoriteCount = favoriteCount
        )
    }
}

sealed interface DebeAndStatsUiState {
    data object Loading : DebeAndStatsUiState
    data class Success(
        val items: List<RankedTopicOrEntryItem>,
        val subtitle: String
    ) : DebeAndStatsUiState
    data class Error(val message: String) : DebeAndStatsUiState
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
 * Arama kutusuna yazmaya başlandığında (en az 2 karakter) anlık gösterilen
 * canlı başlık (`Titles`) ve yazar (`@Nicks`) önerileri durumu.
 */
data class AutocompleteSuggestionState(
    val query: String = "",
    val isLoading: Boolean = false,
    val titleSuggestions: List<TopicItem> = emptyList(),
    val nickSuggestions: List<String> = emptyList()
) {
    val hasAnySuggestion: Boolean
        get() = titleSuggestions.isNotEmpty() || nickSuggestions.isNotEmpty()
}

/**
 * Bir başlık sayfasının Jsoup ile ayrıştırılmış tam sonucu.
 * - [cleanTopicUrl]: Sorgu parametrelerinden arındırılmış kanonik başlık URL'si (`https://eksisozluk.com/baslik--123`)
 * - [activeFilterQueryUrl]: Aktif filtre parametresini (`?a=popular`, `?day=YYYY-MM-DD`, `?a=dailynice`, `?a=nice`)
 *   koruyan URL; böylece 2..N sayfalarına geçildiğinde aktif filtre asla kaybolmaz.
 */
data class TopicPageData(
    val topicId: String,
    val topicTitle: String,
    val topicSlug: String,
    val cleanTopicUrl: String,
    val activeFilterQueryUrl: String = cleanTopicUrl,
    val currentPage: Int,
    val pageCount: Int,
    val entries: List<EntryItem>,
    val usedTrackedFallback: Boolean = false,
    val usedTodayFallback: Boolean = false,
    val isSingleEntryView: Boolean = false,
    val isFromWaybackArchive: Boolean = false
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
