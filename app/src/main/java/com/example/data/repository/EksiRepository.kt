package com.example.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.data.local.BookmarkedEntryEntity
import com.example.data.local.EksinDao
import com.example.data.local.ReadStateDao
import com.example.data.local.TopicReadStateEntity
import com.example.data.model.EksiChannel
import com.example.data.model.EntryItem
import com.example.data.model.LeftFrameSection
import com.example.data.model.PeriodicRankingTab
import com.example.data.model.PeriodicTimeRange
import com.example.data.model.RankedTopicOrEntryItem
import com.example.data.model.SearchFilter
import com.example.data.model.TopicFilterMode
import com.example.data.model.TopicItem
import com.example.data.model.TopicPageData
import com.example.data.remote.EksiParser
import com.example.network.WebViewCookieJar
import com.example.network.WebViewFetchBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ekşi+ veri katmanı:
 * - Tüm HTML veri çekme işlemlerini [WebViewFetchBridge] üzerinden gerçekleştirir.
 * - Sol Frame (Gündem, Bugün, Debe, Olaylar), Kanallar (`/basliklar/kanal/{kanal_slug}?p={page}`),
 *   DEBE 50 (`/debe`) ve Dönemsel Enler (`/istatistik/...`) rotalarını ayrıştırır.
 * - DEBE ve Dönemsel Enler öğelerinin beğeni (`data-favorite-count` / `.favorite-count`) sayılarını
 *   hafızada (`entryFavoriteCountCache`) tutar ve paralel `Semaphore(4)` ile arka planda tamamlar.
 * - Başlık detay ekranında Paging 3'ün `prevKey` sonsuz geri sarma döngüsünü önlemek için
 *   doğrudan tekil sayfa çekme ([fetchSingleTopicPage]) ve `X + 1` arka plan önbellekleme mimarisini destekler.
 * - Başlığa ilk girildiğinde kalınan yeri tek seferlik ([getSavedTopicReadState]) okur ve
 *   kaydırma sırasında [ReadStateDao] üzerinden Room veritabanına kaydeder.
 */
