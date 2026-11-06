package com.example.data.repository

import android.util.LruCache
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
import com.example.network.ContentSource
import com.example.network.WebViewCookieJar
import com.example.network.WebViewFetchBridge
import kotlinx.coroutines.CancellationException
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
 * Gündem 1. sayfasındaki başlık URL'lerini üst sınırlı (`LruCache(500)`) ve iş parçacığı güvenli
 * olarak tutan önbellek.
 */
class BoundedGundemUrlCache(maxSize: Int = 500) {
    private val lock = Any()
    private val lru = LruCache<String, Boolean>(maxSize)

    fun put(urlKey: String) {
        val normalized = urlKey.trim().lowercase()
        if (normalized.isNotBlank()) {
            synchronized(lock) {
                lru.put(normalized, true)
            }
        }
    }

    fun contains(urlKey: String): Boolean {
        val normalized = urlKey.trim().lowercase()
        if (normalized.isBlank()) return false
        synchronized(lock) {
            return lru.get(normalized) == true
        }
    }

    fun isEmpty(): Boolean {
        synchronized(lock) {
            return lru.size() == 0
        }
    }

    fun isNotEmpty(): Boolean = !isEmpty()

    fun evictAll() {
        synchronized(lock) {
            lru.evictAll()
        }
    }
}

