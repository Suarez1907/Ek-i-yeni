package com.example.data.repository

import android.net.Uri
import android.webkit.CookieManager
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
import com.example.util.failureUnlessCancelled
import com.example.util.runCatchingUnlessCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Bugün" sekmesi sayfa önbellek girdisi:
 * - [isComplete] `false` iken paralel isteklerden İLK tamamlananın (1 sn içinde) hızlı sonucunu tutar.
 * - [isComplete] `true` olduğunda ikinci paralel isteğin birleştirilmiş nihai sonucunu tutar.
 */
data class BugunPageCacheEntry(
    val items: List<TopicItem>,
    val isComplete: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Ekşi+ veri katmanı:
 * - Tüm HTML ve JSON veri çekme işlemlerini [WebViewFetchBridge] üzerinden gerçekleştirir.
 * - "Bugün" sekmesini sıfır sıralı (sequential) bekleme, paralel maksimum 2 hızlı kaynak (`isAjax = true`),
 *   anında ekrana basma (Progressive Loading) ve Gündem yüklendikten 500ms sonra arka planda
 *   ön yükleme (`prefetchBugunTopics`) ile 0ms gecikmeli olarak sunar.
 */
@Singleton
class EksiRepository @Inject constructor(
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser,
    private val readStateDao: ReadStateDao,
    private val eksinDao: EksinDao
) {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * DEBE ve Dönemsel Enler entry'lerinin beğeni (`data-favorite-count`) ve yazar bilgilerini
     * tekrar tekrar ağdan çekmemek için tutulan bellek içi (in-memory) önbellekler.
     */
    private val entryFavoriteCountCache = ConcurrentHashMap<String, Int>()
    private val entryAuthorCache = ConcurrentHashMap<String, String>()
    private val entryResolvedIdCache = ConcurrentHashMap<String, String>()
    private val entryTopicTitleCache = ConcurrentHashMap<String, String>()

    /**
     * Hafızadaki Gündem 1. sayfa başlıkları (`gundemTopics`) ve hızlı URL seti (`gundemPage1UrlCache`).
     * "Bugün" sekmesi yenilenirken Gündem 1. sayfası ASLA ağdan tekrar çekilmez; doğrudan bu
     * bellek içi listeyle karşılaştırılıp mükerrer başlıklar filtrelenir.
     */
    private val gundemPage1UrlCache: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _gundemTopics = MutableStateFlow<List<TopicItem>>(emptyList())
    val gundemTopics: StateFlow<List<TopicItem>> = _gundemTopics.asStateFlow()

    /**
     * "Bugün" sekmesi için anında ekrana basılan (Progressive Loading) ve sekme geçişlerinde
     * korunan bellek içi başlık durumu (`bugunTopics`), yükleme göstergesi (`isBugunLoading`)
     * ve sayfa bazlı önbellek (`bugunPageCache`).
     */
    private val bugunPageCache = ConcurrentHashMap<Int, BugunPageCacheEntry>()
    private val inFlightBugunPageJobs = ConcurrentHashMap<Int, Job>()
    private val _bugunTopics = MutableStateFlow<List<TopicItem>>(emptyList())
    val bugunTopics: StateFlow<List<TopicItem>> = _bugunTopics.asStateFlow()

    private val _isBugunLoading = MutableStateFlow(false)
    val isBugunLoading: StateFlow<Boolean> = _isBugunLoading.asStateFlow()

    /**
     * Giriş yapılmadığında Bugün listesi gündem/kanal başlıklarından türetilir.
     * UI bu durumda listeyi "yaklaşık" diye etiketler.
     */
    private val _bugunIsApproximate = MutableStateFlow(false)
    val bugunIsApproximate: StateFlow<Boolean> = _bugunIsApproximate.asStateFlow()

    private val bugunGeneration = AtomicInteger(0)

    @Volatile
    private var activeBugunPagingSource: BugunTopicsPagingSource? = null

    @Volatile
    private var gundemFirstPageLoadedListener: (() -> Unit)? = null

    @Volatile
    private var explicitUserLoggedInFlag: Boolean? = null

    /**
     * Gündem, Bugün, Kanallar ve Olaylar sekmelerinden yüklenen başlıkların bellek içi önbelleği.
     * Arama kutusuna yazmaya başlandığında 0 milisaniye gecikmeyle anlık başlık önerisi üretmek için kullanılır.
     */
    private val cachedKnownTopics = ConcurrentHashMap<String, TopicItem>()

    /**
     * Gündem 1. sayfası yüklendiğinde ViewModel'in 500ms sonra arka planda `prefetchBugunTopics()`
     * başlatabilmesi için dinleyici kaydeder.
     */
    fun setOnGundemFirstPageLoadedListener(listener: () -> Unit) {
        gundemFirstPageLoadedListener = listener
        if (_gundemTopics.value.isNotEmpty()) {
            listener.invoke()
        }
    }

    fun setUserLoggedInState(isLoggedIn: Boolean) {
        explicitUserLoggedInFlag = isLoggedIn
    }

    /**
     * Kullanıcının Ekşi Sözlük oturumu açıp açmadığını kontrol eder.
     * Giriş yapılmışsa `/basliklar/bugun?p={page}` tek istekle (`isAjax = true`) çekilir.
     */
    fun isUserLoggedIn(baseDomain: String): Boolean {
        explicitUserLoggedInFlag?.let { if (it) return true }
        return try {
            if (!WebViewCookieJar.isHardwareWebViewSupported()) return false
            val rawCookie = CookieManager.getInstance().getCookie(baseDomain).orEmpty()
            if (rawCookie.isBlank()) return false
            rawCookie.split(";").any { segment ->
                val name = segment.substringBefore('=').trim()
                val value = segment.substringAfter('=', "").trim()
                value.isNotEmpty() && (
                    name.equals("a", ignoreCase = true) ||
                        name.equals(".ASPXAUTH", ignoreCase = true) ||
                        name.equals("iq", ignoreCase = true)
                    )
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun clearGundemCache() {
        gundemPage1UrlCache.clear()
        _gundemTopics.value = emptyList()
    }

    /**
     * Kullanıcı özellikle "Yenile" veya "Pull-to-Refresh" yaptığında "Bugün" önbelleğini temizler.
     * Normal sekme geçişlerinde bu metot ÇAĞRILMAZ; böylece hafızadaki "Bugün" listesi 0ms'de açılır.
     */
    fun clearBugunCache() {
        bugunGeneration.incrementAndGet()
        inFlightBugunPageJobs.values.forEach { it.cancel() }
        inFlightBugunPageJobs.clear()
        bugunPageCache.clear()
        _bugunTopics.value = emptyList()
        _bugunIsApproximate.value = false
        _isBugunLoading.value = false
    }

    /**
     * `EksinApplication.onTrimMemory` çağrıldığında bellek içi önbellekleri güvenli şekilde temizler.
     */
    fun clearMemoryCaches() {
        entryFavoriteCountCache.clear()
        entryAuthorCache.clear()
        entryResolvedIdCache.clear()
        entryTopicTitleCache.clear()
        cachedKnownTopics.clear()
    }

    fun getCachedBugunPage(page: Int): BugunPageCacheEntry? {
        return bugunPageCache[page]
    }

    fun hasCachedBugunTopics(): Boolean {
        return bugunPageCache[1]?.items?.isNotEmpty() == true || _bugunTopics.value.isNotEmpty()
    }

    internal fun registerActiveBugunPagingSource(source: BugunTopicsPagingSource) {
        activeBugunPagingSource = source
    }

    /**
     * 3. ÖZELLİK: ARKA PLANDA ÖN YÜKLEME (PREFETCH) VE ÖNBELLEK:
     * Uygulama ilk açıldığında Gündem yüklendikten hemen 500ms sonra arka planda sessizce
     * "Bugün" listesini hazırlar. Kullanıcı "Bugün" sekmesine tıkladığında 0ms bekleme süresiyle
     * anında hazır listeyi görür.
     */
    suspend fun prefetchBugunTopics(forceRefresh: Boolean = false): List<TopicItem> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                val existing = bugunPageCache[1]
                if (existing != null && existing.items.isNotEmpty()) {
                    if (_bugunTopics.value.isEmpty()) {
                        _bugunTopics.value = existing.items
                    }
                    return@withContext existing.items
                }
            } else {
                clearBugunCache()
            }

            loadBugunPageProgressive(
                page = 1,
                forceRefresh = forceRefresh
            )
        }

    /**
     * 1 & 2. ÖZELLİK: "BUGÜN" SEKMESİ PARALEL HIZLANDIRMA VE ANINDA EKRANA BASMA (PROGRESSIVE LOADING):
     * - Sıralı (sequential) çoklu istekleri tamamen kaldırır.
     * - Tüm isteklerde hafif AJAX modunu (`isAjax = true`) kullanır.
     * - Zaten hafızada (`gundemTopics` / `gundemPage1UrlCache`) var olan Gündem 1. sayfa başlıklarını
     *   tekrar ağdan ÇEKMEZ; doğrudan hafızadaki listeyle karşılaştırıp mükerrerleri filtreler.
     * - Kullanıcı giriş yapmışsa tek bir istekle `https://eksisozluk.com/basliklar/bugun?p={page}` çeker.
     * - Anonim modda 4-5 kanalı sırayla beklemek yerine `coroutineScope` ve `async` ile EN FAZLA 2
     *   hızlı kaynağı (Gündem'in `page + 1` sayfası `/basliklar/gundem?p=2` ve aktif ana kanal
     *   `/basliklar/kanal/spor` veya `/basliklar/kanal/siyaset`) TAMAMEN PARALEL çeker.
     * - Paralel isteklerden İLK hangisi tamamlanırsa hiç beklemeden `bugunTopics` state'ini ve
     *   `bugunPageCache` önbelleğini günceller, `isBugunLoading`'i kapatır ve ilk listeyi anında döndürür.
     * - İkinci paralel istek tamamlandığında listeyi arka planda sessizce zenginleştirir.
     */
    suspend fun loadBugunPageProgressive(
        page: Int,
        forceRefresh: Boolean = false
    ): List<TopicItem> = withContext(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)

        if (!forceRefresh) {
            val cached = bugunPageCache[safePage]
            if (cached != null && cached.items.isNotEmpty()) {
                if (safePage == 1 && _bugunTopics.value.isEmpty()) {
                    _bugunTopics.value = cached.items
                }
                return@withContext cached.items
            }
        }

        if (safePage == 1) {
            _isBugunLoading.value = true
        }

        val baseDomain = webViewFetchBridge.getActiveBaseDomain().ifBlank {
            WebViewCookieJar.DEFAULT_BASE_URL
        }.trimEnd('/')

        val generation = bugunGeneration.get()
        fun stillCurrent(): Boolean = bugunGeneration.get() == generation

        try {
            // 1b) Kullanıcı giriş yapmışsa doğrudan TEK BİR İSTEKLE `/basliklar/bugun?p={page}` (`isAjax = true`) çek
            if (isUserLoggedIn(baseDomain)) {
                val bugunUrl = "$baseDomain/basliklar/bugun?p=$safePage&_=${System.currentTimeMillis()}"
                val bugunHtml = runCatchingUnlessCancelled {
                    webViewFetchBridge.fetchHtml(rawUrl = bugunUrl, isAjax = true)
                }.getOrDefault("")
                val parsedBugun = withContext(Dispatchers.Default) {
                    eksiParser.parseLeftFrameTopics(bugunHtml, isTrackedSection = false)
                }
                val filteredBugun = filterAgainstInMemoryGundem(parsedBugun)
                if (filteredBugun.isNotEmpty() && stillCurrent()) {
                    cacheKnownTopics(filteredBugun)
                    bugunPageCache[safePage] = BugunPageCacheEntry(
                        items = filteredBugun,
                        isComplete = true
                    )
                    _bugunIsApproximate.value = false
                    if (safePage == 1) {
                        _bugunTopics.value = filteredBugun
                        _isBugunLoading.value = false
                    }
                    return@withContext filteredBugun
                }
            }

            // 1c & 2) Anonim modda (veya `/basliklar/bugun` Gündem ile aynı döndüğünde):
            // EN FAZLA 2 hızlı kaynağı (`isAjax = true`) TAMAMEN PARALEL başlat.
            // İlk tamamlanan kaynağı ANINDA ekrana bas, ikinci tamamlandığında sessizce zenginleştir!
            val firstReadyDeferred = CompletableDeferred<List<TopicItem>>()

            val primaryFastChannel = when ((safePage - 1) % 4) {
                0 -> EksiChannel.SPOR
                1 -> EksiChannel.SIYASET
                2 -> EksiChannel.EKONOMI
                else -> EksiChannel.TEKNOLOJI
            }
            val channelPage = ((safePage - 1) / 4) + 1
            val deeperAgendaPage = (safePage + 1).coerceAtMost(15)

            val enrichmentJob = repositoryScope.launch {
                try {
                val resultChannel = Channel<List<TopicItem>>(capacity = 2)

                // Paralel Kaynak 1: Gündem'in bir sonraki sayfası (`/basliklar/gundem?p=2` vb., `isAjax = true`)
                val sourceADeferred = async(Dispatchers.IO) {
                    val deeperUrl = "$baseDomain/basliklar/gundem?p=$deeperAgendaPage&_=${System.currentTimeMillis()}"
                    val html = runCatchingUnlessCancelled {
                        webViewFetchBridge.fetchHtml(rawUrl = deeperUrl, isAjax = true)
                    }.getOrDefault("")
                    val parsed = withContext(Dispatchers.Default) {
                        eksiParser.parseLeftFrameTopics(html, isTrackedSection = false)
                    }
                    val filtered = filterAgainstInMemoryGundem(parsed)
                    resultChannel.trySend(filtered)
                    filtered
                }

                // Paralel Kaynak 2: Aktif ana kanal (`/basliklar/kanal/spor` veya `/basliklar/kanal/siyaset`, `isAjax = true`)
                val sourceBDeferred = async(Dispatchers.IO) {
                    val channelUrl = eksiParser.buildChannelTopicsUrl(
                        baseDomain = baseDomain,
                        channel = primaryFastChannel,
                        page = channelPage,
                        useRawTurkishSlug = false
                    )
                    val html = runCatchingUnlessCancelled {
                        webViewFetchBridge.fetchHtml(rawUrl = channelUrl, isAjax = true)
                    }.getOrDefault("")
                    val parsed = withContext(Dispatchers.Default) {
                        eksiParser.parseChannelTopics(html)
                    }
                    val filtered = filterAgainstInMemoryGundem(parsed)
                    resultChannel.trySend(filtered)
                    filtered
                }

                // 2. KURAL (ANINDA EKRANA BASMA - PROGRESSIVE LOADING):
                // Paralel 2 istekten İLK hangisi tamamlanırsa hiç beklemeden UI state'ini güncelle!
                val firstBatch = resultChannel.receive()
                if (firstBatch.isNotEmpty() && stillCurrent()) {
                    cacheKnownTopics(firstBatch)
                    bugunPageCache[safePage] = BugunPageCacheEntry(
                        items = firstBatch,
                        isComplete = false
                    )
                    _bugunIsApproximate.value = true
                    if (safePage == 1) {
                        _bugunTopics.value = firstBatch
                        _isBugunLoading.value = false
                    }
                    if (!firstReadyDeferred.isCompleted) {
                        firstReadyDeferred.complete(firstBatch)
                    }
                }

                // İkinci paralel istek tamamlandığında listeyi sessizce zenginleştir
                val secondBatch = resultChannel.receive()
                val mergedItems = mergeAndDeduplicateBugunTopics(
                    batchA = sourceADeferred.await(),
                    batchB = sourceBDeferred.await()
                )

                if (mergedItems.isNotEmpty() && stillCurrent()) {
                    cacheKnownTopics(mergedItems)
                    val hadInitialEmission = firstReadyDeferred.isCompleted && firstBatch.isNotEmpty()
                    bugunPageCache[safePage] = BugunPageCacheEntry(
                        items = mergedItems,
                        isComplete = true
                    )
                    _bugunIsApproximate.value = true
                    if (safePage == 1) {
                        _bugunTopics.value = mergedItems
                        _isBugunLoading.value = false
                    }

                    if (!firstReadyDeferred.isCompleted) {
                        firstReadyDeferred.complete(mergedItems)
                    } else if (hadInitialEmission && mergedItems.size > firstBatch.size) {
                        activeBugunPagingSource?.invalidate()
                    }
                } else if (stillCurrent()) {
                    if (safePage == 1) {
                        _isBugunLoading.value = false
                    }
                    if (!firstReadyDeferred.isCompleted) {
                        firstReadyDeferred.complete(emptyList())
                    }
                }
                } catch (cancelled: CancellationException) {
                    if (!firstReadyDeferred.isCompleted) {
                        firstReadyDeferred.cancel(cancelled)
                    }
                    throw cancelled
                } catch (_: Throwable) {
                    if (!firstReadyDeferred.isCompleted) {
                        firstReadyDeferred.complete(emptyList())
                    }
                } finally {
                    if (!firstReadyDeferred.isCompleted) {
                        firstReadyDeferred.complete(emptyList())
                    }
                    if (stillCurrent() && safePage == 1) {
                        _isBugunLoading.value = false
                    }
                }
            }

            inFlightBugunPageJobs[safePage] = enrichmentJob
            enrichmentJob.invokeOnCompletion {
                inFlightBugunPageJobs.remove(safePage, enrichmentJob)
                if (!firstReadyDeferred.isCompleted) {
                    firstReadyDeferred.complete(emptyList())
                }
            }

            try {
                firstReadyDeferred.await()
            } catch (cancelled: CancellationException) {
                if (stillCurrent() && safePage == 1) {
                    _isBugunLoading.value = false
                }
                throw cancelled
            }
        } catch (cancelled: CancellationException) {
            if (stillCurrent() && safePage == 1) {
                _isBugunLoading.value = false
            }
            throw cancelled
        } catch (_: Throwable) {
            if (stillCurrent() && safePage == 1) {
                _isBugunLoading.value = false
            }
            bugunPageCache[safePage]?.items ?: emptyList()
        }
    }

    /**
     * 1d) Zaten hafızada (`gundemTopics` / `gundemPage1UrlCache`) var olan Gündem 1. sayfa
     * başlıklarını ağdan tekrar çekmeden doğrudan hafızadaki listeyle karşılaştırıp mükerrerleri ayıklar.
     */
    private fun filterAgainstInMemoryGundem(candidates: List<TopicItem>): List<TopicItem> {
        if (candidates.isEmpty()) return emptyList()
        val inMemoryGundemUrls = gundemPage1UrlCache
        val inMemoryGundemTitles = _gundemTopics.value
            .mapTo(HashSet()) { it.title.trim().lowercase() }

        val filtered = candidates.filterNot { item ->
            val cleanUrlKey = item.cleanUrl.trim().lowercase()
            val titleKey = item.title.trim().lowercase()
            inMemoryGundemUrls.contains(cleanUrlKey) || inMemoryGundemTitles.contains(titleKey)
        }
            .distinctBy { it.cleanUrl.trim().lowercase() }
            .distinctBy { it.title.trim().lowercase() }

        return filtered.ifEmpty {
            candidates
                .distinctBy { it.cleanUrl.trim().lowercase() }
                .distinctBy { it.title.trim().lowercase() }
        }
    }

    private fun mergeAndDeduplicateBugunTopics(
        batchA: List<TopicItem>,
        batchB: List<TopicItem>
    ): List<TopicItem> {
        if (batchA.isEmpty()) return batchB
        if (batchB.isEmpty()) return batchA

        val interleaved = ArrayList<TopicItem>(batchA.size + batchB.size)
        val maxLen = maxOf(batchA.size, batchB.size)
        for (i in 0 until maxLen) {
            if (i < batchB.size) interleaved.add(batchB[i])
            if (i < batchA.size) interleaved.add(batchA[i])
        }

        return filterAgainstInMemoryGundem(interleaved)
    }

    fun cacheKnownTopics(topics: List<TopicItem>) {
        for (topic in topics) {
            val key = topic.title.trim().lowercase()
            if (key.isNotBlank()) {
                val existing = cachedKnownTopics[key]
                if (existing == null || topic.entryCount >= existing.entryCount) {
                    cachedKnownTopics[key] = topic
                }
            }
        }
    }

    /**
     * Arama kutusuna yazmaya başlandığında (en az 2 karakter) hafızadaki mevcut Gündem/Bugün/Kanal
     * başlıkları içinden eşleşenleri 0 milisaniye gecikmeyle döndürür.
     */
    fun getInstantLocalTopicMatches(query: String): List<TopicItem> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.length < 2) return emptyList()
        return cachedKnownTopics.values
            .filter { it.title.lowercase().contains(cleanQuery) }
            .sortedWith(
                compareByDescending<TopicItem> { it.title.lowercase().startsWith(cleanQuery) }
                    .thenByDescending { it.entryCount }
            )
            .distinctBy { it.title.trim().lowercase() }
            .take(8)
    }

    /**
     * `https://eksisozluk.com/autocomplete/query?q=${Uri.encode(query)}` adresinden canlı
     * `Titles` (Başlık) ve `Nicks` (`@yazar`) önerilerini çeker; hata veya boş yanıt durumunda
     * `/basliklar/ara?SearchForm.Keywords=${Uri.encode(query)}` sonuçlarına fallback yapar.
     */
    suspend fun fetchAutocompleteSuggestions(
        query: String
    ): Pair<List<TopicItem>, List<String>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) return@withContext emptyList<TopicItem>() to emptyList()

        val baseDomain = webViewFetchBridge.getActiveBaseDomain().ifBlank {
            WebViewCookieJar.DEFAULT_BASE_URL
        }.trimEnd('/')

        val localMatches = getInstantLocalTopicMatches(cleanQuery)

        try {
            val autocompleteUrl = "$baseDomain/autocomplete/query?q=${Uri.encode(cleanQuery)}&_=${System.currentTimeMillis()}"
            val rawResponse = runCatchingUnlessCancelled {
                webViewFetchBridge.fetchHtml(rawUrl = autocompleteUrl, isAjax = true)
            }.getOrDefault("")

            var (parsedTitles, parsedNicks) = withContext(Dispatchers.Default) {
                eksiParser.parseAutocompleteResponse(
                    rawResponse = rawResponse,
                    baseDomain = baseDomain
                )
            }

            if (parsedTitles.isEmpty() && parsedNicks.isEmpty()) {
                val searchFallbackUrl = eksiParser.buildSearchUrl(
                    filter = SearchFilter(keywords = cleanQuery),
                    page = 1
                )
                val searchHtml = runCatchingUnlessCancelled {
                    webViewFetchBridge.fetchHtml(rawUrl = searchFallbackUrl, isAjax = true)
                }.getOrDefault("")
                val searchParsed = withContext(Dispatchers.Default) {
                    eksiParser.parseSearchResults(
                        html = searchHtml,
                        query = cleanQuery,
                        requestedPage = 1
                    )
                }
                parsedTitles = searchParsed.topics
            }

            val enrichedRemoteTitles = parsedTitles.map { remoteTopic ->
                val cached = cachedKnownTopics[remoteTopic.title.trim().lowercase()]
                cached ?: remoteTopic
            }

            val mergedTitles = (localMatches + enrichedRemoteTitles)
                .distinctBy { it.title.trim().lowercase() }
                .take(12)

            cacheKnownTopics(mergedTitles)
            mergedTitles to parsedNicks.take(8)
        } catch (_: Throwable) {
            localMatches to emptyList()
        }
    }

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
        if (eksinDao.isEntryBookmarkedNow(entry.id)) {
            eksinDao.deleteBookmarkedEntry(entry.id)
            return
        }
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
     * Gündem 1. sayfası yüklendiği anda hafızadaki `gundemTopics` güncellenir ve
     * 500ms sonra arka planda "Bugün" ön yüklemesi (`prefetchBugunTopics`) tetiklenir.
     */
    fun getGundemTopicsPager(): Flow<PagingData<TopicItem>> {
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
                    onTopicsLoaded = { items, page ->
                        cacheKnownTopics(items)
                        if (page == 1 && items.isNotEmpty()) {
                            _gundemTopics.value = items
                            gundemFirstPageLoadedListener?.invoke()
                        }
                    }
                )
            }
        ).flow
    }

    /**
     * Bugün sekmesini (`BugunTopicsPagingSource`) Gündem'den bağımsız ve
     * önbellek + paralel Progressive Loading destekli Pager ile sunar.
     */
    fun getBugunTopicsPager(): Flow<PagingData<TopicItem>> {
        return Pager(
            config = PagingConfig(
                pageSize = 25,
                prefetchDistance = 20,
                initialLoadSize = 25,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                BugunTopicsPagingSource(
                    repository = this
                ).also { source ->
                    registerActiveBugunPagingSource(source)
                }
            }
        ).flow
    }

    /**
     * Olaylar (Takip Edilen Başlıklar) sekmesini bağımsız Pager ile çeker.
     */
    fun getOlaylarTopicsPager(): Flow<PagingData<TopicItem>> {
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
                    onTopicsLoaded = { items, _ -> cacheKnownTopics(items) }
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
                    eksiParser = eksiParser,
                    onTopicsLoaded = { cacheKnownTopics(it) }
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
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Ağ hatası durumunda mevcut öğeyi koru
        }
        item
    }

    /**
     * 3a. GELİŞMİŞ DEBE:
     * Sadece `https://eksisozluk.com/debe` (ve `/basliklar/debe`) gerçek listesini çeker.
     */
    suspend fun fetchDebeItems(): Result<List<RankedTopicOrEntryItem>> = withContext(Dispatchers.IO) {
        try {
            val baseDomain = webViewFetchBridge.getActiveBaseDomain().ifBlank {
                WebViewCookieJar.DEFAULT_BASE_URL
            }.trimEnd('/')

            val fullPageUrl = "$baseDomain/debe?_=${System.currentTimeMillis()}"
            val fullPageHtml = runCatchingUnlessCancelled {
                webViewFetchBridge.fetchHtml(rawUrl = fullPageUrl, isAjax = false)
            }.getOrDefault("")
            val parsedFull = withContext(Dispatchers.Default) {
                eksiParser.parseDebeEntriesList(fullPageHtml)
            }

            val ajaxUrl = "$baseDomain/debe?_=${System.currentTimeMillis()}"
            val ajaxHtml = runCatchingUnlessCancelled {
                webViewFetchBridge.fetchHtml(rawUrl = ajaxUrl, isAjax = true)
            }.getOrDefault("")
            val parsedAjax = withContext(Dispatchers.Default) {
                eksiParser.parseDebeEntriesList(ajaxHtml)
            }

            val fullEntryCount = parsedFull.count { !it.entryId.isNullOrBlank() }
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

            val fallbackUrl = "$baseDomain/basliklar/debe?_=${System.currentTimeMillis()}"
            val fallbackHtml = webViewFetchBridge.fetchHtml(rawUrl = fallbackUrl, isAjax = true)
            val parsedFallback = withContext(Dispatchers.Default) {
                eksiParser.parseDebeEntriesList(fallbackHtml)
            }
            Result.success(parsedFallback.map { applyCachedFavoriteMetadata(it) })
        } catch (e: Throwable) {
            failureUnlessCancelled(e)
        }
    }

    /**
     * 3b. DÖNEMSEL ENLER (Önceki Gün / Önceki Hafta / Önceki Ay):
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

            val statsPath = if (isEntriesMode) period.entriesStatsPath else period.topicsStatsPath
            val statsUrl = "$baseDomain$statsPath"

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

            Result.success(emptyList())
        } catch (e: Throwable) {
            failureUnlessCancelled(e)
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
                    eksiParser = eksiParser,
                    onTopicsLoaded = { cacheKnownTopics(it) }
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
                forceStripTracked = trackedFallbackAlreadyActive || (safePage > 1),
                forceAllEntriesFallback = todayFallbackAlreadyActive
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

            if (resultPageData.entries.isEmpty() && eksiParser.containsTrackedParam(targetUrl)) {
                val cleanFallbackUrl = eksiParser.buildTopicPageUrl(
                    baseOrRawUrl = eksiParser.stripTrackedParameters(initialTopicUrl),
                    page = safePage,
                    filterMode = TopicFilterMode.ALL,
                    searchKeywordInTopic = inTopicSearchKeyword,
                    forceStripTracked = true,
                    forceAllEntriesFallback = true
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

            if (safePage == 1 &&
                resultPageData.entries.isEmpty() &&
                (effectiveFilterMode == TopicFilterMode.TODAY_POPULAR || eksiParser.containsTodayPopularParam(targetUrl))
            ) {
                val allEntriesFallbackUrl = eksiParser.buildTopicPageUrl(
                    baseOrRawUrl = eksiParser.stripTrackedParameters(baseForRequest),
                    page = 1,
                    filterMode = TopicFilterMode.ALL,
                    searchKeywordInTopic = inTopicSearchKeyword,
                    forceStripTracked = true,
                    forceAllEntriesFallback = true
                )
                val fallbackHtml = webViewFetchBridge.fetchHtml(
                    rawUrl = allEntriesFallbackUrl,
                    isAjax = false
                )
                resultPageData = withContext(Dispatchers.Default) {
                    eksiParser.parseTopicPage(
                        html = fallbackHtml,
                        requestedUrl = allEntriesFallbackUrl,
                        fallbackPage = 1,
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

            for (entry in finalEntries) {
                if (entry.id.isNotBlank() && entry.favoriteCount >= 0) {
                    entryFavoriteCountCache["entry:${entry.id}"] = entry.favoriteCount
                    if (entry.author.isNotBlank()) {
                        entryAuthorCache["entry:${entry.id}"] = entry.author
                    }
                }
            }

            val finalPageCount = resultPageData.pageCount.coerceAtLeast(safePage)
            val fromArchive = webViewFetchBridge.isUrlServedFromWayback(targetUrl) ||
                webViewFetchBridge.isUrlServedFromWayback(resultPageData.cleanTopicUrl)
            Result.success(
                resultPageData.copy(
                    currentPage = safePage,
                    pageCount = finalPageCount,
                    entries = finalEntries,
                    isFromWaybackArchive = fromArchive
                )
            )
        } catch (e: Throwable) {
            failureUnlessCancelled(e)
        }
    }

    /**
     * `BkzPreviewBottomSheet` için `(bkz: ...)` veya `*` tıklandığında ilgili başlığın
     * en popüler (`?a=nice`) veya ilk 5 entry'sini [WebViewFetchBridge] üzerinden hızla çeker.
     */
    suspend fun fetchBkzPreview(bkzQuery: String): Result<TopicPageData> = withContext(Dispatchers.IO) {
        try {
            val cleanQuery = bkzQuery.trim()
            val encoded = Uri.encode(cleanQuery)
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
            failureUnlessCancelled(e)
        }
    }
}

