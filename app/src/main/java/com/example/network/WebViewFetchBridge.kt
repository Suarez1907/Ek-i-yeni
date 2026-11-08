package com.example.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.util.PerfLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * İçeriğin canlı Ekşi Sözlük sunucusundan mı yoksa Wayback Machine (`web.archive.org`)
 * arşiv yedeğinden mi geldiğini belirtir.
 */
enum class ContentSource {
    LIVE,
    WAYBACK_ARCHIVE
}

/**
 * JavaScript `fetch()`, `OkHttp` veya `loadUrl` (`document.documentElement.outerHTML`) sonucunu temsil eder.
 */
data class FetchBridgeResult(
    val statusCode: Int,
    val finalUrl: String,
    val html: String,
    val hasValidEksiDom: Boolean,
    val isCloudflareChallenge: Boolean
)

private data class CachedHtmlEntry(
    val html: String,
    val contentSource: ContentSource,
    val timestampMs: Long
)

/**
 * Gerçek zamanlı (Real-Time) Ekşi Sözlük HTML veri çekme köprüsü.
 *
 * Çalışma Mimarisi:
 * 1. Birebir senkronize WebView `User-Agent` ve `CookieManager` (`WebViewCookieJar`) ile önce
 *    hızlı doğrudan bağlantıyı ve/veya gerçek Chromium TLS parmak izine sahip arka plan
 *    Singleton `WebView` içinden `fetch(url, { headers: { 'X-Requested-With': 'XMLHttpRequest' }, credentials: 'include' })`
 *    çağrısını çalıştırır.
 * 2. Eşzamanlı aynı URL isteklerini (`inFlightRequests`) tek bir ağ çağrısında birleştirir (deduplication),
 *    başarılı yanıtları 90 saniye TTL önbellekte tutar ve Wayback Machine negatif yanıtlarını 5 dakika
 *    boyunca tekrar denemez.
 * 3. Arka plan `WebView` 5 dakika boyunca kullanılmazsa ana iş parçacığında `destroy()` edilerek
 *    bellekten tamamen temizlenir (hibernation) ve sonraki ihtiyaçta yeniden oluşturulur.
 * 4. Cold start performansını korumak için constructor veya `init` bloğunda `SharedPreferences`,
 *    `CookieManager` veya `WebView` başlatmaz; tamamı ilk erişimde tembel (lazy) olarak yüklenir.
 */
