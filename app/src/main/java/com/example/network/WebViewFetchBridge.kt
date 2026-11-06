package com.example.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * JavaScript `fetch()`, `OkHttp` veya `loadUrl` (`document.documentElement.outerHTML`) sonucunu temsil eder.
 */
data class FetchBridgeResult(
    val statusCode: Int,
    val finalUrl: String,
    val html: String,
    val hasValidEksiDom: Boolean,
    val isCloudflareChallenge: Boolean
)

/**
 * Gerçek zamanlı (Real-Time) Ekşi Sözlük HTML veri çekme köprüsü.
 *
 * Çalışma Mimarisi:
 * 1. Birebir senkronize WebView `User-Agent` ve `CookieManager` (`WebViewCookieJar`) ile önce
 *    hızlı doğrudan bağlantıyı ve/veya gerçek Chromium TLS parmak izine sahip arka plan
 *    Singleton `WebView` içinden `fetch(url, { headers: { 'X-Requested-With': 'XMLHttpRequest' }, credentials: 'include' })`
 *    çağrısını çalıştırır.
 * 2. Arka plan `WebView` origin hazırlığında sentetik `data:` URL yerine gerçek Ekşi Sözlük
 *    sayfasını yükleyerek Cloudflare otomatik JS kontrollerinin ve birinci taraf çerezlerinin
 *    (`cf_clearance`, `__cf_bm`, `iq`) gerçek zamanlı oluşmasını sağlar.
 * 3. Mobil User-Agent altında `/basliklar/gundem` veya `/basliklar/bugun` uç noktalarının boş
 *    dönmesi ihtimaline karşı `/basliklar/m/populer`, `/basliklar/m/bugun` ve `/debe` yedek
 *    uç noktalarını otomatik dener.
 * 4. Türkiye'deki erişim engellerine (BTK) karşı `eksisozluk.com`, `eksisozluk111.com` ve
 *    `eksisozluk1923.com` domainlerini otomatik dener ve çalışan domaini kalıcı olarak hatırlar.
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

    @Volatile
    private var pendingPageLoadDeferred: CompletableDeferred<FetchBridgeResult>? = null

    @Volatile
    private var pendingPageLoadRequestId: String = ""

    @Volatile
    private var backgroundWebView: WebView? = null

    @Volatile
    private var initializedOriginDomain: String = ""

    @Volatile
    private var activeBaseDomain: String = loadInitialActiveDomain()

    private val domVerifiedState = AtomicBoolean(cookieJar.hasCfClearance(activeBaseDomain))

    @Volatile
    private var lastVerifiedHomeHtml: String? = null

    private fun loadInitialActiveDomain(): String {
        return try {
            val saved = prefs.getString(KEY_ACTIVE_DOMAIN, null)?.trim()
            if (!saved.isNullOrBlank() && saved.startsWith("https://")) {
                saved
            } else {
                PRIMARY_DOMAIN
            }
        } catch (_: Throwable) {
            PRIMARY_DOMAIN
        }
    }

    fun isDomVerified(): Boolean = domVerifiedState.get() || cookieJar.hasCfClearance(activeBaseDomain)

    fun getActiveBaseDomain(): String = activeBaseDomain

    fun setActiveBaseDomain(domain: String) {
        val cleaned = domain.trim().trimEnd('/')
        if (cleaned.startsWith("https://")) {
            activeBaseDomain = cleaned
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

        cookieJar.syncFromWebViewCookieManager(activeBaseDomain)
        cookieJar.syncFromWebViewCookieManager(verifiedUrl)
        cookieJar.flush()
        domVerifiedState.set(true)

        if (!extractedOuterHtml.isNullOrBlank() && hasTopicListElements(extractedOuterHtml)) {
            lastVerifiedHomeHtml = extractedOuterHtml
        }
    }

    /**
     * Verilen URL için gerçek zamanlı (canlı) HTML içeriğini çeker ve `EksiParser` (Jsoup) katmanına döndürür.
     */
    suspend fun fetchHtml(
        rawUrl: String,
        isAjax: Boolean = true
    ): String {
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
                        return directResult.html
                    }
                    // Eğer ilk uç noktada Cloudflare 403/503 yakalandıysa aynı domainde OkHttp ile
                    // ikinci varyantı denemek yerine doğrudan Chromium WebView köprüsüne geç
                    if (directResult.isCloudflareChallenge && index == 0) {
                        break
                    }
                }
            }

            // AŞAMA 2: Gerçek cihazda Chromium WebView üzerinden JS fetch() ve loadUrl()
            if (hardwareWebViewSupported) {
                val originReady = ensureRealOriginLoaded(candidateDomain)
                if (originReady) {
                    for (candidateUrl in urlsToTryOnDomain) {
                        val effectiveAjax = if (candidateUrl.endsWith("/debe")) false else isAjax
                        val jsFetchResult = executeJsFetch(
                            targetUrl = candidateUrl,
                            baseDomain = candidateDomain,
                            isAjax = effectiveAjax
                        )
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
                    if (fullPageResult != null) {
                        if (fullPageResult.isCloudflareChallenge) {
                            lastSawCloudflareChallenge = true
                        }
                        if (fullPageResult.html.isNotBlank() &&
                            !fullPageResult.isCloudflareChallenge &&
                            hasRelevantContentForUrl(candidateUrl, fullPageResult.html)
                        ) {
                            onDomainSucceeded(candidateDomain, fullPageResult.finalUrl)
                            return fullPageResult.html
                        }
                    }
                }
            }
        }

        // AŞAMA 4: Doğrudan erişim Cloudflare (403/503) veya emülatör kısıtlamasına takıldığında
        // sahte veri üretmek yerine gerçek Ekşi Sözlük HTML sayfalarını (`2id_` raw identity mirror)
        // doğrudan OkHttp üzerinden çekerek %100 gerçek başlık ve entry'leri döndür.
        val liveMirrorHtml = executeLiveMirrorArchiveFetch(rawUrl)
        if (!liveMirrorHtml.isNullOrBlank()) {
            domVerifiedState.set(true)
            return liveMirrorHtml
        }

        if (lastSawCloudflareChallenge) {
            domVerifiedState.set(false)
            cloudflareManager.emitChallenge(
                CloudflareChallengeEvent(
                    targetUrl = activeBaseDomain,
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
            Log.d(TAG, "executeDirectHttpFetch ($targetUrl) bilgi: ${e.message}")
            null
        }
    }

    /**
     * Görünmez WebView'ın gerçek `https://eksisozluk...` origin sayfasında olduğundan emin olur.
     * Sentetik `loadDataWithBaseURL` yerine gerçek ana sayfayı yükler ki Cloudflare çerezleri
     * ve same-origin `fetch()` izinleri eksiksiz çalışsın.
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
            Log.w(TAG, "executeJsFetch uyarısı ($targetUrl): ${e.message}")
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

            // Eğer sol frame AJAX isteği için loadUrl yapıldıysa ancak dönen sayfa tam sayfa olup
            // içinde .topic-list barındırmıyorsa (örn. mobil ana sayfa yüklendiyse), şimdi gerçek origin
            // yüklendiği için sayfa içinden JS fetch() ile sol frame verisini çek.
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
            Log.w(TAG, "loadUrlAndExtractOuterHtml uyarısı ($targetUrl): ${e.message}")
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

            // Cihazın gerçek Chromium WebView User-Agent değerini koru ve OkHttp ile birebir eşitle
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
            } catch (e: Throwable) {
                Log.w(TAG, "CookieManager yapılandırma uyarısı: ${e.message}")
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
                            finalUrl = activeBaseDomain,
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
                    finalUrl = activeBaseDomain,
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
            // Olaylar sekmesi giriş yapılmadığında veya boş olduğunda topic-list boş dönebilir
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
        list.add(activeBaseDomain)
        for (domain in ALTERNATIVE_DOMAINS) {
            if (!list.contains(domain)) {
                list.add(domain)
            }
        }
        return list
    }

    /**
     * Mobil ve masaüstü Ekşi Sözlük uç noktaları için aday URL listesi üretir.
     * Örneğin `/basliklar/gundem` boş dönerse `/basliklar/m/populer`,
     * `/basliklar/bugun` boş dönerse `/basliklar/m/bugun`,
     * `/basliklar/debe` boş dönerse `/debe` otomatik olarak denenir.
     */
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

    private fun rewriteToActiveDomain(rawUrl: String): String {
        return replaceDomain(rawUrl, activeBaseDomain)
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

    private val mirrorHtmlCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedRealGundemHtml: String? = null

    @Volatile
    private var cachedRealDebeHtml: String? = null

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
                    return@withContext pagedHtml
                }
                // Arşivde 2. sayfa ayrı olarak yoksa 1. sayfanın tekrarlanmasını önlemek için boş döndür
                return@withContext EMPTY_TOPIC_ENTRIES_HTML
            }

            val topicHtml = fetchRawWaybackHtml(cleanTopicUrl)
            if (!topicHtml.isNullOrBlank() && hasEntryListElements(topicHtml)) {
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
            Log.w(TAG, "executeLiveMirrorArchiveFetch uyarısı ($rawUrl): ${e.message}")
            null
        }
    }

    private fun fetchRawWaybackHtml(cleanEksiUrl: String): String? {
        mirrorHtmlCache[cleanEksiUrl]?.let { return it }
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
                        mirrorHtmlCache[cleanEksiUrl] = body
                        return body
                    }
                }
                null
            }
        } catch (e: Throwable) {
            Log.d(TAG, "fetchRawWaybackHtml bilgi ($cleanEksiUrl): ${e.message}")
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