class LeftFramePagingSource(
    private val section: LeftFrameSection,
    private val webViewFetchBridge: WebViewFetchBridge,
    private val eksiParser: EksiParser,
    private val gundemPage1UrlCache: MutableSet<String>,
    private val onTopicsLoaded: (List<TopicItem>, Int) -> Unit = { _, _ -> }
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
                "$baseDomain${section.endpointPath}?_=${System.currentTimeMillis()}"
            } else {
                "$baseDomain${section.endpointPath}?p=$page&_=${System.currentTimeMillis()}"
            }

            val html = webViewFetchBridge.fetchHtml(
                rawUrl = url,
                isAjax = true
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
                    isAjax = false
                )
                items = withContext(Dispatchers.Default) {
                    eksiParser.parseLeftFrameTopics(
                        html = fullPageHtml,
                        isTrackedSection = (section == LeftFrameSection.OLAYLAR)
                    )
                }
            }

            if (section == LeftFrameSection.GUNDEM && page == 1 && items.isNotEmpty()) {
                gundemPage1UrlCache.clear()
                items.forEach { topic ->
                    gundemPage1UrlCache.add(topic.cleanUrl.trim().lowercase())
                }
            }

            if (items.isNotEmpty()) {
                onTopicsLoaded(items, page)
            }

            val canPaginate = (section == LeftFrameSection.GUNDEM || section == LeftFrameSection.BUGUN)
            val nextKey = if (items.isEmpty() || !canPaginate || page >= 15) null else page + 1

            LoadResult.Page(
                data = items.distinctBy { it.cleanUrl.ifBlank { it.id } },
                prevKey = if (page == 1) null else page - 1,
                nextKey = nextKey
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            LoadResult.Error(e)
        }
    }
}

