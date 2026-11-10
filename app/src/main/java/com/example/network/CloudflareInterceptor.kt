package com.example.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI katmanına gönderilen Cloudflare doğrulama sinyali modeli.
 */
data class CloudflareChallengeEvent(
    val targetUrl: String,
    val statusCode: Int,
    val reason: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * OkHttp katmanından yakalanan Cloudflare 403 / 503 challenge yanıtlarını
 * bir [SharedFlow] üzerinden UI katmanına ileten merkezi yönetici.
 */
@Singleton
class CloudflareChallengeManager @Inject constructor() {

    private val _challengeEvents = MutableSharedFlow<CloudflareChallengeEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * UI katmanının dinlediği "Cloudflare Doğrulama Ekranını Aç" sinyal akışı.
     */
    val challengeEvents: SharedFlow<CloudflareChallengeEvent> = _challengeEvents.asSharedFlow()

    @Volatile
    private var lastEmittedAtMs: Long = 0L

    @Volatile
    private var isVerificationDialogOpen: Boolean = false

    fun setVerificationScreenActive(active: Boolean) {
        isVerificationDialogOpen = active
    }

    fun emitChallenge(event: CloudflareChallengeEvent) {
        val now = System.currentTimeMillis()
        // Aynı anda birden fazla paralel Paging/sol frame isteği 403 aldığında
        // ekranın üst üste tetiklenmesini önlemek için debounce uygula.
        if (isVerificationDialogOpen || (now - lastEmittedAtMs) < DEBOUNCE_WINDOW_MS) {
            return
        }
        lastEmittedAtMs = now
        _challengeEvents.tryEmit(event)
    }

    /**
     * Kullanıcı manuel olarak Cloudflare veya Giriş ekranını açmak istediğinde tetiklenir.
     */
    fun requestManualVerification(targetUrl: String = WebViewCookieJar.DEFAULT_BASE_URL) {
        lastEmittedAtMs = System.currentTimeMillis()
        _challengeEvents.tryEmit(
            CloudflareChallengeEvent(
                targetUrl = targetUrl,
                statusCode = 403,
                reason = "Manuel doğrulama / oturum açma isteği"
            )
        )
    }

    companion object {
        private const val DEBOUNCE_WINDOW_MS = 3_000L
    }
}

/**
 * OkHttp yanıtlarını denetleyen ve HTTP 403 veya 503 (Cloudflare challenge / Turnstile / IUAM)
 * durumunda [CloudflareChallengeManager] üzerinden UI katmanına
 * "Cloudflare Doğrulama Ekranını Aç" sinyali gönderen Interceptor.
 */
@Singleton
class CloudflareInterceptor @Inject constructor(
    private val challengeManager: CloudflareChallengeManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val code = response.code
        if (code == 403 || code == 503) {
            if (isCloudflareChallenge(response)) {
                val targetUrl = buildSafeVerificationUrl(request.url.toString())
                challengeManager.emitChallenge(
                    CloudflareChallengeEvent(
                        targetUrl = targetUrl,
                        statusCode = code,
                        reason = "Cloudflare güvenlik doğrulaması gerekiyor (HTTP $code)"
                    )
                )
            }
        }

        return response
    }

    /**
     * Yanıtın gerçekten bir Cloudflare challenge olup olmadığını başlıklar ve gövde önizlemesi
     * (peekBody) üzerinden belleği tüketmeden tespit eder.
     */
    private fun isCloudflareChallenge(response: Response): Boolean {
        val cfMitigated = response.header("cf-mitigated").orEmpty()
        if (cfMitigated.contains("challenge", ignoreCase = true)) {
            return true
        }

        val serverHeader = response.header("Server").orEmpty()
        val cfRay = response.header("CF-Ray").orEmpty()
        val hasCloudflareHeaders = serverHeader.contains("cloudflare", ignoreCase = true) ||
            cfRay.isNotBlank()

        // Gövdenin ilk 8 KB'lık kısmına akışı bozmadan (peekBody) bak
        val bodyPreview = try {
            response.peekBody(8_192L).string()
        } catch (e: Throwable) {
            ""
        }

        val hasChallengeMarkers = CHALLENGE_BODY_MARKERS.any { marker ->
            bodyPreview.contains(marker, ignoreCase = true)
        }

        return hasChallengeMarkers
    }

    /**
     * Eğer 403 alan istek bir arka plan AJAX uç noktasıysa, WebView'da tam sayfa challenge
     * çözülebilmesi için ana domain veya ilgili başlık sayfasına yönlendirir.
     */
    private fun buildSafeVerificationUrl(rawUrl: String): String {
        return if (rawUrl.contains("X-Requested-With", ignoreCase = true) ||
            rawUrl.contains("/autocomplete/") ||
            rawUrl.contains("_=")
        ) {
            WebViewCookieJar.DEFAULT_BASE_URL
        } else {
            rawUrl
        }
    }

    companion object {
        private val CHALLENGE_BODY_MARKERS = listOf(
            "cf-browser-verification",
            "cf_chl_opt",
            "__cf_chl_",
            "Just a moment...",
            "Bir dakika lütfen...",
            "Attention Required! | Cloudflare",
            "challenge-platform",
            "turnstile",
            "Ray ID:"
        )
    }
}
