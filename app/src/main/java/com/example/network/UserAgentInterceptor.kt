package com.example.network

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cihazın WebView User-Agent bilgisini sağlayan ve çalışma zamanında
 * WebView ile OkHttp arasında birebir senkronize tutan sınıf.
 *
 * Cloudflare `cf_clearance` çerezi ve `Sec-CH-UA` Client Hints doğrulaması User-Agent'a
 * kilitli olduğundan, doğrudan sistemin kendi Chromium WebView User-Agent
 * değeri okunur ve WebView ile OkHttp arasında %100 aynı kullanılır.
 */
@Singleton
class UserAgentProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cachedUserAgent = AtomicReference<String?>(null)

    /**
     * OkHttp ve WebView tarafından ortak kullanılan birebir aynı User-Agent bilgisini döndürür.
     */
    fun getUserAgent(): String {
        cachedUserAgent.get()?.let { if (it.isNotBlank()) return it }
        val resolved = resolveInitialUserAgent()
        cachedUserAgent.compareAndSet(null, resolved)
        return cachedUserAgent.get() ?: resolved
    }

    /**
     * WebView başlatıldığında `webView.settings.userAgentString` değerini buraya kaydeder,
     * böylece OkHttp ile WebView arasında %100 karakter uyumu garanti altına alınır.
     */
    fun updateFromWebView(webViewUserAgent: String?) {
        if (!webViewUserAgent.isNullOrBlank()) {
            cachedUserAgent.set(webViewUserAgent.trim())
        }
    }

    private fun resolveInitialUserAgent(): String {
        if (WebViewCookieJar.isHardwareWebViewSupported()) {
            try {
                val systemUa = WebSettings.getDefaultUserAgent(context)?.trim()
                if (!systemUa.isNullOrBlank()) {
                    return systemUa
                }
            } catch (_: Throwable) {
            }
        }
        return buildDefaultChromeMobileUserAgent()
    }

    private fun buildDefaultChromeMobileUserAgent(): String {
        val release = Build.VERSION.RELEASE.ifBlank { "14" }
        val model = Build.MODEL.ifBlank { "Pixel 8" }
        return "Mozilla/5.0 (Linux; Android $release; $model) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * Tüm OkHttp isteklerine:
 * 1. WebView ile birebir aynı `User-Agent` başlığını ekler.
 * 2. Sol frame, gündem, bugün, olaylar, debe, kanal, arama ve AJAX uç noktalarına
 *    otomatik olarak `X-Requested-With: XMLHttpRequest` başlığını ekler.
 * 3. Cloudflare uyumluluğu için standart `Accept`, `Accept-Language` ve `Referer` başlıklarını tamamlar.
 */
@Singleton
class UserAgentInterceptor @Inject constructor(
    private val userAgentProvider: UserAgentProvider
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        // 1. Birebir aynı WebView User-Agent başlığını zorunlu kıl
        requestBuilder.header(HEADER_USER_AGENT, userAgentProvider.getUserAgent())

        // 2. Sol frame / gündem / arama / AJAX isteklerinde X-Requested-With: XMLHttpRequest ekle
        val isAjax = shouldAttachXmlHttpRequestHeader(originalRequest)
        if (isAjax) {
            requestBuilder.header(HEADER_X_REQUESTED_WITH, VALUE_XML_HTTP_REQUEST)
        }

        // 3. Eksikse standart tarayıcı başlıklarını ekle
        if (originalRequest.header(HEADER_ACCEPT) == null) {
            val acceptValue = if (isAjax) {
                "text/html, */*; q=0.01"
            } else {
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            }
            requestBuilder.header(HEADER_ACCEPT, acceptValue)
        }

        if (originalRequest.header(HEADER_ACCEPT_LANGUAGE) == null) {
            requestBuilder.header(HEADER_ACCEPT_LANGUAGE, "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
        }

        if (originalRequest.header(HEADER_REFERER) == null) {
            val baseReferer = "${originalRequest.url.scheme}://${originalRequest.url.host}/"
            requestBuilder.header(HEADER_REFERER, baseReferer)
        }

        return chain.proceed(requestBuilder.build())
    }

    /**
     * İsteğin sol frame, gündem veya AJAX isteği olup olmadığını belirler.
     * Ayrıca çağrı yapan kod `X-Force-Ajax: true` başlığı koyduysa veya URL sol frame
     * desenlerinden birine uyuyorsa `X-Requested-With: XMLHttpRequest` eklenir.
     */
    private fun shouldAttachXmlHttpRequestHeader(request: Request): Boolean {
        if (request.header(HEADER_X_REQUESTED_WITH) != null) {
            return true
        }
        if (request.header(HEADER_FORCE_AJAX) == "true") {
            return true
        }

        val encodedPath = request.url.encodedPath.lowercase()
        val query = request.url.encodedQuery?.lowercase().orEmpty()

        val isLeftFrameOrAjaxPath = AJAX_PATH_PREFIXES.any { prefix ->
            encodedPath.startsWith(prefix)
        }
        val isAjaxQuery = query.contains("searchform.keywords=") ||
            query.contains("_=")

        return isLeftFrameOrAjaxPath || isAjaxQuery
    }

    companion object {
        const val HEADER_USER_AGENT = "User-Agent"
        const val HEADER_X_REQUESTED_WITH = "X-Requested-With"
        const val HEADER_FORCE_AJAX = "X-Force-Ajax"
        const val HEADER_ACCEPT = "Accept"
        const val HEADER_ACCEPT_LANGUAGE = "Accept-Language"
        const val HEADER_REFERER = "Referer"
        const val VALUE_XML_HTTP_REQUEST = "XMLHttpRequest"

        private val AJAX_PATH_PREFIXES = listOf(
            "/basliklar/gundem",
            "/basliklar/bugun",
            "/basliklar/olay",
            "/basliklar/debe",
            "/basliklar/kanal",
            "/basliklar/ara",
            "/basliklar/m/",
            "/entry/favorileyenler",
            "/entry/oyla",
            "/autocomplete/"
        )
    }
}