/**
 * Ekşi+ veri katmanı:
 * - Tüm HTML veri çekme işlemlerini [WebViewFetchBridge] üzerinden gerçekleştirir.
 * - Sol Frame (Gündem, Bugün, Debe, Olaylar), Kanallar (`/basliklar/kanal/{kanal_slug}?p={page}`),
 *   DEBE 50 (`/debe`) ve Dönemsel Enler (`/istatistik/...`) rotalarını ayrıştırır.
 * - DEBE ve Dönemsel Enler öğelerinin beğeni (`data-favorite-count` / `.favorite-count`) sayılarını
 *   üst sınırlı (`LruCache(2000)`) önbellekte tutar ve paralel `Semaphore(4)` ile arka planda tamamlar.
 * - Başlık detay ekranında Paging 3'ün `prevKey` sonsuz geri sarma döngüsünü önlemek için
 *   doğrudan tekil sayfa çekme ([fetchSingleTopicPage]) ve `X + 1` arka plan önbellekleme mimarisini destekler.
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
     * üst sınırlı (`LruCache(2000)`) olarak tutan bellek içi önbellekler.
     */
    private val entryCacheLock = Any()
    private val entryFavoriteCountCache = LruCache<String, Int>(2000)
    private val entryAuthorCache = LruCache<String, String>(2000)
    private val entryResolvedIdCache = LruCache<String, String>(2000)
    private val entryTopicTitleCache = LruCache<String, String>(2000)

    /**
     * Gündem 1. sayfasındaki başlık URL'lerini tutar (`LruCache(500)`).
     */
    private val gundemPage1UrlCache = BoundedGundemUrlCache(500)

    private fun getCachedFav(key: String): Int? = synchronized(entryCacheLock) {
        entryFavoriteCountCache.get(key)
    }

    private fun putCachedFav(key: String, count: Int) = synchronized(entryCacheLock) {
        entryFavoriteCountCache.put(key, count)
    }

    private fun getCachedAuthor(key: String): String? = synchronized(entryCacheLock) {
        entryAuthorCache.get(key)
    }

    private fun putCachedAuthor(key: String, author: String) = synchronized(entryCacheLock) {
        entryAuthorCache.put(key, author)
    }

    private fun getCachedResolvedId(key: String): String? = synchronized(entryCacheLock) {
        entryResolvedIdCache.get(key)
    }

    private fun putCachedResolvedId(key: String, entryId: String) = synchronized(entryCacheLock) {
        entryResolvedIdCache.put(key, entryId)
    }

    private fun getCachedTopicTitle(key: String): String? = synchronized(entryCacheLock) {
        entryTopicTitleCache.get(key)
    }

    private fun putCachedTopicTitle(key: String, title: String) = synchronized(entryCacheLock) {
        entryTopicTitleCache.put(key, title)
    }

    fun clearGundemCache() {
        gundemPage1UrlCache.evictAll()
    }

    /**
     * Sistem `onTrimMemory` bildiriminde tüm bellek içi LruCache'leri temizler.
     */
    fun clearMemoryCaches() {
        synchronized(entryCacheLock) {
            entryFavoriteCountCache.evictAll()
            entryAuthorCache.evictAll()
            entryResolvedIdCache.evictAll()
            entryTopicTitleCache.evictAll()
        }
        gundemPage1UrlCache.evictAll()
        webViewFetchBridge.clearMemoryCaches()
    }

    fun observeBookmarks(): Flow<List<BookmarkedEntryEntity>> =
        eksinDao.observeBookmarkedEntries()

    fun observeAllReadStates(): Flow<List<TopicReadStateEntity>> =
        readStateDao.observeAllReadStates()

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
     * Gündem başlıklarını (`https://eksisozluk.com/basliklar/gundem?p={page}`) bağımsız Pager ile çeker.
     */
    fun getGundemTopicsPager(bypassCacheOnFirstPage: Boolean = false): Flow<PagingData<TopicItem>> {
        return Pager(
            config = PagingConfig(
                pageSize = 25,
                prefetchDistance = 20,
                initialLoadSize = 25,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                LeftFramePagingSource(
                    section = LeftFrameSection.GUNDEM,
                    webViewFetchBridge = webViewFetchBridge,
                    eksiParser = eksiParser,
                    gundemPage1UrlCache = gundemPage1UrlCache,
                    bypassCacheOnFirstPage = bypassCacheOnFirstPage
                )
            }
        ).flow
    }

    /**
     * Bugün / Son Başlıklar sekmesini Gündem'den tamamen bağımsız bir Pager ile çeker.
     */
    fun getBugunTopicsPager(bypassCacheOnFirstPage: Boolean = false): Flow<PagingData<TopicItem>> {
        return Pager(
            config = PagingConfig(
                pageSize = 25,
                prefetchDistance = 20,
                initialLoadSize = 25,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                BugunTopicsPagingSource(
                    webViewFetchBridge = webViewFetchBridge,
                    eksiParser = eksiParser,
                    gundemPage1UrlCache = gundemPage1UrlCache,
                    bypassCacheOnFirstPage = bypassCacheOnFirstPage
                )
            }
        ).flow
    }

    /**
     * Olaylar (Takip Edilen Başlıklar) sekmesini bağımsız Pager ile çeker.
     */
    fun getOlaylarTopicsPager(bypassCacheOnFirstPage: Boolean = false): Flow<PagingData<TopicItem>> {
        return Pager(
            config = PagingConfig(
                pageSize = 25,
                prefetchDistance = 20,
                initialLoadSize = 25,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                LeftFramePagingSource(
                    section = LeftFrameSection.OLAYLAR,
                    webViewFetchBridge = webViewFetchBridge,
                    eksiParser = eksiParser,
                    gundemPage1UrlCache = gundemPage1UrlCache,
                    bypassCacheOnFirstPage = bypassCacheOnFirstPage
                )
            }
        ).flow
    }

    /**
     * Sol frame başlıklarını (Gündem, Bugün, Debe, Olaylar) [WebViewFetchBridge] ve Paging 3 ile çeker.
     */
    fun getLeftFrameTopicsPager(
        section: LeftFrameSection,
        bypassCacheOnFirstPage: Boolean = false
    ): Flow<PagingData<TopicItem>> {
        return when (section) {
            LeftFrameSection.GUNDEM -> getGundemTopicsPager(bypassCacheOnFirstPage)
            LeftFrameSection.BUGUN -> getBugunTopicsPager(bypassCacheOnFirstPage)
            LeftFrameSection.OLAYLAR -> getOlaylarTopicsPager(bypassCacheOnFirstPage)
            LeftFrameSection.DEBE -> Pager(
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
                        eksiParser = eksiParser,
                        gundemPage1UrlCache = gundemPage1UrlCache,
                        bypassCacheOnFirstPage = bypassCacheOnFirstPage
                    )
                }
            ).flow
        }
    }

    /**
     * 1. KANALLAR SEKMESİ:
     * Seçilen kanalın (`#spor`, `#teknoloji`, `#siyaset` vb.) güncel başlıklarını
     * `https://eksisozluk.com/basliklar/kanal/{kanal_slug}?p={page}` rotasından Paging 3 ile çeker.
     */
    fun getChannelTopicsPager(
        channel: EksiChannel,
        bypassCacheOnFirstPage: Boolean = false
    ): Flow<PagingData<TopicItem>> {
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
                    eksiParser = eksiParser,
                    bypassCacheOnFirstPage = bypassCacheOnFirstPage
                )
            }
        ).flow
    }

    private fun buildFavoriteCacheKey(item: RankedTopicOrEntryItem): String {
        val entryId = item.entryId?.trim()?.filter { it.isDigit() }.orEmpty()
        if (entryId.isNotBlank()) return "entry:$entryId"
        return "url:${item.url.trim()}"
    }

    /**
     * Öğenin `entryFavoriteCountCache` içinde zaten kayıtlı olup olmadığını kontrol eder.
     * Önbellekte varsa ağ isteği atılmadan doğrudan atlanır.
     */
    fun isFavoriteMetadataCached(item: RankedTopicOrEntryItem): Boolean {
        if (item.favoriteCount > 0 && !item.entryId.isNullOrBlank()) return true
        val key = buildFavoriteCacheKey(item)
        val cachedFav = getCachedFav(key)
        val cachedEntryId = item.entryId?.takeIf { it.isNotBlank() } ?: getCachedResolvedId(key)
        return cachedFav != null && !cachedEntryId.isNullOrBlank()
    }

    fun applyCachedFavoriteMetadata(item: RankedTopicOrEntryItem): RankedTopicOrEntryItem {
        val key = buildFavoriteCacheKey(item)
        val cachedFav = getCachedFav(key)
        val cachedAuthor = getCachedAuthor(key)
        val cachedEntryId = getCachedResolvedId(key)
        val cachedTitle = getCachedTopicTitle(key)

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
            putCachedFav(key, item.favoriteCount)
            if (!item.author.isNullOrBlank()) {
                putCachedAuthor(key, item.author)
            }
            if (resolvedEntryId != null) {
                putCachedResolvedId(key, resolvedEntryId)
                putCachedFav("entry:$resolvedEntryId", item.favoriteCount)
            }
            if (resolvedTitle.isNotBlank()) {
                putCachedTopicTitle(key, resolvedTitle)
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
     * çeker, `LruCache` içine alır ve güncellenmiş öğeyi döndürür.
     */
    suspend fun fetchAndCacheEntryFavoriteCount(
        item: RankedTopicOrEntryItem
    ): RankedTopicOrEntryItem = withContext(Dispatchers.IO) {
        val alreadyCached = applyCachedFavoriteMetadata(item)
        if (isFavoriteMetadataCached(alreadyCached)) {
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
                isAjax = false,
                bypassCache = false
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

                putCachedFav(key, favCount)
                if (!metadata.author.isNullOrBlank()) {
                    putCachedAuthor(key, metadata.author)
                }
                if (resolvedTitle.isNotBlank()) {
                    putCachedTopicTitle(key, resolvedTitle)
                }
                if (resolvedEntryId.isNotBlank()) {
                    val entryKey = "entry:$resolvedEntryId"
                    putCachedResolvedId(key, resolvedEntryId)
                    putCachedResolvedId(entryKey, resolvedEntryId)
                    putCachedFav(entryKey, favCount)
                    if (!metadata.author.isNullOrBlank()) {
                        putCachedAuthor(entryKey, metadata.author)
                    }
                    if (resolvedTitle.isNotBlank()) {
                        putCachedTopicTitle(entryKey, resolvedTitle)
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
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Ağ hatası durumunda mevcut öğeyi koru
        }
        item
    }

    /**
     * 3a. GELİŞMİŞ DEBE:
     * Sadece `https://eksisozluk.com/debe` (ve `/basliklar/debe`) gerçek listesini çeker.
     * Varsa hafızadaki (`entryFavoriteCountCache`) beğeni sayılarını ve gerçek başlık adlarını anında uygular.
     */
    suspend fun fetchDebeItems(
        bypassCache: Boolean = false
    ): Result<List<RankedTopicOrEntryItem>> = withContext(Dispatchers.IO) {
        try {
            val baseDomain = webViewFetchBridge.getActiveBaseDomain().ifBlank {
                WebViewCookieJar.DEFAULT_BASE_URL
            }.trimEnd('/')

            val fullPageUrl = "$baseDomain/debe"
            val fullPageHtml = try {
                webViewFetchBridge.fetchHtml(
                    rawUrl = fullPageUrl,
                    isAjax = false,
                    bypassCache = bypassCache
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                ""
            }
            val parsedFull = withContext(Dispatchers.Default) {
                eksiParser.parseDebeEntriesList(fullPageHtml)
            }

            // Eğer tam sayfa `/debe` zaten doğrudan `/entry/{id}` bağlantılı öğeleri verdiyse ikinci bir AJAX isteğine gerek yok
            val fullEntryCount = parsedFull.count { !it.entryId.isNullOrBlank() }
            if (parsedFull.size >= 10 && fullEntryCount >= parsedFull.size / 2) {
                return@withContext Result.success(
                    parsedFull.map { applyCachedFavoriteMetadata(it) }
                )
            }

            val ajaxUrl = "$baseDomain/debe"
            val ajaxHtml = try {
                webViewFetchBridge.fetchHtml(
                    rawUrl = ajaxUrl,
                    isAjax = true,
                    bypassCache = bypassCache
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                ""
            }
            val parsedAjax = withContext(Dispatchers.Default) {
                eksiParser.parseDebeEntriesList(ajaxHtml)
            }

            val ajaxEntryCount = parsedAjax.count { !it.entryId.isNullOrBlank() }

            val bestList = when {
                fullEntryCount > ajaxEntryCount && parsedFull.isNotEmpty() -> parsedFull
                ajaxEntryCount > fullEntryCount && parsedAjax.isNotEmpty() -> parsedAjax
                parsedFull.size >= parsedAjax.size && parsedFull.isNotEmpty() -> parsedFull
                parsedAjax.isNotEmpty() -> parsedAjax
                else -> emptyList()
            }

            if (bestList.isNotEmpty()) {
                return@withContext Result.success(
                    bestList.map { applyCachedFavoriteMetadata(it) }
                )
            }

            val fallbackUrl = "$baseDomain/basliklar/debe"
            val fallbackHtml = webViewFetchBridge.fetchHtml(
                rawUrl = fallbackUrl,
                isAjax = true,
                bypassCache = bypassCache
            )
            val parsedFallback = withContext(Dispatchers.Default) {
                eksiParser.parseDebeEntriesList(fallbackHtml)
            }
            Result.success(parsedFallback.map { applyCachedFavoriteMetadata(it) })
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * 3b. DÖNEMSEL ENLER (Önceki Gün / Önceki Hafta / Önceki Ay):
     */
    suspend fun fetchPeriodicBestItems(
        period: PeriodicTimeRange,
        rankingTab: PeriodicRankingTab,
        bypassCache: Boolean = false
    ): Result<List<RankedTopicOrEntryItem>> = withContext(Dispatchers.IO) {
        try {
            val baseDomain = webViewFetchBridge.getActiveBaseDomain().ifBlank {
                WebViewCookieJar.DEFAULT_BASE_URL
            }.trimEnd('/')

            val isEntriesMode = (rankingTab == PeriodicRankingTab.TOP_ENTRIES)

            if (isEntriesMode && period == PeriodicTimeRange.YESTERDAY) {
                val yesterdayEntries = compileTopLikedEntriesFallback(
                    baseDomain = baseDomain,
                    period = period,
                    bypassCache = bypassCache
                )
                return@withContext Result.success(yesterdayEntries)
            }

            val statsPath = if (isEntriesMode) period.entriesStatsPath else period.topicsStatsPath
            val statsUrl = "$baseDomain$statsPath"

            val statsHtml = webViewFetchBridge.fetchHtml(
                rawUrl = statsUrl,
                isAjax = false,
                bypassCache = bypassCache
            )
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

            if (isEntriesMode) {
                val compiledTopEntries = compileTopLikedEntriesFallback(
                    baseDomain = baseDomain,
                    period = period,
                    bypassCache = bypassCache
                )
                return@withContext Result.success(compiledTopEntries)
            } else {
                val compiledTopTopics = compileTopPopularTopicsFallback(
                    baseDomain = baseDomain,
                    period = period,
                    bypassCache = bypassCache
                )
                return@withContext Result.success(compiledTopTopics)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private suspend fun compileTopLikedEntriesFallback(
        baseDomain: String,
        period: PeriodicTimeRange,
        bypassCache: Boolean = false
    ): List<RankedTopicOrEntryItem> = coroutineScope {
        val semaphore = Semaphore(4)
        val collectedByEntryId = ConcurrentHashMap<String, RankedTopicOrEntryItem>()

        // 1. DEBE (`/debe`) gerçek listesini çek
        val debeDeferred = async(Dispatchers.IO) {
            try {
                val debeHtml = webViewFetchBridge.fetchHtml(
                    rawUrl = "$baseDomain/debe",
                    isAjax = true,
                    bypassCache = bypassCache
                )
                eksiParser.parseDebeEntriesList(debeHtml).map { applyCachedFavoriteMetadata(it) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                emptyList()
            }
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
                    try {
                        val url = "$baseDomain/basliklar/gundem?p=$pageNum"
                        val html = webViewFetchBridge.fetchHtml(
                            rawUrl = url,
                            isAjax = true,
                            bypassCache = bypassCache
                        )
                        eksiParser.parseLeftFrameTopics(html = html, isTrackedSection = false)
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        emptyList()
                    }
                }
            }
        }

        val channelTopicsDeferred = channelsToFetch.map { channel ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val url = eksiParser.buildChannelTopicsUrl(
                            baseDomain = baseDomain,
                            channel = channel,
                            page = 1
                        )
                        val html = webViewFetchBridge.fetchHtml(rawUrl = url, isAjax = true)
                        eksiParser.parseChannelTopics(html)
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        emptyList()
                    }
                }
            }
        }

        val allCandidateTopics = (gundemTopicsDeferred.awaitAll().flatten() + channelTopicsDeferred.awaitAll().flatten())
            .filter { it.cleanUrl.isNotBlank() && !eksiParser.isDirectSingleEntryUrl(it.cleanUrl) }
            .distinctBy { it.cleanUrl }
            .distinctBy { it.title.trim().lowercase() }
            .sortedByDescending { it.entryCount }
            .take(if (period == PeriodicTimeRange.YESTERDAY) 8 else 14)

        val singleBestEntryPerTopic = allCandidateTopics.map { topic ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
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
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        null
                    }
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
            putCachedFav(cacheKey, fav)
            putCachedAuthor(cacheKey, entry.author)
            putCachedResolvedId(cacheKey, entry.id)
            putCachedTopicTitle(cacheKey, entry.topicTitle)
            collectedByEntryId[entry.id] = item
        }

        val debeList = debeDeferred.await()
        val debeSubset = when (period) {
            PeriodicTimeRange.YESTERDAY -> debeList.take(35)
            PeriodicTimeRange.LAST_WEEK -> debeList.take(20)
            PeriodicTimeRange.LAST_MONTH -> debeList.drop(5).take(20)
        }

        val enrichedDebeItems = debeSubset.map { debeItem ->
            async(Dispatchers.IO) {
                val withCache = applyCachedFavoriteMetadata(debeItem)
                if (isFavoriteMetadataCached(withCache)) {
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

    private suspend fun compileTopPopularTopicsFallback(
        baseDomain: String,
        period: PeriodicTimeRange,
        bypassCache: Boolean = false
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
                    try {
                        val url = "$baseDomain/basliklar/gundem?p=$p"
                        val html = webViewFetchBridge.fetchHtml(
                            rawUrl = url,
                            isAjax = true,
                            bypassCache = bypassCache
                        )
                        eksiParser.parseLeftFrameTopics(html = html, isTrackedSection = false)
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        emptyList()
                    }
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
    fun getSearchTopicsPager(
        filter: SearchFilter,
        bypassCacheOnFirstPage: Boolean = false
    ): Flow<PagingData<TopicItem>> {
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
                    eksiParser = eksiParser,
                    bypassCacheOnFirstPage = bypassCacheOnFirstPage
                )
            }
        ).flow
    }

    /**
     * Direct Page State Mimarisi:
     * Başlığın SADECE istenen `page` (`?p=X`) sayfasını çeker ve ayrıştırır.
     */
    suspend fun fetchSingleTopicPage(
        initialTopicUrl: String,
        resolvedCleanTopicUrl: String? = null,
        page: Int,
        filterMode: TopicFilterMode,
        inTopicSearchKeyword: String = "",
        trackedFallbackAlreadyActive: Boolean = false,
        todayFallbackAlreadyActive: Boolean = false,
        bypassCache: Boolean = false
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

            var activeFetchedUrl = targetUrl
            val primaryHtml = webViewFetchBridge.fetchHtml(
                rawUrl = targetUrl,
                isAjax = false,
                bypassCache = bypassCache
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
                activeFetchedUrl = cleanFallbackUrl
                val fallbackHtml = webViewFetchBridge.fetchHtml(
                    rawUrl = cleanFallbackUrl,
                    isAjax = false,
                    bypassCache = bypassCache
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
                activeFetchedUrl = allEntriesFallbackUrl
                val fallbackHtml = webViewFetchBridge.fetchHtml(
                    rawUrl = allEntriesFallbackUrl,
                    isAjax = false,
                    bypassCache = bypassCache
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

            // Çekilen sayfadaki entry'lerin favori sayılarını da LruCache'e kaydet
            for (entry in finalEntries) {
                if (entry.id.isNotBlank() && entry.favoriteCount >= 0) {
                    val key = "entry:${entry.id}"
                    putCachedFav(key, entry.favoriteCount)
                    putCachedResolvedId(key, entry.id)
                    if (entry.author.isNotBlank()) {
                        putCachedAuthor(key, entry.author)
                    }
                    if (entry.topicTitle.isNotBlank()) {
                        putCachedTopicTitle(key, entry.topicTitle)
                    }
                }
            }

            val isWayback = webViewFetchBridge.isUrlServedFromWayback(activeFetchedUrl) ||
                webViewFetchBridge.isUrlServedFromWayback(resultPageData.cleanTopicUrl) ||
                webViewFetchBridge.lastContentSource.value == ContentSource.WAYBACK_ARCHIVE

            val finalPageCount = resultPageData.pageCount.coerceAtLeast(safePage)
            Result.success(
                resultPageData.copy(
                    currentPage = safePage,
                    pageCount = finalPageCount,
                    entries = finalEntries,
                    isFromWaybackArchive = isWayback
                )
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
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
                    niceParsed.copy(
                        entries = niceParsed.entries.take(5),
                        isFromWaybackArchive = webViewFetchBridge.isUrlServedFromWayback(niceUrl) ||
                            webViewFetchBridge.lastContentSource.value == ContentSource.WAYBACK_ARCHIVE
                    )
                )
            }

            val firstPageHtml = webViewFetchBridge.fetchHtml(rawUrl = fallbackUrl, isAjax = false)
            val firstPageParsed = eksiParser.parseTopicPage(
                html = firstPageHtml,
                requestedUrl = fallbackUrl,
                fallbackPage = 1
            )
            Result.success(
                firstPageParsed.copy(
                    entries = firstPageParsed.entries.take(5),
                    isFromWaybackArchive = webViewFetchBridge.isUrlServedFromWayback(fallbackUrl) ||
                        webViewFetchBridge.lastContentSource.value == ContentSource.WAYBACK_ARCHIVE
                )
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }
}

class LeftFramePagingSource(
    private val section: LeftFrameSection,
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser,
    private val gundemPage1UrlCache: BoundedGundemUrlCache,
    private val bypassCacheOnFirstPage: Boolean = false
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
            }.trimEnd('/')
            val url = if (page == 1) {
                "$baseDomain${section.endpointPath}"
            } else {
                "$baseDomain${section.endpointPath}?p=$page"
            }

            val shouldBypass = bypassCacheOnFirstPage && page == 1
            val html = webViewFetchBridge.fetchHtml(
                rawUrl = url,
                isAjax = true,
                bypassCache = shouldBypass
            )

            var items = withContext(Dispatchers.Default) {
                eksiParser.parseLeftFrameTopics(
                    html = html,
                    isTrackedSection = (section == LeftFrameSection.OLAYLAR)
                )
            }

            if (items.isEmpty()) {
                val fullPageHtml = webViewFetchBridge.fetchHtml(
                    rawUrl = url,
                    isAjax = false,
                    bypassCache = shouldBypass
                )
                items = withContext(Dispatchers.Default) {
                    eksiParser.parseLeftFrameTopics(
                        html = fullPageHtml,
                        isTrackedSection = (section == LeftFrameSection.OLAYLAR)
                    )
                }
            }

            if (section == LeftFrameSection.GUNDEM && page == 1 && items.isNotEmpty()) {
                gundemPage1UrlCache.evictAll()
                items.forEach { topic ->
                    gundemPage1UrlCache.put(topic.cleanUrl)
                }
            }

            val canPaginate = (section == LeftFrameSection.GUNDEM || section == LeftFrameSection.BUGUN)
            val nextKey = if (items.isEmpty() || !canPaginate || page >= 15) null else page + 1

            LoadResult.Page(
                data = items,
                prevKey = if (page == 1) null else page - 1,
                nextKey = nextKey
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            LoadResult.Error(e)
        }
    }
}

class BugunTopicsPagingSource(
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser,
    private val gundemPage1UrlCache: BoundedGundemUrlCache,
    private val bypassCacheOnFirstPage: Boolean = false
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
            }.trimEnd('/')

            if (gundemPage1UrlCache.isEmpty()) {
                try {
                    val gundemHtml = webViewFetchBridge.fetchHtml(
                        rawUrl = "$baseDomain/basliklar/gundem",
                        isAjax = true
                    )
                    val gundemItems = withContext(Dispatchers.Default) {
                        eksiParser.parseLeftFrameTopics(gundemHtml, isTrackedSection = false)
                    }
                    gundemItems.forEach { gundemPage1UrlCache.put(it.cleanUrl) }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                }
            }

            val shouldBypass = bypassCacheOnFirstPage && page == 1
            val bugunUrl = "$baseDomain/basliklar/bugun?p=$page"
            val bugunHtml = try {
                webViewFetchBridge.fetchHtml(
                    rawUrl = bugunUrl,
                    isAjax = true,
                    bypassCache = shouldBypass
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                ""
            }
            val rawBugunItems = withContext(Dispatchers.Default) {
                eksiParser.parseLeftFrameTopics(bugunHtml, isTrackedSection = false)
            }

            val overlapCount = rawBugunItems.count { gundemPage1UrlCache.contains(it.cleanUrl) }
            val isDuplicateOfGundem = rawBugunItems.isEmpty() ||
                (gundemPage1UrlCache.isNotEmpty() && overlapCount * 2 >= rawBugunItems.size)

            val finalItems: List<TopicItem> = if (!isDuplicateOfGundem) {
                rawBugunItems.filterNot { gundemPage1UrlCache.contains(it.cleanUrl) }
                    .ifEmpty { rawBugunItems }
            } else {
                coroutineScope {
                    val semaphore = Semaphore(3)
                    val allChannels = EksiChannel.entries
                    val channelIndexA = ((page - 1) * 3) % allChannels.size
                    val channelIndexB = ((page - 1) * 3 + 1) % allChannels.size
                    val channelIndexC = ((page - 1) * 3 + 2) % allChannels.size
                    val selectedChannels = listOf(
                        allChannels[channelIndexA],
                        allChannels[channelIndexB],
                        allChannels[channelIndexC]
                    ).distinct()

                    val channelDeferreds = selectedChannels.map { channel ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                try {
                                    val channelPage = ((page - 1) / 5) + 1
                                    val url = eksiParser.buildChannelTopicsUrl(
                                        baseDomain = baseDomain,
                                        channel = channel,
                                        page = channelPage,
                                        useRawTurkishSlug = false
                                    )
                                    val html = webViewFetchBridge.fetchHtml(
                                        rawUrl = url,
                                        isAjax = true,
                                        bypassCache = shouldBypass
                                    )
                                    withContext(Dispatchers.Default) {
                                        eksiParser.parseChannelTopics(html)
                                    }
                                } catch (e: Throwable) {
                                    if (e is CancellationException) throw e
                                    emptyList()
                                }
                            }
                        }
                    }

                    val deeperAgendaDeferred = async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                val deeperPage = (page + 1).coerceAtMost(15)
                                val url = "$baseDomain/basliklar/gundem?p=$deeperPage"
                                val html = webViewFetchBridge.fetchHtml(
                                    rawUrl = url,
                                    isAjax = true,
                                    bypassCache = shouldBypass
                                )
                                withContext(Dispatchers.Default) {
                                    eksiParser.parseLeftFrameTopics(html, isTrackedSection = false)
                                }
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                emptyList()
                            }
                        }
                    }

                    val channelLists = channelDeferreds.awaitAll()
                    val deeperList = deeperAgendaDeferred.await()

                    val interleaved = mutableListOf<TopicItem>()
                    val maxLen = (channelLists.map { it.size } + listOf(deeperList.size)).maxOrNull() ?: 0
                    for (i in 0 until maxLen) {
                        for (cList in channelLists) {
                            if (i < cList.size) interleaved.add(cList[i])
                        }
                        if (i < deeperList.size) interleaved.add(deeperList[i])
                    }

                    interleaved
                        .filterNot { gundemPage1UrlCache.contains(it.cleanUrl) }
                        .distinctBy { it.cleanUrl.lowercase() }
                        .distinctBy { it.title.trim().lowercase() }
                }
            }

            val nextKey = if (finalItems.isEmpty() || page >= 15) null else page + 1

            LoadResult.Page(
                data = finalItems,
                prevKey = if (page == 1) null else page - 1,
                nextKey = nextKey
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            LoadResult.Error(e)
        }
    }
}

class ChannelTopicsPagingSource(
    private val channel: EksiChannel,
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser,
    private val bypassCacheOnFirstPage: Boolean = false
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
            val shouldBypass = bypassCacheOnFirstPage && page == 1
            val primaryChannelUrl = eksiParser.buildChannelTopicsUrl(
                baseDomain = baseDomain,
                channel = channel,
                page = page,
                useRawTurkishSlug = false
            )

            val primaryHtml = webViewFetchBridge.fetchHtml(
                rawUrl = primaryChannelUrl,
                isAjax = true,
                bypassCache = shouldBypass
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
                    isAjax = true,
                    bypassCache = shouldBypass
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
                    isAjax = true,
                    bypassCache = shouldBypass
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
            if (e is CancellationException) throw e
            LoadResult.Error(e)
        }
    }
}

class SearchTopicsPagingSource(
    private val filter: SearchFilter,
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser,
    private val bypassCacheOnFirstPage: Boolean = false
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
            val shouldBypass = bypassCacheOnFirstPage && page == 1

            val html = webViewFetchBridge.fetchHtml(
                rawUrl = searchUrl,
                isAjax = true,
                bypassCache = shouldBypass
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
            if (e is CancellationException) throw e
            LoadResult.Error(e)
        }
    }
}