@Singleton
class EksiRepository @Inject constructor(
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser,
    private val readStateDao: ReadStateDao,
    private val eksinDao: EksinDao
) {

    /**
     * DEBE ve Dönemsel Enler entry'lerinin beğeni (`data-favorite-count`) ve yazar bilgilerini
     * tekrar tekrar ağdan çekmemek için tutulan bellek içi (in-memory) önbellekler.
     */
    private val entryFavoriteCountCache = ConcurrentHashMap<String, Int>()
    private val entryAuthorCache = ConcurrentHashMap<String, String>()
    private val entryResolvedIdCache = ConcurrentHashMap<String, String>()
    private val entryTopicTitleCache = ConcurrentHashMap<String, String>()

    fun observeBookmarks(): Flow<List<BookmarkedEntryEntity>> =
        eksinDao.observeBookmarkedEntries()

    /**
     * Profil ekranındaki "Son Okunan Başlıklar" listesi için tüm okuma geçmişini gözlemler.
     */
    fun observeAllReadStates(): Flow<List<TopicReadStateEntity>> =
        readStateDao.observeAllReadStates()

    /**
     * Başlığa İLK girildiğinde tek seferlik (`suspend` / `firstOrNull` mantığıyla)
     * kalınan sayfa (`pageNumber`) ve entry (`entryId`, `scrollOffset`) bilgisini getirir.
     * Sürekli bir Flow olarak dinlenmediği için kullanıcının manuel sayfa geçişlerini asla ezmez.
     */
    suspend fun getSavedTopicReadState(
        rawOrCleanTopicUrl: String,
        topicId: String = ""
    ): TopicReadStateEntity? = withContext(Dispatchers.IO) {
        val canonicalKey = normalizeTopicKey(rawOrCleanTopicUrl)
        val byKey = readStateDao.getReadStateByKey(canonicalKey)
        if (byKey != null) return@withContext byKey

        if (topicId.isNotBlank()) {
            return@withContext readStateDao.getReadStateByTopicId(topicId)
        }
        null
    }

    /**
     * Kullanıcı başlık içinde kaydırma yaptıkça veya sayfa değiştirdikçe en son okunan
     * `entryId`, `pageNumber`, `scrollIndex` ve `scrollOffset` değerlerini Room veritabanına kaydeder.
     */
    suspend fun saveTopicReadState(
        topicUrl: String,
        topicId: String = "",
        topicTitle: String,
        topicSlug: String = "",
        entryId: String,
        pageNumber: Int,
        scrollIndex: Int,
        scrollOffset: Int,
        totalKnownPages: Int = 1
    ) = withContext(Dispatchers.IO) {
        if (entryId.isBlank()) return@withContext
        val canonicalKey = normalizeTopicKey(topicUrl)
        val entity = TopicReadStateEntity(
            topicKey = canonicalKey,
            topicId = topicId,
            topicTitle = topicTitle,
            topicSlug = topicSlug,
            topicUrl = canonicalKey,
            entryId = entryId,
            pageNumber = pageNumber.coerceAtLeast(1),
            scrollIndex = scrollIndex.coerceAtLeast(0),
            scrollOffset = scrollOffset.coerceAtLeast(0),
            totalKnownPages = totalKnownPages.coerceAtLeast(pageNumber.coerceAtLeast(1)),
            updatedAtMs = System.currentTimeMillis()
        )
        readStateDao.upsertReadState(entity)
    }

    suspend fun deleteTopicReadState(topicKey: String) = withContext(Dispatchers.IO) {
        val canonicalKey = normalizeTopicKey(topicKey)
        readStateDao.deleteReadState(canonicalKey)
    }

    suspend fun clearAllReadStates() = withContext(Dispatchers.IO) {
        readStateDao.clearAllReadStates()
    }

    fun normalizeTopicKey(rawUrl: String): String {
        return eksiParser.stripTrackedParameters(rawUrl)
    }

    suspend fun toggleBookmark(entry: EntryItem) {
        eksinDao.upsertBookmarkedEntry(
            BookmarkedEntryEntity(
                entryId = entry.id,
                topicTitle = entry.topicTitle,
                topicUrl = entry.topicUrl,
                contentHtml = entry.contentHtml,
                contentText = entry.plainText,
                author = entry.author,
                dateText = if (entry.editDate != null) {
                    "${entry.date} ~ ${entry.editDate}"
                } else {
                    entry.date
                },
                favoriteCount = entry.favoriteCount
            )
        )
    }

    suspend fun removeBookmark(entryId: String) {
        eksinDao.deleteBookmarkedEntry(entryId)
    }

    /**
     * Sol frame başlıklarını (Gündem, Bugün, Debe, Olaylar) [WebViewFetchBridge] ve Paging 3 ile çeker.
     */
    fun getLeftFrameTopicsPager(section: LeftFrameSection): Flow<PagingData<TopicItem>> {
        return Pager(
            config = PagingConfig(
                pageSize = 25,
                prefetchDistance = 20,
                initialLoadSize = 25,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                LeftFramePagingSource(
                    section = section,
                    webViewFetchBridge = webViewFetchBridge,
                    eksiParser = eksiParser
                )
            }
        ).flow
    }

    /**
     * 1. KANALLAR SEKMESİ:
     * Seçilen kanalın (`#spor`, `#teknoloji`, `#siyaset` vb.) güncel başlıklarını
     * `https://eksisozluk.com/basliklar/kanal/{kanal_slug}?p={page}` rotasından Paging 3 ile çeker.
     */
    fun getChannelTopicsPager(channel: EksiChannel): Flow<PagingData<TopicItem>> {
        return Pager(
            config = PagingConfig(
                pageSize = 25,
                prefetchDistance = 20,
                initialLoadSize = 25,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                ChannelTopicsPagingSource(
                    channel = channel,
                    webViewFetchBridge = webViewFetchBridge,
                    eksiParser = eksiParser
                )
            }
        ).flow
    }

    private fun buildFavoriteCacheKey(item: RankedTopicOrEntryItem): String {
        val entryId = item.entryId?.trim()?.filter { it.isDigit() }.orEmpty()
        if (entryId.isNotBlank()) return "entry:$entryId"
        return "url:${item.url.trim()}"
    }

    fun applyCachedFavoriteMetadata(item: RankedTopicOrEntryItem): RankedTopicOrEntryItem {
        val key = buildFavoriteCacheKey(item)
        val cachedFav = entryFavoriteCountCache[key]
        val cachedAuthor = entryAuthorCache[key]
        val cachedEntryId = entryResolvedIdCache[key]
        val cachedTitle = entryTopicTitleCache[key]

        val resolvedEntryId = (item.entryId ?: cachedEntryId)
            ?.trim()
            ?.filter { it.isDigit() }
            ?.takeIf { it.isNotBlank() }

        val resolvedTitle = cachedTitle?.takeIf { it.isNotBlank() } ?: item.title
        val resolvedUrl = if (resolvedEntryId != null) {
            "https://eksisozluk.com/entry/$resolvedEntryId"
        } else {
            item.url
        }

        if (item.favoriteCount > 0) {
            entryFavoriteCountCache[key] = item.favoriteCount
            if (!item.author.isNullOrBlank()) {
                entryAuthorCache[key] = item.author
            }
            if (resolvedEntryId != null) {
                entryResolvedIdCache[key] = resolvedEntryId
                entryFavoriteCountCache["entry:$resolvedEntryId"] = item.favoriteCount
            }
            if (resolvedTitle.isNotBlank()) {
                entryTopicTitleCache[key] = resolvedTitle
            }
            return item.copy(
                id = resolvedEntryId ?: item.id,
                title = resolvedTitle,
                url = resolvedUrl,
                badgeText = "♥ ${item.favoriteCount}",
                entryId = resolvedEntryId ?: item.entryId,
                isDirectEntry = item.isDirectEntry || resolvedEntryId != null
            )
        }

        if (cachedFav != null) {
            return item.copy(
                id = resolvedEntryId ?: item.id,
                title = resolvedTitle,
                url = resolvedUrl,
                favoriteCount = cachedFav,
                badgeText = "♥ $cachedFav",
                author = item.author ?: cachedAuthor,
                entryId = resolvedEntryId ?: item.entryId,
                isDirectEntry = item.isDirectEntry || resolvedEntryId != null
            )
        }
        return item
    }

    /**
     * Tekil bir DEBE veya Dönemsel Enler öğesinin detay sayfasından:
     * - `data-favorite-count` (veya `.favorite-count`) beğeni sayısını,
     * - `entryId` gerçek kimliğini,
     * - `#topic h1#title` / `data-title` gerçek başlık adını
     * çeker, hafızaya (`entryFavoriteCountCache`, `entryTopicTitleCache`) alır ve güncellenmiş öğeyi döndürür.
     */
    suspend fun fetchAndCacheEntryFavoriteCount(
        item: RankedTopicOrEntryItem
    ): RankedTopicOrEntryItem = withContext(Dispatchers.IO) {
        val alreadyCached = applyCachedFavoriteMetadata(item)
        if (alreadyCached.favoriteCount > 0 && !alreadyCached.entryId.isNullOrBlank()) {
            return@withContext alreadyCached
        }

        val key = buildFavoriteCacheKey(item)
        val baseDomain = webViewFetchBridge.getActiveBaseDomain().ifBlank {
            WebViewCookieJar.DEFAULT_BASE_URL
        }.trimEnd('/')

        try {
            val targetFetchUrl = if (!item.entryId.isNullOrBlank()) {
                "$baseDomain/entry/${item.entryId.trim().filter { it.isDigit() }}"
            } else {
                item.url
            }
            val html = webViewFetchBridge.fetchHtml(
                rawUrl = targetFetchUrl,
                isAjax = false
            )
            val metadata = withContext(Dispatchers.Default) {
                eksiParser.parseEntryDetailMetadata(
                    html = html,
                    targetEntryId = item.entryId
                )
            }
            if (metadata != null) {
                val favCount = metadata.favoriteCount.coerceAtLeast(0)
                val resolvedEntryId = metadata.entryId
                    .trim()
                    .filter { it.isDigit() }
                    .ifBlank { item.entryId?.trim()?.filter { it.isDigit() }.orEmpty() }

                val resolvedTitle = metadata.topicTitle?.trim()?.takeIf { it.isNotBlank() } ?: item.title
                val resolvedUrl = if (resolvedEntryId.isNotBlank()) {
                    "$baseDomain/entry/$resolvedEntryId"
                } else {
                    item.url
                }

                entryFavoriteCountCache[key] = favCount
                if (!metadata.author.isNullOrBlank()) {
                    entryAuthorCache[key] = metadata.author
                }
                if (resolvedTitle.isNotBlank()) {
                    entryTopicTitleCache[key] = resolvedTitle
                }
                if (resolvedEntryId.isNotBlank()) {
                    val entryKey = "entry:$resolvedEntryId"
                    entryResolvedIdCache[key] = resolvedEntryId
                    entryResolvedIdCache[entryKey] = resolvedEntryId
                    entryFavoriteCountCache[entryKey] = favCount
                    if (!metadata.author.isNullOrBlank()) {
                        entryAuthorCache[entryKey] = metadata.author
                    }
                    if (resolvedTitle.isNotBlank()) {
                        entryTopicTitleCache[entryKey] = resolvedTitle
                    }
                }
                return@withContext item.copy(
                    id = resolvedEntryId.ifBlank { item.id },
                    title = resolvedTitle,
                    url = resolvedUrl,
                    favoriteCount = favCount,
                    badgeText = "♥ $favCount",
                    author = item.author ?: metadata.author,
                    entryId = resolvedEntryId.takeIf { it.isNotBlank() },
                    isDirectEntry = true
                )
            }
        } catch (_: Throwable) {
            // Ağ hatası durumunda mevcut öğeyi koru
        }
        item
    }

    /**
     * 3a. GELİŞMİŞ DEBE:
     * Sadece `https://eksisozluk.com/debe` (ve `/basliklar/debe`) gerçek listesini çeker.
     * Varsa hafızadaki (`entryFavoriteCountCache`) beğeni sayılarını ve gerçek başlık adlarını anında uygular.
     */
    suspend fun fetchDebeItems(): Result<List<RankedTopicOrEntryItem>> = withContext(Dispatchers.IO) {
        try {
            val baseDomain = webViewFetchBridge.getActiveBaseDomain().ifBlank {
                WebViewCookieJar.DEFAULT_BASE_URL
            }.trimEnd('/')

            val primaryUrl = "$baseDomain/debe?_=${System.currentTimeMillis()}"
            val primaryHtml = webViewFetchBridge.fetchHtml(rawUrl = primaryUrl, isAjax = true)
            val parsedPrimary = withContext(Dispatchers.Default) {
                eksiParser.parseDebeEntriesList(primaryHtml)
            }
            if (parsedPrimary.isNotEmpty()) {
                return@withContext Result.success(
                    parsedPrimary.map { applyCachedFavoriteMetadata(it) }
                )
            }

            val fallbackUrl = "$baseDomain/basliklar/debe?_=${System.currentTimeMillis()}"
            val fallbackHtml = webViewFetchBridge.fetchHtml(rawUrl = fallbackUrl, isAjax = true)
            val parsedFallback = withContext(Dispatchers.Default) {
                eksiParser.parseDebeEntriesList(fallbackHtml)
            }
            if (parsedFallback.isNotEmpty()) {
                return@withContext Result.success(
                    parsedFallback.map { applyCachedFavoriteMetadata(it) }
                )
            }

            val fullPageHtml = webViewFetchBridge.fetchHtml(rawUrl = "$baseDomain/debe", isAjax = false)
            val parsedFull = withContext(Dispatchers.Default) {
                eksiParser.parseDebeEntriesList(fullPageHtml)
            }
            Result.success(parsedFull.map { applyCachedFavoriteMetadata(it) })
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 3b. DÖNEMSEL ENLER (Önceki Gün / Önceki Hafta / Önceki Ay):
     * - **Önceki Gün -> En Beğenilen Entry'ler**: `/debe` listesini ve gündem başlıklarının son 24 saatlik
     *   şükela (`?a=dailynice`) filtresini kullanır.
     * - **Önceki Hafta / Ay -> En Beğenilen Entry'ler**: Önce `/istatistik/gecen-haftanin-en-begenilen-entryleri`
     *   ve `/istatistik/gecen-ayin-en-begenilen-entryleri` sayfalarındaki gerçek `<a href="/entry/...">`
     *   linklerini ve karşısındaki başlık adlarını birebir eşleştirerek çeker.
     *   Eğer istatistik sayfası boş döner ve gündem/kanal başlıklarından derleme yapılırsa:
     *   1) ASLA `?a=nice` (tüm zamanlar şükelası) kullanmaz (`?a=dailynice` / `?a=popular` kullanır).
     *   2) Her başlıktan SADECE EN ÇOK FAVORİ ALAN 1 TEK ENTRY alır (aynı başlık asla tekrar etmez).
     *   3) Sadece `.entry-date` tarihi 2026 yılına ait güncel entry'leri kabul eder; 2010-2025 arası eski
     *      entry'leri kesinlikle listeye almaz.
     * - **En Popüler Konular**: Her bir öğenin kendi gerçek başlık URL'si (`topic.cleanUrl`) doğrudan
     *   bağlanır (`isDirectEntry = false`, `entryId = null`).
     */
    suspend fun fetchPeriodicBestItems(
        period: PeriodicTimeRange,
        rankingTab: PeriodicRankingTab
    ): Result<List<RankedTopicOrEntryItem>> = withContext(Dispatchers.IO) {
        try {
            val baseDomain = webViewFetchBridge.getActiveBaseDomain().ifBlank {
                WebViewCookieJar.DEFAULT_BASE_URL
            }.trimEnd('/')

            val isEntriesMode = (rankingTab == PeriodicRankingTab.TOP_ENTRIES)

            // Önceki Gün -> En Beğenilen Entry'ler için doğrudan DEBE + son 24 saat (?a=dailynice) derlemesini kullan
            if (isEntriesMode && period == PeriodicTimeRange.YESTERDAY) {
                val yesterdayEntries = compileTopLikedEntriesFallback(
                    baseDomain = baseDomain,
                    period = period
                )
                return@withContext Result.success(yesterdayEntries)
            }

            val statsPath = if (isEntriesMode) period.entriesStatsPath else period.topicsStatsPath
            val statsUrl = "$baseDomain$statsPath"

            // a) Tam sayfa HTML (isAjax = false) olarak istatistik sayfasını çek
            val statsHtml = webViewFetchBridge.fetchHtml(rawUrl = statsUrl, isAjax = false)
            val parsedStats = withContext(Dispatchers.Default) {
                eksiParser.parsePeriodicStatsList(
                    html = statsHtml,
                    isEntriesMode = isEntriesMode,
                    period = period
                )
            }
            if (parsedStats.isNotEmpty()) {
                val enriched = if (isEntriesMode) {
                    parsedStats
                        .map { applyCachedFavoriteMetadata(it) }
                        .distinctBy { it.entryId ?: it.url }
                        .distinctBy { it.title.trim().lowercase() }
                } else {
                    parsedStats
                        .distinctBy { it.url }
                        .distinctBy { it.title.trim().lowercase() }
                }
                return@withContext Result.success(enriched)
            }

            // b) İstatistik sayfası boş döndüyse:
            if (isEntriesMode) {
                val compiledTopEntries = compileTopLikedEntriesFallback(
                    baseDomain = baseDomain,
                    period = period
                )
                return@withContext Result.success(compiledTopEntries)
            } else {
                val compiledTopTopics = compileTopPopularTopicsFallback(
                    baseDomain = baseDomain,
                    period = period
                )
                return@withContext Result.success(compiledTopTopics)
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * İstatistik sayfası boş döndüğünde veya "Önceki Gün -> En Beğenilen Entry'ler" seçildiğinde:
     * 1. ASLA `?a=nice` (Tüm zamanların şükelası) KULLANMAZ (`?a=dailynice` veya `?a=popular` kullanır).
     * 2. Her başlıktan SADECE EN ÇOK FAVORİ ALAN 1 TEK ENTRY (`maxByOrNull { it.favoriteCount }`) alır;
     *    böylece aynı başlık alt alta tekrar etmez.
     * 3. Sadece `.entry-date` tarihi içinde bulunduğumuz yıla (`2026`) ait olan güncel entry'leri kabul eder;
     *    eski yıllara (`2010-2025`) ait entry'leri kesinlikle listeye almaz.
     * 4. Her kartın `title` alanı doğrudan o `entryId`'nin ait olduğu gerçek başlık (`#topic h1#title`) olur
     *    ve `url` doğrudan `/entry/{gerçek_entry_id}` olarak bağlanır.
     */
    private suspend fun compileTopLikedEntriesFallback(
        baseDomain: String,
        period: PeriodicTimeRange
    ): List<RankedTopicOrEntryItem> = coroutineScope {
        val semaphore = Semaphore(4)
        val collectedByEntryId = ConcurrentHashMap<String, RankedTopicOrEntryItem>()

        // 1. DEBE (`/debe`) gerçek listesini çek
        val debeDeferred = async(Dispatchers.IO) {
            runCatching {
                val debeHtml = webViewFetchBridge.fetchHtml(
                    rawUrl = "$baseDomain/debe?_=${System.currentTimeMillis()}",
                    isAjax = true
                )
                eksiParser.parseDebeEntriesList(debeHtml).map { applyCachedFavoriteMetadata(it) }
            }.getOrDefault(emptyList())
        }

        // 2. Döneme göre gündem ve kanal başlıklarını çek
        val gundemPagesToFetch = when (period) {
            PeriodicTimeRange.YESTERDAY -> listOf(1)
            PeriodicTimeRange.LAST_WEEK -> listOf(1, 2)
            PeriodicTimeRange.LAST_MONTH -> listOf(1, 2, 3)
        }

        val channelsToFetch = when (period) {
            PeriodicTimeRange.YESTERDAY -> emptyList()
            PeriodicTimeRange.LAST_WEEK -> listOf(
                EksiChannel.SIYASET,
                EksiChannel.EKONOMI,
                EksiChannel.SPOR,
                EksiChannel.TEKNOLOJI
            )
            PeriodicTimeRange.LAST_MONTH -> listOf(
                EksiChannel.SINEMA,
                EksiChannel.ILISKILER,
                EksiChannel.BILIM,
                EksiChannel.SAGLIK,
                EksiChannel.TARIH,
                EksiChannel.EDEBIYAT
            )
        }

        val gundemTopicsDeferred = gundemPagesToFetch.map { pageNum ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    runCatching {
                        val url = "$baseDomain/basliklar/gundem?p=$pageNum&_=${System.currentTimeMillis()}"
                        val html = webViewFetchBridge.fetchHtml(rawUrl = url, isAjax = true)
                        eksiParser.parseLeftFrameTopics(html = html, isTrackedSection = false)
                    }.getOrDefault(emptyList())
                }
            }
        }

        val channelTopicsDeferred = channelsToFetch.map { channel ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    runCatching {
                        val url = eksiParser.buildChannelTopicsUrl(
                            baseDomain = baseDomain,
                            channel = channel,
                            page = 1
                        )
                        val html = webViewFetchBridge.fetchHtml(rawUrl = url, isAjax = true)
                        eksiParser.parseChannelTopics(html)
                    }.getOrDefault(emptyList())
                }
            }
        }

        val allCandidateTopics = (gundemTopicsDeferred.awaitAll().flatten() + channelTopicsDeferred.awaitAll().flatten())
            .filter { it.cleanUrl.isNotBlank() && !eksiParser.isDirectSingleEntryUrl(it.cleanUrl) }
            .distinctBy { it.cleanUrl }
            .distinctBy { it.title.trim().lowercase() }
            .sortedByDescending { it.entryCount }
            .take(if (period == PeriodicTimeRange.YESTERDAY) 8 else 14)

        // 3. Seçilen her başlık için ASLA `?a=nice` kullanmadan, sadece `?a=dailynice` (veya `?a=popular`)
        // ile 2026 yılına ait EN ÇOK FAVORİ ALAN 1 TEK ENTRY'yi al!
        val singleBestEntryPerTopic = allCandidateTopics.map { topic ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    runCatching {
                        val cleanUrl = topic.cleanUrl
                        val dailyNiceUrl = "$cleanUrl?a=dailynice"
                        val dailyHtml = webViewFetchBridge.fetchHtml(rawUrl = dailyNiceUrl, isAjax = false)
                        val parsedDailyPage = eksiParser.parseTopicPage(
                            html = dailyHtml,
                            requestedUrl = dailyNiceUrl,
                            fallbackPage = 1
                        )

                        var recent2026Entries = parsedDailyPage.entries.filter { entry ->
                            entry.id.isNotBlank() &&
                                entry.id.all { it.isDigit() } &&
                                entry.favoriteCount > 0 &&
                                eksiParser.isRecent2026EntryDate(entry.date)
                        }
                        var resolvedPageTitle = parsedDailyPage.topicTitle

                        // Eğer `?a=dailynice` boş döndüyse sadece güncel `?a=popular` sayfasına bak (ASLA `?a=nice` kullanma!)
                        if (recent2026Entries.isEmpty()) {
                            val popularUrl = "$cleanUrl?a=popular"
                            val popularHtml = webViewFetchBridge.fetchHtml(rawUrl = popularUrl, isAjax = false)
                            val parsedPopularPage = eksiParser.parseTopicPage(
                                html = popularHtml,
                                requestedUrl = popularUrl,
                                fallbackPage = 1
                            )
                            resolvedPageTitle = parsedPopularPage.topicTitle
                            recent2026Entries = parsedPopularPage.entries.filter { entry ->
                                entry.id.isNotBlank() &&
                                    entry.id.all { it.isDigit() } &&
                                    entry.favoriteCount > 0 &&
                                    eksiParser.isRecent2026EntryDate(entry.date)
                            }
                        }

                        // Her başlıktan SADECE EN ÇOK FAVORİ ALAN 1 TEK ENTRY seç!
                        val bestEntry = recent2026Entries.maxByOrNull { it.favoriteCount }
                        if (bestEntry != null) {
                            val actualTitle = bestEntry.topicTitle
                                .takeIf { it.isNotBlank() }
                                ?: resolvedPageTitle.takeIf { it.isNotBlank() }
                                ?: topic.title
                            bestEntry.copy(topicTitle = actualTitle)
                        } else {
                            null
                        }
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()

        for (entry in singleBestEntryPerTopic) {
            val fav = entry.favoriteCount
            val entryUrl = "$baseDomain/entry/${entry.id}"
            val item = RankedTopicOrEntryItem(
                rank = 1,
                id = entry.id,
                title = entry.topicTitle,
                url = entryUrl,
                badgeText = "♥ $fav",
                favoriteCount = fav,
                author = entry.author,
                entryId = entry.id,
                snippet = entry.plainText.take(140),
                isDirectEntry = true
            )
            val cacheKey = "entry:${entry.id}"
            entryFavoriteCountCache[cacheKey] = fav
            entryAuthorCache[cacheKey] = entry.author
            entryResolvedIdCache[cacheKey] = entry.id
            entryTopicTitleCache[cacheKey] = entry.topicTitle
            collectedByEntryId[entry.id] = item
        }

        // 4. DEBE öğelerini de gerçek entryId ve gerçek başlık adlarıyla (`fetchAndCacheEntryFavoriteCount`) zenginleştirerek ekle
        val debeList = debeDeferred.await()
        val debeSubset = when (period) {
            PeriodicTimeRange.YESTERDAY -> debeList.take(35)
            PeriodicTimeRange.LAST_WEEK -> debeList.take(20)
            PeriodicTimeRange.LAST_MONTH -> debeList.drop(5).take(20)
        }

        val enrichedDebeItems = debeSubset.map { debeItem ->
            async(Dispatchers.IO) {
                val withCache = applyCachedFavoriteMetadata(debeItem)
                if (withCache.favoriteCount > 0 && !withCache.entryId.isNullOrBlank()) {
                    withCache
                } else {
                    semaphore.withPermit {
                        fetchAndCacheEntryFavoriteCount(withCache)
                    }
                }
            }
        }.awaitAll()

        for (debeItem in enrichedDebeItems) {
            val validEntryId = debeItem.entryId?.trim()?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
            val uniqueKey = validEntryId ?: debeItem.url
            val existing = collectedByEntryId[uniqueKey]
            if (existing == null || debeItem.favoriteCount > existing.favoriteCount) {
                collectedByEntryId[uniqueKey] = debeItem.copy(
                    id = validEntryId ?: debeItem.id,
                    url = if (validEntryId != null) "$baseDomain/entry/$validEntryId" else debeItem.url,
                    entryId = validEntryId,
                    badgeText = if (debeItem.favoriteCount > 0) "♥ ${debeItem.favoriteCount}" else debeItem.badgeText,
                    isDirectEntry = true
                )
            }
        }

        // 5. Tüm derlenen entry'leri beğeni (favori) sayısına göre çoktan aza sırala ve
        // aynı başlığın birden fazla kez tekrar etmesini (`distinctBy { it.title.trim().lowercase() }`) kesin olarak engelle!
        collectedByEntryId.values
            .sortedWith(
                compareByDescending<RankedTopicOrEntryItem> { it.favoriteCount }
                    .thenBy { it.rank }
            )
            .distinctBy { it.entryId ?: it.url }
            .distinctBy { it.title.trim().lowercase() }
            .take(50)
            .mapIndexed { index, item ->
                val fav = item.favoriteCount
                item.copy(
                    rank = index + 1,
                    badgeText = if (fav > 0) "♥ $fav" else item.badgeText
                )
            }
    }

    /**
     * "En Popüler Konular" (`PeriodicRankingTab.TOP_TOPICS`) için:
     * Her bir öğenin kendi gerçek başlık URL'si (`topic.cleanUrl`) doğrudan bağlanır (`isDirectEntry = false`, `entryId = null`).
     * Tıklandığında ekranda hangi başlık yazıyorsa %100 o başlığın sayfası açılır.
     */
    private suspend fun compileTopPopularTopicsFallback(
        baseDomain: String,
        period: PeriodicTimeRange
    ): List<RankedTopicOrEntryItem> = coroutineScope {
        val pages = when (period) {
            PeriodicTimeRange.YESTERDAY -> listOf(1, 2)
            PeriodicTimeRange.LAST_WEEK -> listOf(1, 2, 3)
            PeriodicTimeRange.LAST_MONTH -> listOf(1, 2, 3, 4)
        }
        val semaphore = Semaphore(4)
        val topics = pages.map { p ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    runCatching {
                        val url = "$baseDomain/basliklar/gundem?p=$p&_=${System.currentTimeMillis()}"
                        val html = webViewFetchBridge.fetchHtml(rawUrl = url, isAjax = true)
                        eksiParser.parseLeftFrameTopics(html = html, isTrackedSection = false)
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll()
            .flatten()
            .filter { it.cleanUrl.isNotBlank() && !eksiParser.isDirectSingleEntryUrl(it.cleanUrl) }
            .distinctBy { it.cleanUrl }
            .distinctBy { it.title.trim().lowercase() }

        topics
            .sortedByDescending { it.entryCount }
            .take(50)
            .mapIndexed { idx, topic ->
                val countLabel = topic.entryCountText.ifBlank {
                    if (topic.entryCount > 0) topic.entryCount.toString() else "Popüler"
                }
                RankedTopicOrEntryItem(
                    rank = idx + 1,
                    id = topic.id,
                    title = topic.title,
                    url = topic.cleanUrl,
                    badgeText = "$countLabel entry",
                    favoriteCount = topic.entryCount,
                    author = null,
                    entryId = null,
                    isDirectEntry = false
                )
            }
    }

    /**
     * Arama sonuçlarını (`/basliklar/ara?SearchForm.Keywords=...&SearchForm.SortOrder=Count&p=N`)
     * [WebViewFetchBridge] ve Paging 3 ile sayfalama hatası olmadan çeker.
     */
    fun getSearchTopicsPager(filter: SearchFilter): Flow<PagingData<TopicItem>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                prefetchDistance = 15,
                initialLoadSize = 20,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                SearchTopicsPagingSource(
                    filter = filter,
                    webViewFetchBridge = webViewFetchBridge,
                    eksiParser = eksiParser
                )
            }
        ).flow
    }

    /**
     * Direct Page State Mimarisi:
     * Başlığın SADECE istenen `page` (`?p=X`) sayfasını çeker ve ayrıştırır.
     * - Olaylar (`?a=tracked`) boş dönerse parametresiz ana başlığa fallback yapar.
     * - Sadece Bugün (`?a=popular`) boş dönerse 1. sayfadan itibaren tüm entry'lere fallback yapar.
     */
    suspend fun fetchSingleTopicPage(
        initialTopicUrl: String,
        resolvedCleanTopicUrl: String? = null,
        page: Int,
        filterMode: TopicFilterMode,
        inTopicSearchKeyword: String = "",
        trackedFallbackAlreadyActive: Boolean = false,
        todayFallbackAlreadyActive: Boolean = false
    ): Result<TopicPageData> = withContext(Dispatchers.IO) {
        try {
            val safePage = page.coerceAtLeast(1)
            val baseForRequest = resolvedCleanTopicUrl?.takeIf {
                it.isNotBlank() && !eksiParser.isDirectSingleEntryUrl(it)
            } ?: initialTopicUrl

            val effectiveFilterMode = if (todayFallbackAlreadyActive && filterMode == TopicFilterMode.TODAY_POPULAR) {
                TopicFilterMode.ALL
            } else {
                filterMode
            }

            val targetUrl = eksiParser.buildTopicPageUrl(
                baseOrRawUrl = baseForRequest,
                page = safePage,
                filterMode = effectiveFilterMode,
                searchKeywordInTopic = inTopicSearchKeyword,
                forceStripTracked = trackedFallbackAlreadyActive || (safePage > 1)
            )

            val primaryHtml = webViewFetchBridge.fetchHtml(
                rawUrl = targetUrl,
                isAjax = false
            )
            var resultPageData = withContext(Dispatchers.Default) {
                eksiParser.parseTopicPage(
                    html = primaryHtml,
                    requestedUrl = targetUrl,
                    fallbackPage = safePage,
                    usedTrackedFallback = trackedFallbackAlreadyActive,
                    usedTodayFallback = todayFallbackAlreadyActive
                )
            }

            // 1. Olaylar (?a=tracked) sekmesinde okunan başlığa tekrar tıklandığında boş gelme hatası kontrolü
            if (resultPageData.entries.isEmpty() && eksiParser.containsTrackedParam(targetUrl)) {
                val cleanFallbackUrl = eksiParser.buildTopicPageUrl(
                    baseOrRawUrl = eksiParser.stripTrackedParameters(initialTopicUrl),
                    page = safePage,
                    filterMode = TopicFilterMode.ALL,
                    searchKeywordInTopic = inTopicSearchKeyword,
                    forceStripTracked = true
                )
                val fallbackHtml = webViewFetchBridge.fetchHtml(
                    rawUrl = cleanFallbackUrl,
                    isAjax = false
                )
                resultPageData = withContext(Dispatchers.Default) {
                    eksiParser.parseTopicPage(
                        html = fallbackHtml,
                        requestedUrl = cleanFallbackUrl,
                        fallbackPage = safePage,
                        usedTrackedFallback = true,
                        usedTodayFallback = todayFallbackAlreadyActive
                    )
                }
            }

            // 2. "Sadece Bugün" (?a=popular) modunda bugün hiç entry girilmemişse otomatik tüm entry'lere fallback
            if (resultPageData.entries.isEmpty() &&
                (effectiveFilterMode == TopicFilterMode.TODAY_POPULAR || eksiParser.containsTodayPopularParam(targetUrl))
            ) {
                val allEntriesFallbackUrl = eksiParser.buildTopicPageUrl(
                    baseOrRawUrl = eksiParser.stripTrackedParameters(baseForRequest),
                    page = safePage,
                    filterMode = TopicFilterMode.ALL,
                    searchKeywordInTopic = inTopicSearchKeyword,
                    forceStripTracked = true
                )
                val fallbackHtml = webViewFetchBridge.fetchHtml(
                    rawUrl = allEntriesFallbackUrl,
                    isAjax = false
                )
                resultPageData = withContext(Dispatchers.Default) {
                    eksiParser.parseTopicPage(
                        html = fallbackHtml,
                        requestedUrl = allEntriesFallbackUrl,
                        fallbackPage = safePage,
                        usedTrackedFallback = resultPageData.usedTrackedFallback,
                        usedTodayFallback = true
                    )
                }
            }

            val finalEntries = if (filterMode == TopicFilterMode.LINKS_ONLY) {
                resultPageData.entries.filter { it.hasExternalLink }
            } else {
                resultPageData.entries
            }.map { entry ->
                entry.copy(pageNumber = safePage)
            }

            // Çekilen sayfadaki entry'lerin favori sayılarını da önbelleğe kaydet
            for (entry in finalEntries) {
                if (entry.id.isNotBlank() && entry.favoriteCount >= 0) {
                    entryFavoriteCountCache["entry:${entry.id}"] = entry.favoriteCount
                    if (entry.author.isNotBlank()) {
                        entryAuthorCache["entry:${entry.id}"] = entry.author
                    }
                }
            }

            val finalPageCount = resultPageData.pageCount.coerceAtLeast(safePage)
            Result.success(
                resultPageData.copy(
                    currentPage = safePage,
                    pageCount = finalPageCount,
                    entries = finalEntries
                )
            )
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * `BkzPreviewBottomSheet` için `(bkz: ...)` veya `*` tıklandığında ilgili başlığın
     * en popüler (`?a=nice`) veya ilk 5 entry'sini [WebViewFetchBridge] üzerinden hızla çeker.
     */
    suspend fun fetchBkzPreview(bkzQuery: String): Result<TopicPageData> = withContext(Dispatchers.IO) {
        try {
            val cleanQuery = bkzQuery.trim()
            val encoded = android.net.Uri.encode(cleanQuery)
            val baseDomain = webViewFetchBridge.getActiveBaseDomain()
            val niceUrl = "$baseDomain/?q=$encoded&a=nice"
            val fallbackUrl = "$baseDomain/?q=$encoded"

            val niceHtml = webViewFetchBridge.fetchHtml(rawUrl = niceUrl, isAjax = false)
            val niceParsed = eksiParser.parseTopicPage(
                html = niceHtml,
                requestedUrl = niceUrl,
                fallbackPage = 1
            )

            if (niceParsed.entries.isNotEmpty()) {
                return@withContext Result.success(
                    niceParsed.copy(entries = niceParsed.entries.take(5))
                )
            }

            val firstPageHtml = webViewFetchBridge.fetchHtml(rawUrl = fallbackUrl, isAjax = false)
            val firstPageParsed = eksiParser.parseTopicPage(
                html = firstPageHtml,
                requestedUrl = fallbackUrl,
                fallbackPage = 1
            )
            Result.success(
                firstPageParsed.copy(entries = firstPageParsed.entries.take(5))
            )
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}

class LeftFramePagingSource(
    private val section: LeftFrameSection,
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser
) : PagingSource<Int, TopicItem>() {

    override fun getRefreshKey(state: PagingState<Int, TopicItem>): Int? {
        return state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TopicItem> {
        val page = params.key ?: 1
        return try {
            val baseDomain = webViewFetchBridge.getActiveBaseDomain().ifBlank {
                WebViewCookieJar.DEFAULT_BASE_URL
            }
            val url = if (page == 1) {
                "$baseDomain${section.endpointPath}?_=${System.currentTimeMillis()}"
            } else {
                "$baseDomain${section.endpointPath}?p=$page&_=${System.currentTimeMillis()}"
            }

            val html = webViewFetchBridge.fetchHtml(
                rawUrl = url,
                isAjax = true
            )

            val items = withContext(Dispatchers.Default) {
                eksiParser.parseLeftFrameTopics(
                    html = html,
                    isTrackedSection = (section == LeftFrameSection.OLAYLAR)
                )
            }

            val canPaginate = (section == LeftFrameSection.GUNDEM || section == LeftFrameSection.BUGUN)
            val nextKey = if (items.isEmpty() || !canPaginate || page >= 15) null else page + 1

            LoadResult.Page(
                data = items,
                prevKey = if (page == 1) null else page - 1,
                nextKey = nextKey
            )
        } catch (e: Throwable) {
            LoadResult.Error(e)
        }
    }
}

/**
 * `https://eksisozluk.com/basliklar/kanal/{kanal_slug}?p={page}` rotasından
 * seçili kanalın güncel başlıklarını ve entry sayılarını Paging 3 ile çeken kaynak.
 */
class ChannelTopicsPagingSource(
    private val channel: EksiChannel,
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser
) : PagingSource<Int, TopicItem>() {

    override fun getRefreshKey(state: PagingState<Int, TopicItem>): Int? {
        return state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TopicItem> {
        val page = params.key ?: 1
        return try {
            val baseDomain = webViewFetchBridge.getActiveBaseDomain().ifBlank {
                WebViewCookieJar.DEFAULT_BASE_URL
            }
            val primaryChannelUrl = eksiParser.buildChannelTopicsUrl(
                baseDomain = baseDomain,
                channel = channel,
                page = page,
                useRawTurkishSlug = false
            )

            val primaryHtml = webViewFetchBridge.fetchHtml(
                rawUrl = primaryChannelUrl,
                isAjax = true
            )

            var items = withContext(Dispatchers.Default) {
                eksiParser.parseChannelTopics(primaryHtml)
            }

            if (items.isEmpty() && channel.rawSlug != channel.slug) {
                val altChannelUrl = eksiParser.buildChannelTopicsUrl(
                    baseDomain = baseDomain,
                    channel = channel,
                    page = page,
                    useRawTurkishSlug = true
                )
                val altHtml = webViewFetchBridge.fetchHtml(
                    rawUrl = altChannelUrl,
                    isAjax = true
                )
                items = withContext(Dispatchers.Default) {
                    eksiParser.parseChannelTopics(altHtml)
                }
            }

            if (items.isEmpty()) {
                val searchFallbackUrl = eksiParser.buildSearchUrl(
                    filter = SearchFilter(keywords = channel.displayName.lowercase()),
                    page = page
                )
                val searchHtml = webViewFetchBridge.fetchHtml(
                    rawUrl = searchFallbackUrl,
                    isAjax = true
                )
                items = withContext(Dispatchers.Default) {
                    eksiParser.parseChannelTopics(searchHtml)
                }
            }

            val nextKey = if (items.isEmpty() || page >= 15) null else page + 1

            LoadResult.Page(
                data = items,
                prevKey = if (page == 1) null else page - 1,
                nextKey = nextKey
            )
        } catch (e: Throwable) {
            LoadResult.Error(e)
        }
    }
}

class SearchTopicsPagingSource(
    private val filter: SearchFilter,
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser
) : PagingSource<Int, TopicItem>() {

    override fun getRefreshKey(state: PagingState<Int, TopicItem>): Int? {
        return state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TopicItem> {
        if (filter.keywords.isBlank()) {
            return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
        }
        val page = params.key ?: 1
        return try {
            val searchUrl = eksiParser.buildSearchUrl(filter = filter, page = page)

            val html = webViewFetchBridge.fetchHtml(
                rawUrl = searchUrl,
                isAjax = true
            )

            val parsedPage = withContext(Dispatchers.Default) {
                eksiParser.parseSearchResults(
                    html = html,
                    query = filter.keywords,
                    requestedPage = page
                )
            }

            val nextKey = if (parsedPage.topics.isEmpty() || page >= parsedPage.pageCount) {
                null
            } else {
                page + 1
            }

            LoadResult.Page(
                data = parsedPage.topics,
                prevKey = if (page == 1) null else page - 1,
                nextKey = nextKey
            )
        } catch (e: Throwable) {
            LoadResult.Error(e)
        }
    }
}
