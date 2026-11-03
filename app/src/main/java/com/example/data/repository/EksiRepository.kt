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
import com.example.data.model.EntryItem
import com.example.data.model.LeftFrameSection
import com.example.data.model.SearchFilter
import com.example.data.model.TopicFilterMode
import com.example.data.model.TopicItem
import com.example.data.model.TopicPageData
import com.example.data.paging.TopicPagingSource
import com.example.data.remote.EksiParser
import com.example.network.WebViewCookieJar
import com.example.network.WebViewFetchBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Ekşin OLED veri katmanı:
// - Tüm HTML veri çekme işlemlerini OkHttp yerine WebViewFetchBridge (singleton görünmez WebView
//   içinde fetch(url, { headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'text/html, */*; q=0.01' }, credentials: 'include' })
//   ve loadUrl -> document.documentElement.outerHTML fallback) üzerinden gerçekleştirir.
// - Başlığa girildiğinde en son okunan entryId, pageNumber, scrollIndex ve scrollOffset
//   değerlerini ReadStateDao üzerinden Room veritabanına kaydeder ve başlığa tekrar girildiğinde
//   doğrudan kalınan sayfadan ve entry'den devam edilmesini sağlar.
@Singleton
class EksiRepository @Inject constructor(
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser,
    private val readStateDao: ReadStateDao,
    private val eksinDao: EksinDao
) {

    fun observeBookmarks(): Flow<List<BookmarkedEntryEntity>> =
        eksinDao.observeBookmarkedEntries()

    /**
     * Tüm okunan başlıkların son kaldığı yer (`entryId`, `pageNumber`, `scrollOffset`) geçmişini gözlemler.
     */
    fun observeAllReadStates(): Flow<List<TopicReadStateEntity>> =
        readStateDao.observeAllReadStates()

    /**
     * Belirli bir başlık URL'si için Room'da kayıtlı okuma durumunu reaktif olarak gözlemler.
     */
    fun observeTopicReadState(rawOrCleanTopicUrl: String): Flow<TopicReadStateEntity?> {
        val canonicalKey = normalizeTopicKey(rawOrCleanTopicUrl)
        return readStateDao.observeReadStateByKey(canonicalKey)
    }

    /**
     * Başlığa tekrar girildiğinde doğrudan kalınan sayfadan (`pageNumber`) ve entry'den (`entryId`, `scrollOffset`)
     * devam etmek için kayıtlı [TopicReadStateEntity] nesnesini getirir.
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

    suspend fun deleteTopicReadState(topicUrl: String) = withContext(Dispatchers.IO) {
        val canonicalKey = normalizeTopicKey(topicUrl)
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
     * Başlık entry'lerini [TopicPagingSource] ve yüksek `prefetchDistance` ile çeker.
     */
    fun getTopicEntriesPager(
        rawTopicUrl: String,
        filterMode: TopicFilterMode = TopicFilterMode.ALL,
        inTopicSearchKeyword: String = "",
        initialPage: Int = 1,
        onPageMetadataLoaded: (TopicPageData) -> Unit
    ): Flow<PagingData<EntryItem>> {
        return Pager(
            config = TopicPagingSource.createOptimizedPagingConfig(),
            initialKey = initialPage.coerceAtLeast(1),
            pagingSourceFactory = {
                TopicPagingSource(
                    initialTopicUrl = rawTopicUrl,
                    filterMode = filterMode,
                    inTopicSearchKeyword = inTopicSearchKeyword,
                    initialPage = initialPage.coerceAtLeast(1),
                    webViewFetchBridge = webViewFetchBridge,
                    eksiParser = eksiParser,
                    onTopicPageLoaded = onPageMetadataLoaded
                )
            }
        ).flow
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