/**
 * Hızlandırılmış "Bugün" PagingSource:
 * 1. Sayfa önbellekte (`bugunPageCache`) hazırsa (ör. Gündem'den 500ms sonra arka planda
 *    `prefetchBugunTopics()` ile hazırlanmışsa veya sekme değiştirilmişse) 0ms'de ağa çıkmadan döndürür.
 * 2. Önbellekte yoksa [EksiRepository.loadBugunPageProgressive] ile paralel 2 hızlı kaynaktan
 *    ilk tamamlananı anında ekrana basar, ikinci tamamlandığında sessizce zenginleştirir.
 */
class BugunTopicsPagingSource(
    private val repository: EksiRepository
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
            // 3. KURAL: Önbellekte hazır "Bugün" sayfası varsa 0 milisaniye bekleme ile anında döndür!
            val cachedEntry = repository.getCachedBugunPage(page)
            val items = if (cachedEntry != null && cachedEntry.items.isNotEmpty()) {
                cachedEntry.items
            } else {
                repository.loadBugunPageProgressive(
                    page = page,
                    forceRefresh = false
                )
            }

            val nextKey = if (items.isEmpty() || page >= 15) null else page + 1

            LoadResult.Page(
                data = items.distinctBy { it.cleanUrl.ifBlank { it.id } },
                prevKey = if (page == 1) null else page - 1,
                nextKey = nextKey
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
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
    private val eksiParser: EksiParser,
    private val onTopicsLoaded: (List<TopicItem>) -> Unit = {}
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

            if (items.isNotEmpty()) {
                onTopicsLoaded(items)
            }

            val nextKey = if (items.isEmpty() || page >= 15) null else page + 1

            LoadResult.Page(
                data = items.distinctBy { it.cleanUrl.ifBlank { it.id } },
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
    private val onTopicsLoaded: (List<TopicItem>) -> Unit = {}
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

            if (parsedPage.topics.isNotEmpty()) {
                onTopicsLoaded(parsedPage.topics)
            }

            val nextKey = if (parsedPage.topics.isEmpty() || page >= parsedPage.pageCount) {
                null
            } else {
                page + 1
            }

            LoadResult.Page(
                data = parsedPage.topics.distinctBy { it.cleanUrl.ifBlank { it.id } },
                prevKey = if (page == 1) null else page - 1,
                nextKey = nextKey
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            LoadResult.Error(e)
        }
    }
}