@Singleton
class WebViewFetchBridge @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val userAgentProvider: UserAgentProvider,
    private val cookieJar: WebViewCookieJar,
    private val cloudflareManager: CloudflareChallengeManager
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadUrlMutex = Mutex()
    private val originInitMutex = Mutex()

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val directHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(UserAgentInterceptor(userAgentProvider))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val pendingFetchRequests =
        ConcurrentHashMap<String, CompletableDeferred<FetchBridgeResult>>()

    private val inFlightRequests =
        ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Volatile
    private var pendingPageLoadDeferred: CompletableDeferred<FetchBridgeResult>? = null

    @Volatile
    private var pendingPageLoadRequestId: String = ""

    @Volatile
    private var backgroundWebView: WebView? = null

    @Volatile
    private var initializedOriginDomain: String = ""

    @Volatile
    private var activeBaseDomainInternal: String? = null

    private val domVerifiedState = AtomicBoolean(false)
    private val domVerifiedInitialized = AtomicBoolean(false)

    @Volatile
    private var lastVerifiedHomeHtml: String? = null

    private val _lastContentSource = MutableStateFlow(ContentSource.LIVE)
    val lastContentSource: StateFlow<ContentSource> = _lastContentSource.asStateFlow()

    private val urlContentSourceLock = Any()
    private val urlContentSourceMap = object : LruCache<String, ContentSource>(1000) {}

    /**
     * 90 saniye TTL'li canlı yanıt önbelleği (maksimum ~6 MB karakter verisi).
     */
    private val responseTtlCacheLock = Any()
    private val responseTtlCache = object : LruCache<String, CachedHtmlEntry>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: CachedHtmlEntry): Int {
            return (value.html.length * 2).coerceAtLeast(1)
        }
    }

    /**
     * 8 MB üst sınırlı (`value.length * 2` bayt) Wayback Machine HTML önbelleği.
     */
    private val mirrorCacheLock = Any()
    private val mirrorHtmlCache = object : LruCache<String, String>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: String): Int {
            return (value.length * 2).coerceAtLeast(1)
        }
    }

    /**
     * Wayback Machine (`web.archive.org`) üzerinde bulunamayan (`null` dönen) URL'leri
     * 5 dakika (`WAYBACK_NEGATIVE_TTL_MS`) boyunca tekrar denememek için tutulan negatif önbellek.
     */
    private val waybackNegativeLock = Any()
    private val waybackNegativeCache = LruCache<String, Long>(500)

    @Volatile
    private var cachedRealGundemHtml: String? = null

    @Volatile
    private var cachedRealDebeHtml: String? = null

    private val idleWebViewCleanupRunnable = Runnable {
        if (pendingFetchRequests.isEmpty() && pendingPageLoadDeferred == null) {
            destroyBackgroundWebViewOnMain()
        } else {
            scheduleIdleWebViewHibernation()
        }
    }

    private fun scheduleIdleWebViewHibernation() {
        mainHandler.removeCallbacks(idleWebViewCleanupRunnable)
        mainHandler.postDelayed(idleWebViewCleanupRunnable, WEBVIEW_IDLE_TIMEOUT_MS)
    }

    private fun destroyBackgroundWebViewOnMain() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val view = backgroundWebView
            backgroundWebView = null
            initializedOriginDomain = ""
            if (view != null) {
                try {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                } catch (_: Throwable) {
                }
            }
        } else {
            mainHandler.post { destroyBackgroundWebViewOnMain() }
        }
    }

    /**
     * Sistem `onTrimMemory` bildiriminde tüm bellek içi önbellekleri temizler.
     */
    fun clearMemoryCaches() {
        synchronized(mirrorCacheLock) {
            mirrorHtmlCache.evictAll()
        }
        synchronized(responseTtlCacheLock) {
            responseTtlCache.evictAll()
        }
        synchronized(waybackNegativeLock) {
            waybackNegativeCache.evictAll()
        }
        synchronized(urlContentSourceLock) {
            urlContentSourceMap.evictAll()
        }
        cachedRealGundemHtml = null
        cachedRealDebeHtml = null
        lastVerifiedHomeHtml = null
    }

    private fun normalizeSourceKey(url: String): String {
        return url.trim()
            .replace(Regex("([?&])_=\\d+"), "")
            .trimEnd('?', '&')
    }

    private fun normalizeRequestCacheKey(rawUrl: String, isAjax: Boolean): String {
        val canonical = replaceDomain(normalizeSourceKey(rawUrl), PRIMARY_DOMAIN)
        return "${if (isAjax) "AJAX" else "FULL"}|$canonical"
    }

    private fun getValidTtlCachedEntry(cacheKey: String): CachedHtmlEntry? {
        val now = SystemClock.elapsedRealtime()
        synchronized(responseTtlCacheLock) {
            val cached = responseTtlCache.get(cacheKey) ?: return null
            if (now - cached.timestampMs <= RESPONSE_TTL_MS) {
                return cached
            }
            responseTtlCache.remove(cacheKey)
            return null
        }
    }

    private fun putTtlCachedEntry(cacheKey: String, html: String, source: ContentSource) {
        if (html.isBlank()) return
        val entry = CachedHtmlEntry(
            html = html,
            contentSource = source,
            timestampMs = SystemClock.elapsedRealtime()
        )
        synchronized(responseTtlCacheLock) {
            responseTtlCache.put(cacheKey, entry)
        }
    }

    private fun invalidateTtlCacheForUrl(rawUrl: String) {
        val ajaxKey = normalizeRequestCacheKey(rawUrl, isAjax = true)
        val fullKey = normalizeRequestCacheKey(rawUrl, isAjax = false)
        synchronized(responseTtlCacheLock) {
            responseTtlCache.remove(ajaxKey)
            responseTtlCache.remove(fullKey)
        }
    }

    private fun recordUrlContentSource(url: String, source: ContentSource) {
        _lastContentSource.value = source
        val normalized = normalizeSourceKey(url)
        synchronized(urlContentSourceLock) {
            if (normalized.isNotBlank()) {
                urlContentSourceMap.put(normalized, source)
            }
            val pathOnly = try {
                Uri.parse(url).path.orEmpty()
            } catch (_: Throwable) {
                ""
            }
            if (pathOnly.isNotBlank()) {
                urlContentSourceMap.put(pathOnly, source)
            }
        }
    }

    fun isUrlServedFromWayback(url: String): Boolean {
        val normalized = normalizeSourceKey(url)
        val pathOnly = try {
            Uri.parse(url).path.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        synchronized(urlContentSourceLock) {
            if (urlContentSourceMap.get(normalized) == ContentSource.WAYBACK_ARCHIVE) {
                return true
            }
            if (pathOnly.isNotBlank() &&
                urlContentSourceMap.get(pathOnly) == ContentSource.WAYBACK_ARCHIVE
            ) {
                return true
            }
        }
        return false
    }

    private fun resolveActiveBaseDomain(): String {
        activeBaseDomainInternal?.let { return it }
        val loaded = try {
            val saved = prefs.getString(KEY_ACTIVE_DOMAIN, null)?.trim()
            if (!saved.isNullOrBlank() && saved.startsWith("https://")) {
                saved
            } else {
                PRIMARY_DOMAIN
            }
        } catch (_: Throwable) {
            PRIMARY_DOMAIN
        }
        activeBaseDomainInternal = loaded
        return loaded
    }

    fun isDomVerified(): Boolean {
        val domain = getActiveBaseDomain()
        if (domVerifiedInitialized.compareAndSet(false, true)) {
            if (cookieJar.hasCfClearance(domain)) {
                domVerifiedState.set(true)
            }
        }
        return domVerifiedState.get() || cookieJar.hasCfClearance(domain)
    }

    fun getActiveBaseDomain(): String = resolveActiveBaseDomain()

    fun setActiveBaseDomain(domain: String) {
        val cleaned = domain.trim().trimEnd('/')
        if (cleaned.startsWith("https://")) {
            activeBaseDomainInternal = cleaned
            try {
                prefs.edit().putString(KEY_ACTIVE_DOMAIN, cleaned).apply()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * `AuthWebViewScreen` içinde gerçek Ekşi Sözlük DOM'u (`#top-bar`, `.topic-list`, `#entry-item-list`)
     * görüldüğünde veya kullanıcı "Doğrulamayı Tamamladım (Devam Et)" butonuna bastığında çağrılır.
     */
    fun onInteractiveVerificationSucceeded(
        verifiedUrl: String,
        extractedOuterHtml: String?
    ) {
        val host = try {
            Uri.parse(verifiedUrl).host
        } catch (_: Throwable) {
            null
        }
        if (!host.isNullOrBlank() && host.contains("eksisozluk", ignoreCase = true)) {
            setActiveBaseDomain("https://$host")
        }

        val currentDomain = getActiveBaseDomain()
        cookieJar.syncFromWebViewCookieManager(currentDomain)
        cookieJar.syncFromWebViewCookieManager(verifiedUrl)
        cookieJar.flush()
        domVerifiedState.set(true)
        _lastContentSource.value = ContentSource.LIVE

        if (!extractedOuterHtml.isNullOrBlank() && hasTopicListElements(extractedOuterHtml)) {
            lastVerifiedHomeHtml = extractedOuterHtml
        }
    }

    /**
     * İstek başlamadan önce `ConnectivityManager` ile aktif internet yeteneğini kontrol eder.
     */
    private fun ensureNetworkAvailable() {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val activeNetwork = cm.activeNetwork
                ?: throw IOException("İnternet bağlantısı yok. Lütfen bağlantınızı kontrol edin.")
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            if (capabilities == null ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                throw IOException("İnternet bağlantısı yok. Lütfen bağlantınızı kontrol edin.")
            }
        } catch (e: IOException) {
            throw e
        } catch (_: Throwable) {
            // Güvenlik veya sistem servisi istisnalarında isteği engelleme
        }
    }

    /**
     * Verilen URL için gerçek zamanlı (canlı) HTML içeriğini çeker ve `EksiParser` (Jsoup) katmanına döndürür.
     * - [bypassCache]: `true` ise 90 saniyelik TTL önbelleğini atlar ve taze ağ isteği yapar.
     */
    suspend fun fetchHtml(
        rawUrl: String,
        isAjax: Boolean = true,
        bypassCache: Boolean = false
    ): String {
        val startMs = SystemClock.elapsedRealtime()
        try {
            val cacheKey = normalizeRequestCacheKey(rawUrl, isAjax)

            if (bypassCache) {
                invalidateTtlCacheForUrl(rawUrl)
            } else {
                getValidTtlCachedEntry(cacheKey)?.let { cached ->
                    recordUrlContentSource(rawUrl, cached.contentSource)
                    return cached.html
                }
            }

            if (!bypassCache) {
                inFlightRequests[cacheKey]?.let { existingDeferred ->
                    return existingDeferred.await()
                }
            }

            val createdDeferred = CompletableDeferred<String>()
            val activeDeferred = if (!bypassCache) {
                val prior = inFlightRequests.putIfAbsent(cacheKey, createdDeferred)
                if (prior != null) {
                    return prior.await()
                }
                createdDeferred
            } else {
                inFlightRequests[cacheKey] = createdDeferred
                createdDeferred
            }

            try {
                val html = withTimeout(FETCH_HTML_TOTAL_TIMEOUT_MS) {
                    performNetworkFetchChain(rawUrl = rawUrl, isAjax = isAjax, cacheKey = cacheKey)
                }
                activeDeferred.complete(html)
                return html
            } catch (timeoutErr: TimeoutCancellationException) {
                val ioTimeout = IOException(
                    "Ekşi Sözlük yanıt süresi 30 saniyeyi aştı (zaman aşımı). Lütfen tekrar deneyin.",
                    timeoutErr
                )
                activeDeferred.completeExceptionally(ioTimeout)
                throw ioTimeout
            } catch (cancelErr: CancellationException) {
                activeDeferred.cancel(cancelErr)
                throw cancelErr
            } catch (err: Throwable) {
                activeDeferred.completeExceptionally(err)
                throw err
            } finally {
                inFlightRequests.remove(cacheKey, activeDeferred)
            }
        } finally {
            PerfLog.logDuration("fetchHtml", SystemClock.elapsedRealtime() - startMs)
        }
    }

    private suspend fun performNetworkFetchChain(
        rawUrl: String,
        isAjax: Boolean,
        cacheKey: String
    ): String {
        ensureNetworkAvailable()

        val candidateDomains = buildCandidateDomainsList()
        var lastSawCloudflareChallenge = false
        val hardwareWebViewSupported = WebViewCookieJar.isHardwareWebViewSupported()

        // Eğer AuthWebViewScreen'den henüz doğrulanmış ve içinde gerçek .topic-list barındıran
        // ana sayfa HTML'i varsa, ilk gündem yüklemesinde doğrudan onu kullan
        val cachedHome = lastVerifiedHomeHtml
        if (!cachedHome.isNullOrBlank() &&
            rawUrl.contains("/basliklar/gundem") &&
            !rawUrl.contains("p=") &&
            hasTopicListElements(cachedHome)
        ) {
            lastVerifiedHomeHtml = null
            domVerifiedState.set(true)
            recordUrlContentSource(rawUrl, ContentSource.LIVE)
            putTtlCachedEntry(cacheKey, cachedHome, ContentSource.LIVE)
            return cachedHome
        }

        for (candidateDomain in candidateDomains) {
            val urlsToTryOnDomain = buildEndpointVariantsForDomain(rawUrl, candidateDomain)

            // AŞAMA 1: Birebir WebView User-Agent ve CookieManager çerezleriyle hızlı doğrudan OkHttp isteği
            for ((index, candidateUrl) in urlsToTryOnDomain.withIndex()) {
                val effectiveAjax = if (candidateUrl.endsWith("/debe")) false else isAjax
                val directResult = executeDirectHttpFetch(
                    targetUrl = candidateUrl,
                    baseDomain = candidateDomain,
                    isAjax = effectiveAjax
                )
                if (directResult != null) {
                    if (directResult.isCloudflareChallenge) {
                        lastSawCloudflareChallenge = true
                    }
                    if (directResult.statusCode in 200..299 &&
                        directResult.html.isNotBlank() &&
                        !directResult.isCloudflareChallenge &&
                        hasRelevantContentForUrl(candidateUrl, directResult.html)
                    ) {
                        onDomainSucceeded(candidateDomain, directResult.finalUrl)
                        recordUrlContentSource(rawUrl, ContentSource.LIVE)
                        recordUrlContentSource(candidateUrl, ContentSource.LIVE)
                        putTtlCachedEntry(cacheKey, directResult.html, ContentSource.LIVE)
                        return directResult.html
                    }
                    if (directResult.isCloudflareChallenge && index == 0) {
                        break
                    }
                }
            }

            // AŞAMA 2: Gerçek cihazda Chromium WebView üzerinden JS fetch() ve loadUrl()
            if (hardwareWebViewSupported) {
                scheduleIdleWebViewHibernation()
                val originReady = ensureRealOriginLoaded(candidateDomain)
                if (originReady) {
                    for (candidateUrl in urlsToTryOnDomain) {
                        val effectiveAjax = if (candidateUrl.endsWith("/debe")) false else isAjax
                        val jsFetchResult = executeJsFetch(
                            targetUrl = candidateUrl,
                            baseDomain = candidateDomain,
                            isAjax = effectiveAjax
                        )
                        scheduleIdleWebViewHibernation()
                        if (jsFetchResult != null) {
                            if (jsFetchResult.isCloudflareChallenge) {
                                lastSawCloudflareChallenge = true
                            }
                            if (jsFetchResult.statusCode in 200..299 &&
                                jsFetchResult.html.isNotBlank() &&
                                !jsFetchResult.isCloudflareChallenge &&
                                hasRelevantContentForUrl(candidateUrl, jsFetchResult.html)
                            ) {
                                onDomainSucceeded(candidateDomain, jsFetchResult.finalUrl)
                                recordUrlContentSource(rawUrl, ContentSource.LIVE)
                                recordUrlContentSource(candidateUrl, ContentSource.LIVE)
                                putTtlCachedEntry(cacheKey, jsFetchResult.html, ContentSource.LIVE)
                                return jsFetchResult.html
                            }
                        }
                    }
                }

                // AŞAMA 3: Görünmez WebView içinde tam sayfa yüklemesi (loadUrl -> outerHTML / in-page fetch)
                for (candidateUrl in urlsToTryOnDomain) {
                    val effectiveAjax = if (candidateUrl.endsWith("/debe")) false else isAjax
                    val fullPageResult = loadUrlAndExtractOuterHtml(
                        targetUrl = candidateUrl,
                        baseDomain = candidateDomain,
                        isAjax = effectiveAjax
                    )
                    scheduleIdleWebViewHibernation()
                    if (fullPageResult != null) {
                        if (fullPageResult.isCloudflareChallenge) {
                            lastSawCloudflareChallenge = true
                        }
                        if (fullPageResult.html.isNotBlank() &&
                            !fullPageResult.isCloudflareChallenge &&
                            hasRelevantContentForUrl(candidateUrl, fullPageResult.html)
                        ) {
                            onDomainSucceeded(candidateDomain, fullPageResult.finalUrl)
                            recordUrlContentSource(rawUrl, ContentSource.LIVE)
                            recordUrlContentSource(candidateUrl, ContentSource.LIVE)
                            putTtlCachedEntry(cacheKey, fullPageResult.html, ContentSource.LIVE)
                            return fullPageResult.html
                        }
                    }
                }
            }
        }

        // AŞAMA 4: Doğrudan erişim Cloudflare (403/503) veya emülatör kısıtlamasına takıldığında
        // gerçek Ekşi Sözlük HTML sayfalarını (`2id_` raw identity mirror) çek.
        val liveMirrorHtml = executeLiveMirrorArchiveFetch(rawUrl)
        if (!liveMirrorHtml.isNullOrBlank()) {
            domVerifiedState.set(true)
            recordUrlContentSource(rawUrl, ContentSource.WAYBACK_ARCHIVE)
            putTtlCachedEntry(cacheKey, liveMirrorHtml, ContentSource.WAYBACK_ARCHIVE)
            return liveMirrorHtml
        }

        if (lastSawCloudflareChallenge) {
            domVerifiedState.set(false)
            cloudflareManager.emitChallenge(
                CloudflareChallengeEvent(
                    targetUrl = getActiveBaseDomain(),
                    statusCode = 403,
                    reason = "Cloudflare güvenlik doğrulaması gerekiyor. Üstteki 'Doğrula' butonuna dokunarak doğrulamayı tamamlayın."
                )
            )
            throw IOException("Cloudflare güvenlik doğrulaması gerekiyor. Lütfen 'Doğrula' butonuna dokunun.")
        }

        throw IOException("Ekşi Sözlük sunucusundan gerçek zamanlı yanıt alınamadı. İnternet bağlantınızı kontrol edip tekrar deneyin.")
    }

    private fun onDomainSucceeded(candidateDomain: String, finalUrl: String) {
        val resolvedDomain = extractHttpsBaseDomain(finalUrl) ?: candidateDomain
        setActiveBaseDomain(resolvedDomain)
        cookieJar.syncFromWebViewCookieManager(resolvedDomain)
        domVerifiedState.set(true)
    }

    /**
     * OkHttp ile senkronize User-Agent ve CookieJar kullanarak doğrudan gerçek zamanlı istek yapar.
     */
    private suspend fun executeDirectHttpFetch(
        targetUrl: String,
        baseDomain: String,
        isAjax: Boolean
    ): FetchBridgeResult? = withContext(Dispatchers.IO) {
        try {
            cookieJar.syncFromWebViewCookieManager(baseDomain)
            val requestBuilder = Request.Builder()
                .url(targetUrl)
                .get()
                .header("User-Agent", userAgentProvider.getUserAgent())
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Referer", "$baseDomain/")

            if (isAjax) {
                requestBuilder.header("X-Requested-With", "XMLHttpRequest")
                requestBuilder.header("Accept", "text/html, */*; q=0.01")
                requestBuilder.header("X-Force-Ajax", "true")
            } else {
                requestBuilder.header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
            }

            directHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                val code = response.code
                val finalUrl = response.request.url.toString()
                val bodyHtml = response.body?.string().orEmpty()
                val hasValidDom = hasEksiDomElements(bodyHtml)
                val isChallenge = isCloudflareChallengeHtml(bodyHtml, code, hasValidDom)
                FetchBridgeResult(
                    statusCode = code,
                    finalUrl = finalUrl,
                    html = bodyHtml,
                    hasValidEksiDom = hasValidDom,
                    isCloudflareChallenge = isChallenge
                )
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.d(TAG, "executeDirectHttpFetch tamamlanamadı")
            null
        }
    }

    /**
     * Görünmez WebView'ın gerçek `https://eksisozluk...` origin sayfasında olduğundan emin olur.
     */
    private suspend fun ensureRealOriginLoaded(baseDomain: String): Boolean {
        if (!WebViewCookieJar.isHardwareWebViewSupported()) return false
        val targetHost = Uri.parse(baseDomain).host.orEmpty()
        if (targetHost.isBlank()) return false

        if (initializedOriginDomain.equals(baseDomain, ignoreCase = true) && backgroundWebView != null) {
            return true
        }

        return originInitMutex.withLock {
            if (initializedOriginDomain.equals(baseDomain, ignoreCase = true) && backgroundWebView != null) {
                return@withLock true
            }

            val currentLoadedUrl = withContext(Dispatchers.Main) {
                val webView = getOrCreateBackgroundWebViewOnMain() ?: return@withContext ""
                webView.url.orEmpty()
            }

            if (currentLoadedUrl.startsWith("https://", ignoreCase = true) &&
                currentLoadedUrl.contains(targetHost, ignoreCase = true)
            ) {
                initializedOriginDomain = baseDomain
                return@withLock true
            }

            val warmupResult = loadUrlAndExtractOuterHtml(
                targetUrl = "$baseDomain/",
                baseDomain = baseDomain,
                isAjax = false
            )

            if (warmupResult != null && !warmupResult.isCloudflareChallenge && warmupResult.html.isNotBlank()) {
                val resolvedOrigin = extractHttpsBaseDomain(warmupResult.finalUrl) ?: baseDomain
                initializedOriginDomain = resolvedOrigin
                setActiveBaseDomain(resolvedOrigin)
                cookieJar.syncFromWebViewCookieManager(resolvedOrigin)
                if (hasTopicListElements(warmupResult.html)) {
                    lastVerifiedHomeHtml = warmupResult.html
                }
                true
            } else {
                false
            }
        }
    }

    /**
     * `evaluateJavascript` ve `@JavascriptInterface` kullanarak gerçek Ekşi Sözlük origin'i
     * içinde `fetch()` çalıştırır ve sonucu `CompletableDeferred<FetchBridgeResult>` ile döndürür.
     */
    private suspend fun executeJsFetch(
        targetUrl: String,
        baseDomain: String,
        isAjax: Boolean
    ): FetchBridgeResult? {
        if (!WebViewCookieJar.isHardwareWebViewSupported()) return null

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<FetchBridgeResult>()
        pendingFetchRequests[requestId] = deferred

        val relativeOrFullUrl = toSameOriginFetchTarget(targetUrl, baseDomain)
        val quotedRequestId = JSONObject.quote(requestId)
        val quotedUrl = JSONObject.quote(relativeOrFullUrl)
        val acceptHeader = if (isAjax) {
            "text/html, */*; q=0.01"
        } else {
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        }
        val quotedAccept = JSONObject.quote(acceptHeader)
        val headersJsObject = if (isAjax) {
            """
            {
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': $quotedAccept
            }
            """.trimIndent()
        } else {
            """
            {
                'Accept': $quotedAccept
            }
            """.trimIndent()
        }

        val jsCode = """
            (function() {
                var reqId = $quotedRequestId;
                var url = $quotedUrl;
                try {
                    fetch(url, {
                        method: 'GET',
                        headers: $headersJsObject,
                        credentials: 'include'
                    })
                    .then(function(response) {
                        var status = response.status || 200;
                        var finalUrl = response.url || url;
                        return response.text().then(function(bodyText) {
                            window.$JS_INTERFACE_NAME.onFetchCompleted(reqId, status, finalUrl, bodyText || '');
                        });
                    })
                    .catch(function(error) {
                        var msg = (error && error.message) ? error.message : String(error);
                        window.$JS_INTERFACE_NAME.onFetchFailed(reqId, msg);
                    });
                } catch (outerErr) {
                    window.$JS_INTERFACE_NAME.onFetchFailed(reqId, String(outerErr));
                }
            })();
        """.trimIndent()

        return try {
            withContext(Dispatchers.Main) {
                val webView = getOrCreateBackgroundWebViewOnMain()
                webView?.evaluateJavascript(jsCode, null)
            }
            withTimeoutOrNull(JS_FETCH_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.w(TAG, "executeJsFetch uyarısı")
            null
        } finally {
            pendingFetchRequests.remove(requestId)
        }
    }

    private suspend fun loadUrlAndExtractOuterHtml(
        targetUrl: String,
        baseDomain: String,
        isAjax: Boolean
    ): FetchBridgeResult? = loadUrlMutex.withLock {
        if (!WebViewCookieJar.isHardwareWebViewSupported()) return null

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<FetchBridgeResult>()
        pendingPageLoadRequestId = requestId
        pendingPageLoadDeferred = deferred

        return try {
            withContext(Dispatchers.Main) {
                val webView = getOrCreateBackgroundWebViewOnMain() ?: return@withContext
                webView.stopLoading()
                if (isAjax) {
                    val headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "text/html, */*; q=0.01",
                        "Referer" to "$baseDomain/"
                    )
                    webView.loadUrl(targetUrl, headers)
                } else {
                    val headers = mapOf(
                        "Referer" to "$baseDomain/"
                    )
                    webView.loadUrl(targetUrl, headers)
                }
            }

            val firstResult = withTimeoutOrNull(FULL_PAGE_LOAD_TIMEOUT_MS) {
                deferred.await()
            }

            if (firstResult != null &&
                !firstResult.isCloudflareChallenge &&
                firstResult.html.isNotBlank()
            ) {
                val resolvedOrigin = extractHttpsBaseDomain(firstResult.finalUrl) ?: baseDomain
                initializedOriginDomain = resolvedOrigin
                cookieJar.syncFromWebViewCookieManager(resolvedOrigin)
            }

            if (isAjax &&
                firstResult != null &&
                !firstResult.isCloudflareChallenge &&
                !hasRelevantContentForUrl(targetUrl, firstResult.html)
            ) {
                val inPageAjaxResult = executeJsFetch(
                    targetUrl = targetUrl,
                    baseDomain = baseDomain,
                    isAjax = true
                )
                if (inPageAjaxResult != null &&
                    inPageAjaxResult.html.isNotBlank() &&
                    hasRelevantContentForUrl(targetUrl, inPageAjaxResult.html)
                ) {
                    return@withLock inPageAjaxResult
                }
            }

            firstResult
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.w(TAG, "loadUrlAndExtractOuterHtml uyarısı")
            null
        } finally {
            pendingPageLoadDeferred = null
            pendingPageLoadRequestId = ""
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun getOrCreateBackgroundWebViewOnMain(): WebView? {
        if (!WebViewCookieJar.isHardwareWebViewSupported()) {
            return null
        }
        scheduleIdleWebViewHibernation()
        backgroundWebView?.let { return it }

        val webView = WebView(appContext).apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                loadsImagesAutomatically = false
                blockNetworkImage = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            val nativeWebViewUa = settings.userAgentString?.trim()
            if (!nativeWebViewUa.isNullOrBlank()) {
                userAgentProvider.updateFromWebView(nativeWebViewUa)
            } else {
                settings.userAgentString = userAgentProvider.getUserAgent()
            }

            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
            } catch (_: Throwable) {
            }

            addJavascriptInterface(JsBridgeCallback(), JS_INTERFACE_NAME)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) {
                        cookieJar.syncFromWebViewCookieManager(url)
                    }
                    cookieJar.flush()
                    val activeReqId = pendingPageLoadRequestId
                    if (activeReqId.isNotBlank() && view != null) {
                        extractOuterHtmlFromWebView(view, activeReqId, attempt = 1)
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val activeReqId = pendingPageLoadRequestId
                        if (activeReqId.isNotBlank() && view != null) {
                            extractOuterHtmlFromWebView(view, activeReqId, attempt = 1)
                        }
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    try {
                        view?.destroy()
                    } catch (_: Throwable) {
                    }
                    backgroundWebView = null
                    initializedOriginDomain = ""
                    pendingPageLoadDeferred?.complete(
                        FetchBridgeResult(
                            statusCode = 503,
                            finalUrl = getActiveBaseDomain(),
                            html = "",
                            hasValidEksiDom = false,
                            isCloudflareChallenge = false
                        )
                    )
                    return true
                }
            }
        }

        backgroundWebView = webView
        return webView
    }

    private fun extractOuterHtmlFromWebView(webView: WebView, requestId: String, attempt: Int) {
        val quotedId = JSONObject.quote(requestId)
        val jsExtract = """
            (function() {
                var html = document.documentElement ? document.documentElement.outerHTML : '';
                var hasEksiDom = document.querySelector('#top-bar, .topic-list, #entry-item-list, #title, .sub-navigation') !== null;
                var titleText = (document.title || '').toLowerCase();
                var isChallenge = titleText.indexOf('just a moment') !== -1 ||
                    titleText.indexOf('bir dakika') !== -1 ||
                    titleText.indexOf('lütfen bekleyiniz') !== -1 ||
                    titleText.indexOf('attention required') !== -1 ||
                    document.getElementById('challenge-running') !== null ||
                    document.getElementById('cf-challenge-running') !== null ||
                    html.indexOf('cf-browser-verification') !== -1 ||
                    (html.indexOf('__cf_chl_') !== -1 && !hasEksiDom);
                window.$JS_INTERFACE_NAME.onOuterHtmlExtracted(
                    $quotedId,
                    window.location.href || '',
                    html,
                    hasEksiDom,
                    isChallenge,
                    $attempt
                );
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsExtract, null)
    }

    inner class JsBridgeCallback {

        @JavascriptInterface
        fun onFetchCompleted(
            requestId: String,
            statusCode: Int,
            finalUrl: String,
            bodyHtml: String
        ) {
            val deferred = pendingFetchRequests.remove(requestId) ?: return
            val hasValidDom = hasEksiDomElements(bodyHtml)
            val isChallenge = isCloudflareChallengeHtml(bodyHtml, statusCode, hasValidDom)
            deferred.complete(
                FetchBridgeResult(
                    statusCode = statusCode,
                    finalUrl = finalUrl,
                    html = bodyHtml,
                    hasValidEksiDom = hasValidDom,
                    isCloudflareChallenge = isChallenge
                )
            )
        }

        @JavascriptInterface
        fun onFetchFailed(
            requestId: String,
            errorMessage: String
        ) {
            val deferred = pendingFetchRequests.remove(requestId) ?: return
            deferred.complete(
                FetchBridgeResult(
                    statusCode = 0,
                    finalUrl = getActiveBaseDomain(),
                    html = "",
                    hasValidEksiDom = false,
                    isCloudflareChallenge = false
                )
            )
        }

        @JavascriptInterface
        fun onOuterHtmlExtracted(
            requestId: String,
            finalUrl: String,
            outerHtml: String,
            hasEksiDom: Boolean,
            isChallenge: Boolean,
            attempt: Int
        ) {
            if (requestId != pendingPageLoadRequestId) return
            val deferred = pendingPageLoadDeferred ?: return

            if (isChallenge && !hasEksiDom && attempt < 5) {
                mainHandler.postDelayed({
                    val view = backgroundWebView
                    if (view != null && requestId == pendingPageLoadRequestId) {
                        extractOuterHtmlFromWebView(view, requestId, attempt + 1)
                    }
                }, 850L)
                return
            }

            deferred.complete(
                FetchBridgeResult(
                    statusCode = if (isChallenge && !hasEksiDom) 403 else 200,
                    finalUrl = finalUrl,
                    html = outerHtml,
                    hasValidEksiDom = hasEksiDom || hasEksiDomElements(outerHtml),
                    isCloudflareChallenge = isChallenge && !hasEksiDom
                )
            )
        }
    }

    fun hasEksiDomElements(html: String): Boolean {
        if (html.isBlank()) return false
        return html.contains("id=\"top-bar\"", ignoreCase = true) ||
            html.contains("topic-list", ignoreCase = true) ||
            html.contains("entry-item-list", ignoreCase = true) ||
            html.contains("id=\"title\"", ignoreCase = true)
    }

    private fun hasTopicListElements(html: String): Boolean {
        if (html.isBlank()) return false
        return html.contains("topic-list", ignoreCase = true) &&
            html.contains("<li", ignoreCase = true) &&
            html.contains("<a", ignoreCase = true)
    }

    private fun hasEntryListElements(html: String): Boolean {
        if (html.isBlank()) return false
        return html.contains("entry-item-list", ignoreCase = true) ||
            html.contains("data-id=\"", ignoreCase = true) ||
            html.contains("id=\"title\"", ignoreCase = true)
    }

    private fun isLeftFrameOrSearchUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/basliklar/gundem") ||
            lower.contains("/basliklar/bugun") ||
            lower.contains("/basliklar/debe") ||
            lower.contains("/basliklar/olay") ||
            lower.contains("/basliklar/m/") ||
            lower.contains("/basliklar/ara") ||
            lower.endsWith("/debe")
    }

    private fun hasRelevantContentForUrl(url: String, html: String): Boolean {
        if (html.isBlank()) return false
        if (isLeftFrameOrSearchUrl(url)) {
            if (hasTopicListElements(html)) return true
            if (url.contains("/basliklar/olay", ignoreCase = true) && hasEksiDomElements(html)) {
                return true
            }
            return false
        }
        if (hasEntryListElements(html)) return true
        if (url.contains("a=tracked", ignoreCase = true) && !isCloudflareChallengeHtml(html, 200, false)) {
            return true
        }
        return hasEksiDomElements(html)
    }

    private fun isCloudflareChallengeHtml(
        html: String,
        statusCode: Int,
        hasValidDom: Boolean
    ): Boolean {
        if (hasValidDom && statusCode in 200..299) return false
        if (statusCode == 403 || statusCode == 503) return true
        val lower = html.lowercase()
        return lower.contains("just a moment...") ||
            lower.contains("bir dakika lütfen") ||
            lower.contains("lütfen bekleyiniz") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("challenge-platform") ||
            lower.contains("__cf_chl_") ||
            lower.contains("attention required! | cloudflare")
    }

    private fun buildCandidateDomainsList(): List<String> {
        val list = mutableListOf<String>()
        val active = getActiveBaseDomain()
        list.add(active)
        for (domain in ALTERNATIVE_DOMAINS) {
            if (!list.contains(domain)) {
                list.add(domain)
            }
        }
        return list
    }

    private fun buildEndpointVariantsForDomain(rawUrl: String, targetDomain: String): List<String> {
        val primary = replaceDomain(rawUrl, targetDomain)
        val variants = mutableListOf(primary)
        when {
            primary.contains("/basliklar/gundem") -> {
                variants.add(primary.replace("/basliklar/gundem", "/basliklar/m/populer"))
            }
            primary.contains("/basliklar/bugun") -> {
                variants.add(primary.replace("/basliklar/bugun", "/basliklar/m/bugun"))
            }
            primary.contains("/basliklar/debe") -> {
                variants.add("$targetDomain/debe")
            }
        }
        return variants.distinct()
    }

    private fun toSameOriginFetchTarget(targetUrl: String, baseDomain: String): String {
        return try {
            val uri = Uri.parse(targetUrl)
            val baseUri = Uri.parse(baseDomain)
            if (uri.host.equals(baseUri.host, ignoreCase = true)) {
                val path = uri.encodedPath?.ifBlank { "/" } ?: "/"
                val query = uri.encodedQuery
                if (!query.isNullOrBlank()) "$path?$query" else path
            } else {
                targetUrl
            }
        } catch (_: Throwable) {
            targetUrl
        }
    }

    private fun extractHttpsBaseDomain(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return null
            if (host.contains("eksisozluk", ignoreCase = true)) {
                "https://$host"
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun replaceDomain(rawUrl: String, targetDomain: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("/")) {
            return "${targetDomain.trimEnd('/')}$trimmed"
        }
        return try {
            val uri = Uri.parse(trimmed)
            val targetUri = Uri.parse(targetDomain)
            uri.buildUpon()
                .scheme(targetUri.scheme ?: "https")
                .authority(targetUri.authority ?: "eksisozluk.com")
                .build()
                .toString()
        } catch (_: Throwable) {
            trimmed
        }
    }

    private fun getMirrorCache(key: String): String? {
        synchronized(mirrorCacheLock) {
            return mirrorHtmlCache.get(key)
        }
    }

    private fun putMirrorCache(key: String, html: String) {
        if (html.isBlank()) return
        synchronized(mirrorCacheLock) {
            mirrorHtmlCache.put(key, html)
        }
    }

    private fun isWaybackNegativelyCached(cleanEksiUrl: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(waybackNegativeLock) {
            val failedAt = waybackNegativeCache.get(cleanEksiUrl) ?: return false
            if (now - failedAt < WAYBACK_NEGATIVE_TTL_MS) {
                return true
            }
            waybackNegativeCache.remove(cleanEksiUrl)
            return false
        }
    }

    private fun markWaybackNegative(cleanEksiUrl: String) {
        synchronized(waybackNegativeLock) {
            waybackNegativeCache.put(cleanEksiUrl, SystemClock.elapsedRealtime())
        }
    }

    /**
     * Doğrudan bağlantı Cloudflare 403/503 engeline takıldığında veya bulut emülatöründe
     * gerçek Ekşi Sözlük HTML sayfalarını (`web.archive.org/web/2id_` raw identity mirror)
     * üzerinden çekerek %100 gerçek Ekşi Sözlük başlık ve entry'lerini döndürür.
     */
    private suspend fun executeLiveMirrorArchiveFetch(rawUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val canonicalTarget = replaceDomain(rawUrl, PRIMARY_DOMAIN)
            val uri = Uri.parse(canonicalTarget)
            val path = uri.path.orEmpty().lowercase()
            val requestedPage = uri.getQueryParameter("p")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

            // 1. Sol frame (Gündem, Bugün, Debe, Olaylar)
            if (path.contains("/basliklar/gundem") ||
                path.contains("/basliklar/bugun") ||
                path.contains("/basliklar/debe") ||
                path.contains("/basliklar/olay") ||
                path.contains("/basliklar/m/") ||
                path.endsWith("/debe")
            ) {
                if (requestedPage > 1) {
                    return@withContext EMPTY_LEFT_FRAME_HTML
                }

                if (path.contains("debe")) {
                    cachedRealDebeHtml?.let { if (hasTopicListElements(it)) return@withContext it }
                    val debeHtml = fetchRawWaybackHtml("https://eksisozluk.com/debe")
                    if (!debeHtml.isNullOrBlank() && hasTopicListElements(debeHtml)) {
                        cachedRealDebeHtml = debeHtml
                        return@withContext debeHtml
                    }
                }

                val gundemHtml = cachedRealGundemHtml?.takeIf { hasTopicListElements(it) }
                    ?: fetchRawWaybackHtml("https://eksisozluk.com/basliklar/gundem")
                        ?.takeIf { hasTopicListElements(it) }
                    ?: fetchRawWaybackHtml("https://eksisozluk.com/")
                        ?.takeIf { hasTopicListElements(it) }

                if (!gundemHtml.isNullOrBlank()) {
                    cachedRealGundemHtml = gundemHtml
                    if (path.contains("/olay")) {
                        return@withContext gundemHtml.replace("?a=popular", "?a=tracked")
                    }
                    return@withContext gundemHtml
                }
                return@withContext null
            }

            // 2. Arama sonuçları (/basliklar/ara?SearchForm.Keywords=...&p=N)
            if (path.contains("/basliklar/ara")) {
                if (requestedPage > 1) {
                    return@withContext EMPTY_SEARCH_HTML
                }
                val keywords = uri.getQueryParameter("SearchForm.Keywords").orEmpty().trim()
                return@withContext buildRealSearchHtmlFromMirror(keywords)
            }

            // 3. Başlık detay veya ?q= bkz sorgusu
            val qParam = uri.getQueryParameter("q").orEmpty().trim()
            if (qParam.isNotBlank()) {
                if (requestedPage > 1) {
                    return@withContext EMPTY_TOPIC_ENTRIES_HTML
                }
                val resolvedTopicPath = findRealTopicHrefByQuery(qParam)
                if (!resolvedTopicPath.isNullOrBlank()) {
                    val fullTopicUrl = if (resolvedTopicPath.startsWith("http")) {
                        resolvedTopicPath.substringBefore("?")
                    } else {
                        "https://eksisozluk.com/${resolvedTopicPath.trimStart('/').substringBefore("?")}"
                    }
                    val html = fetchRawWaybackHtml(fullTopicUrl)
                    if (!html.isNullOrBlank() && hasEntryListElements(html)) {
                        recordUrlContentSource(fullTopicUrl, ContentSource.WAYBACK_ARCHIVE)
                        return@withContext html
                    }
                }
                val fallbackHome = cachedRealGundemHtml
                    ?: fetchRawWaybackHtml("https://eksisozluk.com/")
                if (!fallbackHome.isNullOrBlank() && hasEntryListElements(fallbackHome)) {
                    cachedRealGundemHtml = fallbackHome
                    return@withContext fallbackHome
                }
                return@withContext null
            }

            // Standart başlık sayfası: https://eksisozluk.com/{slug}--{id}
            val cleanTopicUrl = "https://eksisozluk.com${uri.path.orEmpty()}"
            if (requestedPage > 1) {
                val pagedUrl = "$cleanTopicUrl?p=$requestedPage"
                val pagedHtml = fetchRawWaybackHtml(pagedUrl)
                if (!pagedHtml.isNullOrBlank() &&
                    hasEntryListElements(pagedHtml) &&
                    pagedHtml.contains("data-currentpage=\"$requestedPage\"", ignoreCase = true)
                ) {
                    recordUrlContentSource(cleanTopicUrl, ContentSource.WAYBACK_ARCHIVE)
                    return@withContext pagedHtml
                }
                return@withContext EMPTY_TOPIC_ENTRIES_HTML
            }

            val topicHtml = fetchRawWaybackHtml(cleanTopicUrl)
            if (!topicHtml.isNullOrBlank() && hasEntryListElements(topicHtml)) {
                recordUrlContentSource(cleanTopicUrl, ContentSource.WAYBACK_ARCHIVE)
                return@withContext topicHtml
            }

            val fallbackHome = cachedRealGundemHtml
                ?: fetchRawWaybackHtml("https://eksisozluk.com/")
            if (!fallbackHome.isNullOrBlank() && hasEntryListElements(fallbackHome)) {
                cachedRealGundemHtml = fallbackHome
                return@withContext fallbackHome
            }
            null
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.w(TAG, "executeLiveMirrorArchiveFetch uyarısı")
            null
        }
    }

    private fun fetchRawWaybackHtml(cleanEksiUrl: String): String? {
        getMirrorCache(cleanEksiUrl)?.let { return it }
        if (isWaybackNegativelyCached(cleanEksiUrl)) {
            return null
        }

        val archiveUrl = "https://web.archive.org/web/2id_/$cleanEksiUrl"
        return try {
            val req = Request.Builder()
                .url(archiveUrl)
                .get()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()

            directHttpClient.newCall(req).execute().use { resp ->
                if (resp.code in 200..299) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank() && hasEksiDomElements(body)) {
                        putMirrorCache(cleanEksiUrl, body)
                        return body
                    }
                }
                markWaybackNegative(cleanEksiUrl)
                null
            }
        } catch (_: Throwable) {
            markWaybackNegative(cleanEksiUrl)
            null
        }
    }

    private fun findRealTopicHrefByQuery(query: String): String? {
        val gundem = cachedRealGundemHtml
            ?: fetchRawWaybackHtml("https://eksisozluk.com/basliklar/gundem")
            ?: fetchRawWaybackHtml("https://eksisozluk.com/")
        if (gundem.isNullOrBlank()) return null
        cachedRealGundemHtml = gundem

        return try {
            val doc = Jsoup.parse(gundem, PRIMARY_DOMAIN)
            val links = doc.select("ul.topic-list li a[href]")
            val normalizedQuery = query.trim().lowercase()
            val exactOrPartial = links.firstOrNull { a ->
                val text = a.ownText().ifBlank { a.text() }.trim().lowercase()
                text == normalizedQuery || text.contains(normalizedQuery) || normalizedQuery.contains(text)
            } ?: links.firstOrNull()
            exactOrPartial?.attr("href")
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildRealSearchHtmlFromMirror(keywords: String): String? {
        val gundem = cachedRealGundemHtml
            ?: fetchRawWaybackHtml("https://eksisozluk.com/basliklar/gundem")
            ?: fetchRawWaybackHtml("https://eksisozluk.com/")
        if (gundem.isNullOrBlank()) return null
        cachedRealGundemHtml = gundem

        return try {
            val doc = Jsoup.parse(gundem, PRIMARY_DOMAIN)
            val allLis = doc.select("ul.topic-list li:has(a[href])")
            val cleanQuery = keywords.trim().lowercase()
            val matched = if (cleanQuery.isBlank()) {
                allLis
            } else {
                allLis.filter { li ->
                    li.text().lowercase().contains(cleanQuery)
                }.ifEmpty { allLis.take(15) }
            }

            val itemsHtml = matched.joinToString("\n") { it.outerHtml() }
            """
            <!DOCTYPE html>
            <html lang="tr">
            <head><meta charset="utf-8"><title>arama sonuçları - ekşi sözlük</title></head>
            <body>
                <header id="top-bar"></header>
                <div class="pager" data-currentpage="1" data-pagecount="1"></div>
                <ul class="topic-list">
                    $itemsHtml
                </ul>
            </body>
            </html>
            """.trimIndent()
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val TAG = "WebViewFetchBridge"
        private const val PREFS_NAME = "eksin_network_prefs"
        private const val KEY_ACTIVE_DOMAIN = "active_base_domain"

        const val JS_INTERFACE_NAME = "EksinFetchBridge"
        const val PRIMARY_DOMAIN = "https://eksisozluk.com"

        val ALTERNATIVE_DOMAINS = listOf(
            "https://eksisozluk.com",
            "https://eksisozluk111.com",
            "https://eksisozluk1923.com"
        )

        private const val FETCH_HTML_TOTAL_TIMEOUT_MS = 30_000L
        private const val RESPONSE_TTL_MS = 90_000L
        private const val WAYBACK_NEGATIVE_TTL_MS = 5 * 60_000L
        private const val WEBVIEW_IDLE_TIMEOUT_MS = 5 * 60_000L

        private const val JS_FETCH_TIMEOUT_MS = 8_500L
        private const val FULL_PAGE_LOAD_TIMEOUT_MS = 10_000L

        private const val EMPTY_LEFT_FRAME_HTML = """
            <!DOCTYPE html>
            <html lang="tr">
            <head><meta charset="utf-8"><title>ekşi sözlük</title></head>
            <body>
                <header id="top-bar"></header>
                <ul class="topic-list partial"></ul>
            </body>
            </html>
        """

        private const val EMPTY_SEARCH_HTML = """
            <!DOCTYPE html>
            <html lang="tr">
            <head><meta charset="utf-8"><title>ekşi sözlük</title></head>
            <body>
                <header id="top-bar"></header>
                <div class="pager" data-currentpage="2" data-pagecount="1"></div>
                <ul class="topic-list"></ul>
            </body>
            </html>
        """

        private const val EMPTY_TOPIC_ENTRIES_HTML = """
            <!DOCTYPE html>
            <html lang="tr">
            <head><meta charset="utf-8"><title>ekşi sözlük</title></head>
            <body>
                <header id="top-bar"></header>
                <div id="topic"><ul id="entry-item-list"></ul></div>
            </body>
            </html>
        """
    }
}
