package com.example.network

import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android WebView [CookieManager] ile OkHttp [CookieJar] arayüzünü çift yönlü (bi-directional)
 * senkronize eden sınıf.
 *
 * Gerçek Android telefon ve tabletlerde sistem [CookieManager.getInstance] doğrudan başlatılır
 * ve WebView ile OkHttp arasındaki tüm oturum/`cf_clearance` çerezleri gerçek zamanlı eşitlenir.
 */
@Singleton
class WebViewCookieJar @Inject constructor() : CookieJar {

    private val inMemoryCookies = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val webViewBridgeEnabled = AtomicBoolean(false)

    @Volatile
    private var cachedCookieManager: CookieManager? = null

    /**
     * Sistem [CookieManager] köprüsünü etkinleştirir ve bellek içi çerezlerle çift yönlü eşitler.
     */
    fun enableWebViewBridge(): CookieManager? {
        if (!isHardwareWebViewSupported()) {
            return null
        }
        cachedCookieManager?.let { return it }
        return try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                cachedCookieManager = this
                webViewBridgeEnabled.set(true)
                for ((host, map) in inMemoryCookies) {
                    val hostUrl = "https://$host"
                    for ((k, v) in map) {
                        setCookie(hostUrl, "$k=$v; Path=/; Secure")
                    }
                }
                flush()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "CookieManager başlatılamadı: ${e.message}")
            null
        }
    }

    private fun getActiveCookieManagerIfEnabled(): CookieManager? {
        if (!isHardwareWebViewSupported()) return null
        return cachedCookieManager ?: enableWebViewBridge()
    }

    /**
     * WebView'ın sistem [CookieManager] deposundaki gerçek çerezleri okuyup
     * bellek içi OkHttp çerez deposuyla senkronize eder.
     */
    fun syncFromWebViewCookieManager(url: String) {
        val manager = getActiveCookieManagerIfEnabled() ?: return
        val httpUrl = url.toHttpUrlOrNull() ?: return
        try {
            val rawCookieHeader = manager.getCookie(url)
            if (!rawCookieHeader.isNullOrBlank()) {
                val hostMap = inMemoryCookies.getOrPut(httpUrl.host) { ConcurrentHashMap() }
                rawCookieHeader.split(";").forEach { segment ->
                    val trimmed = segment.trim()
                    val idx = trimmed.indexOf('=')
                    if (idx > 0) {
                        val key = trimmed.substring(0, idx).trim()
                        val value = trimmed.substring(idx + 1).trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            hostMap[key] = value
                        }
                    }
                }
            }
            manager.flush()
        } catch (e: Throwable) {
            Log.w(TAG, "syncFromWebViewCookieManager uyarısı: ${e.message}")
        }
    }

    /**
     * OkHttp yanıtından gelen `Set-Cookie` başlıklarını hem bellek içi depoya hem de
     * Android [CookieManager] içine yazar ve diske flush eder.
     */
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return

        val hostMap = inMemoryCookies.getOrPut(url.host) { ConcurrentHashMap() }
        for (cookie in cookies) {
            hostMap[cookie.name] = cookie.value
        }

        val manager = getActiveCookieManagerIfEnabled() ?: return
        val urlString = url.toString()
        for (cookie in cookies) {
            manager.setCookie(urlString, cookie.toString())
        }
        try {
            manager.flush()
        } catch (e: Throwable) {
            Log.w(TAG, "CookieManager.flush() uyarısı: ${e.message}")
        }
    }

    /**
     * OkHttp isteği çıkmadan hemen önce ilgili URL için tüm çerezleri okur,
     * ayrıştırır ve OkHttp [Cookie] listesi olarak döndürür.
     */
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val mergedPairs = LinkedHashMap<String, String>()

        inMemoryCookies[url.host]?.forEach { (k, v) ->
            mergedPairs[k] = v
        }

        val manager = getActiveCookieManagerIfEnabled()
        if (manager != null) {
            val rawCookieHeader = try {
                manager.getCookie(url.toString())
            } catch (e: Throwable) {
                null
            }
            if (!rawCookieHeader.isNullOrBlank()) {
                val hostMap = inMemoryCookies.getOrPut(url.host) { ConcurrentHashMap() }
                rawCookieHeader.split(";").forEach { segment ->
                    val trimmed = segment.trim()
                    val idx = trimmed.indexOf('=')
                    if (idx > 0) {
                        val key = trimmed.substring(0, idx).trim()
                        val value = trimmed.substring(idx + 1).trim()
                        if (key.isNotEmpty()) {
                            mergedPairs[key] = value
                            hostMap[key] = value
                        }
                    }
                }
            }
        }

        if (mergedPairs.isEmpty()) return emptyList()

        return mergedPairs.mapNotNull { (name, value) ->
            parseFallbackCookie(url, "$name=$value")
        }
    }

    private fun parseFallbackCookie(url: HttpUrl, rawPair: String): Cookie? {
        val separatorIndex = rawPair.indexOf('=')
        if (separatorIndex <= 0) return null
        val name = rawPair.substring(0, separatorIndex).trim()
        val value = rawPair.substring(separatorIndex + 1).trim()
        if (name.isEmpty()) return null

        return try {
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(url.host)
                .path("/")
                .apply {
                    if (url.isHttps) {
                        secure()
                    }
                }
                .build()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Verilen URL (varsayılan: https://eksisozluk.com) için `cf_clearance` çerezinin
     * mevcut ve dolu olup olmadığını kontrol eder.
     */
    fun hasCfClearance(url: String = DEFAULT_BASE_URL): Boolean {
        val cookies = getCookieMap(url)
        return !cookies[CF_CLEARANCE_COOKIE].isNullOrBlank()
    }

    /**
     * Kullanıcının Ekşi Sözlük'e giriş yapmış olup olmadığını çerezler üzerinden kontrol eder.
     */
    fun hasActiveUserSession(url: String = DEFAULT_BASE_URL): Boolean {
        val cookies = getCookieMap(url)
        return !cookies["a"].isNullOrBlank() ||
            !cookies[".ASPXAUTH"].isNullOrBlank() ||
            cookies.keys.any { it.equals("iq", ignoreCase = true) }
    }

    /**
     * Belirtilen URL için tüm çerezleri [Map] (isim -> değer) olarak döndürür.
     */
    fun getCookieMap(url: String = DEFAULT_BASE_URL): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null) {
            inMemoryCookies[httpUrl.host]?.forEach { (k, v) ->
                result[k] = v
            }
        }

        val manager = getActiveCookieManagerIfEnabled()
        if (manager != null) {
            val raw = try {
                manager.getCookie(url)
            } catch (e: Throwable) {
                null
            }
            if (!raw.isNullOrBlank()) {
                val hostMap = httpUrl?.let { inMemoryCookies.getOrPut(it.host) { ConcurrentHashMap() } }
                raw.split(";").forEach { part ->
                    val trimmed = part.trim()
                    val idx = trimmed.indexOf('=')
                    if (idx > 0) {
                        val key = trimmed.substring(0, idx).trim()
                        val value = trimmed.substring(idx + 1).trim()
                        result[key] = value
                        hostMap?.put(key, value)
                    }
                }
            }
        }
        return result
    }

    /**
     * WebView üzerinden manuel çerez senkronizasyonu veya dışarıdan çerez enjeksiyonu için yardımcı metot.
     */
    fun setCookieExplicitly(url: String, cookieString: String) {
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null) {
            val firstSegment = cookieString.substringBefore(";").trim()
            val idx = firstSegment.indexOf('=')
            if (idx > 0) {
                val key = firstSegment.substring(0, idx).trim()
                val value = firstSegment.substring(idx + 1).trim()
                val hostMap = inMemoryCookies.getOrPut(httpUrl.host) { ConcurrentHashMap() }
                hostMap[key] = value
            }
        }

        val manager = getActiveCookieManagerIfEnabled() ?: return
        manager.setCookie(url, cookieString)
        try {
            manager.flush()
        } catch (e: Throwable) {
            Log.w(TAG, "setCookieExplicitly flush uyarısı: ${e.message}")
        }
    }

    /**
     * Çerezleri kalıcı depolamaya senkronize eder.
     */
    fun flush() {
        try {
            getActiveCookieManagerIfEnabled()?.flush()
        } catch (e: Throwable) {
            Log.w(TAG, "Flush uyarısı: ${e.message}")
        }
    }

    /**
     * Tüm çerezleri temizler (çıkış yapma veya oturum sıfırlama için).
     */
    fun clearAllCookies(onComplete: ((Boolean) -> Unit)? = null) {
        inMemoryCookies.clear()
        val manager = getActiveCookieManagerIfEnabled()
        if (manager == null) {
            onComplete?.invoke(true)
            return
        }
        try {
            manager.removeAllCookies { removed ->
                manager.flush()
                onComplete?.invoke(removed)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "clearAllCookies uyarısı: ${e.message}")
            onComplete?.invoke(false)
        }
    }

    /**
     * Verilen URL'yi HttpUrl olarak doğrulayıp mevcut çerezleri liste halinde döndürür.
     */
    fun getCookiesForUrl(url: String): List<Cookie> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()
        return loadForRequest(httpUrl)
    }

    companion object {
        private const val TAG = "WebViewCookieJar"
        const val DEFAULT_BASE_URL = "https://eksisozluk.com"
        const val CF_CLEARANCE_COOKIE = "cf_clearance"

        /**
         * Gerçek fiziksel Android telefon ve tabletlerde her zaman `true` döner.
         * Bulut/sanal x86_64 emülatörlerde ise yalnızca `/dev/dri/renderD128` mevcut ve okunabilirse `true`,
         * aksi halde Chromium `Failed to open rendernode` hatasını önlemek için `false` döner.
         */
        fun isHardwareWebViewSupported(): Boolean {
            return try {
                val primaryAbi = Build.SUPPORTED_ABIS?.firstOrNull()?.lowercase().orEmpty()
                val fingerprint = Build.FINGERPRINT.lowercase()
                val hardware = Build.HARDWARE.lowercase()
                val product = Build.PRODUCT.lowercase()
                val model = Build.MODEL.lowercase()
                val brand = Build.BRAND.lowercase()

                val isEmulatorOrVirtual = primaryAbi.startsWith("x86") ||
                    fingerprint.contains("generic") ||
                    fingerprint.contains("sdk_gphone") ||
                    fingerprint.contains("emulator") ||
                    fingerprint.contains("test-keys") ||
                    fingerprint.contains("robolectric") ||
                    hardware.contains("ranchu") ||
                    hardware.contains("goldfish") ||
                    hardware.contains("cutf") ||
                    hardware.contains("vsoc") ||
                    product.contains("sdk") ||
                    product.contains("emulator") ||
                    product.contains("aosp") ||
                    model.contains("sdk") ||
                    model.contains("emulator") ||
                    brand.contains("generic")

                if (isEmulatorOrVirtual) {
                    val renderNode = File("/dev/dri/renderD128")
                    return renderNode.exists() && renderNode.canRead()
                }

                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
