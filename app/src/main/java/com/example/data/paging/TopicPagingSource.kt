package com.example.data.paging

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.data.model.EntryItem
import com.example.data.model.TopicFilterMode
import com.example.data.model.TopicPageData
import com.example.data.remote.EksiParser
import com.example.network.WebViewFetchBridge

// Bir başlığın sayfalarını Paging 3 ile çeken, kullanıcı mevcut sayfayı okurken bir sonraki
// sayfayı (?p=N+1) arka planda önceden indiren (prefetchDistance optimize edilmiş) yapı.
// Tüm HTML veri çekme işlemleri OkHttp yerine WebViewFetchBridge (görünmez singleton WebView
// içinde fetch(..., { headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'text/html, */*; q=0.01' }, credentials: 'include' })
// ve loadUrl -> document.documentElement.outerHTML fallback) üzerinden gerçekleştirilir.
class TopicPagingSource(
    private val initialTopicUrl: String,
    private val filterMode: TopicFilterMode = TopicFilterMode.ALL,
    private val inTopicSearchKeyword: String = "",
    private val initialPage: Int = 1,
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser,
    private val onTopicPageLoaded: (TopicPageData) -> Unit = {}
) : PagingSource<Int, EntryItem>() {

    /**
     * İlk sayfadan sonra keşfedilen temiz kanonik başlık URL'si (örn: `https://eksisozluk.com/baslik--12345`).
     */
    @Volatile
    private var resolvedCleanTopicUrl: String? = null

    /**
     * Eğer `?a=tracked` isteği boş döndüğü için ana başlığa fallback yapıldıysa `true` olur.
     */
    @Volatile
    private var trackedFallbackTriggered: Boolean = false

    override fun getRefreshKey(state: PagingState<Int, EntryItem>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, EntryItem> {
        val page = params.key ?: initialPage.coerceAtLeast(1)

        return try {
            val pageData = fetchPageWithTrackedFallback(page)

            // Kanonik URL'yi sonraki sayfaların (?p=N+1) arka plan ön yüklemesi (prefetch) için sakla
            if (pageData.cleanTopicUrl.isNotBlank()) {
                resolvedCleanTopicUrl = pageData.cleanTopicUrl
            }

            if (page == initialPage || pageData.usedTrackedFallback) {
                onTopicPageLoaded(pageData)
            }

            // LINKS_ONLY modundaysak yalnızca harici link içeren entry'leri filtrele
            val filteredEntries = if (filterMode == TopicFilterMode.LINKS_ONLY) {
                pageData.entries.filter { it.hasExternalLink }
            } else {
                pageData.entries
            }

            val prevKey = if (page <= 1) null else page - 1
            val nextKey = if (page >= pageData.pageCount || pageData.entries.isEmpty()) {
                null
            } else {
                page + 1
            }

            LoadResult.Page(
                data = filteredEntries,
                prevKey = prevKey,
                nextKey = nextKey
            )
        } catch (e: Throwable) {
            LoadResult.Error(e)
        }
    }

    /**
     * İlgili sayfayı [WebViewFetchBridge] üzerinden indirir ve ayrıştırır.
     * Eğer URL'de `?a=tracked` varsa ve yanıt boş entry listesi döndürdüyse,
     * otomatik olarak parametresiz ana başlık URL'sine fallback yapar.
     */
    private suspend fun fetchPageWithTrackedFallback(page: Int): TopicPageData {
        val baseForRequest = resolvedCleanTopicUrl ?: initialTopicUrl
        val targetUrl = eksiParser.buildTopicPageUrl(
            baseOrRawUrl = baseForRequest,
            page = page,
            filterMode = filterMode,
            searchKeywordInTopic = inTopicSearchKeyword,
            forceStripTracked = trackedFallbackTriggered || (page > 1)
        )

        val primaryHtml = webViewFetchBridge.fetchHtml(
            rawUrl = targetUrl,
            isAjax = false
        )
        val primaryData = eksiParser.parseTopicPage(
            html = primaryHtml,
            requestedUrl = targetUrl,
            fallbackPage = page,
            usedTrackedFallback = trackedFallbackTriggered
        )

        // Olaylar (?a=tracked) sekmesinde okunan başlığa tekrar tıklandığında boş gelme hatası kontrolü
        if (primaryData.entries.isEmpty() && eksiParser.containsTrackedParam(targetUrl)) {
            trackedFallbackTriggered = true
            val cleanFallbackUrl = eksiParser.buildTopicPageUrl(
                baseOrRawUrl = eksiParser.stripTrackedParameters(initialTopicUrl),
                page = page,
                filterMode = filterMode,
                searchKeywordInTopic = inTopicSearchKeyword,
                forceStripTracked = true
            )
            val fallbackHtml = webViewFetchBridge.fetchHtml(
                rawUrl = cleanFallbackUrl,
                isAjax = false
            )
            return eksiParser.parseTopicPage(
                html = fallbackHtml,
                requestedUrl = cleanFallbackUrl,
                fallbackPage = page,
                usedTrackedFallback = true
            )
        }

        return primaryData
    }

    companion object {
        /**
         * Ekşi Sözlük'te her sayfada 10 entry bulunur.
         * `prefetchDistance = 20` (2 tam sayfa) olarak ayarlandığında kullanıcı 1. sayfadaki
         * ilk entry'yi görür görmez 2. ve 3. sayfa arka planda çoktan indirilmiş olur.
         */
        fun createOptimizedPagingConfig(): PagingConfig {
            return PagingConfig(
                pageSize = 10,
                prefetchDistance = 20,
                initialLoadSize = 20,
                maxSize = 200,
                enablePlaceholders = false
            )
        }
    }
}
