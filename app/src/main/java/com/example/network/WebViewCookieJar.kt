package com.example.network

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android WebView [CookieManager] ile OkHttp [CookieJar] arayüzünü çift yönlü senkronize eder.
 * Süresi dolmuş ve silinmiş çerezler bellekten düşer. Disk flush'ı her yanıtta değil, en fazla
 * 2 saniyede bir yapılır.
 */
@Singleton
class WebViewCookieJar @Inject constructor() : CookieJar {

    private val inMemoryCookies = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()
    private val webViewBridgeEnabled = AtomicBoolean(false)
    private val lastFlushElapsedMs = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedCookieManager: CookieManager? = null

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
                    for (cookie in map.values) {
                        if (!isExpired(cookie)) {
                            setCookie(hostUrl, cookie.toString())
                        }
                    }
                }
                flushNow(this)
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

    fun syncFromWebViewCookieManager(url: String) {
        val manager = getActiveCookieManagerIfEnabled() ?: return
        val httpUrl = url.toHttpUrlOrNull() ?: return
        try {
            val rawCookieHeader = manager.getCookie(url)
            replaceHostCookies(httpUrl, rawCookieHeader)
        } catch (e: Throwable) {
            Log.w(TAG, "syncFromWebViewCookieManager uyarısı: ${e.message}")
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return

        val hostMap = inMemoryCookies.getOrPut(url.host) { ConcurrentHashMap() }
        val manager = getActiveCookieManagerIfEnabled()
        val urlString = url.toString()
        for (cookie in cookies) {
            val removed = cookie.value.isEmpty() || isExpired(cookie)
            if (removed) {
                hostMap.remove(cookie.name)
                manager?.setCookie(
                    urlString,
                    "${cookie.name}=; Max-Age=0; Path=${cookie.path.ifBlank { "/" }}"
                )
            } else {
                hostMap[cookie.name] = cookie
                manager?.setCookie(urlString, cookie.toString())
            }
        }
        if (manager != null) {
            scheduleFlush(manager)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val hostMap = inMemoryCookies.getOrPut(url.host) { ConcurrentHashMap() }
        val manager = getActiveCookieManagerIfEnabled()
        if (manager != null) {
            val rawCookieHeader = try {
                manager.getCookie(url.toString())
            } catch (_: Throwable) {
                null
            }
            if (rawCookieHeader != null) {
                replaceHostCookies(url, rawCookieHeader)
            }
        }
        dropExpired(hostMap)
        if (hostMap.isEmpty()) return emptyList()
        return hostMap.values.filter { cookie -> cookie.matches(url) && !isExpired(cookie) }
    }

    private fun replaceHostCookies(url: HttpUrl, rawCookieHeader: String?) {
        val hostMap = ConcurrentHashMap<String, Cookie>()
        if (!rawCookieHeader.isNullOrBlank()) {
            rawCookieHeader.split(";").forEach { segment ->
                parseSessionCookie(url, segment.trim())?.let { cookie ->
                    hostMap[cookie.name] = cookie
                }
            }
        }
        inMemoryCookies[url.host] = hostMap
    }

    private fun parseSessionCookie(url: HttpUrl, rawPair: String): Cookie? {
        val separatorIndex = rawPair.indexOf('=')
        if (separatorIndex <= 0) return null
        val name = rawPair.substring(0, separatorIndex).trim()
        val value = rawPair.substring(separatorIndex + 1).trim()
        if (name.isEmpty() || value.isEmpty()) return null
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
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun dropExpired(hostMap: ConcurrentHashMap<String, Cookie>) {
        val now = System.currentTimeMillis()
        val iterator = hostMap.entries.iterator()
        while (iterator.hasNext()) {
            val cookie = iterator.next().value
            if (cookie.expiresAt <= now) {
                iterator.remove()
            }
        }
    }

    private fun isExpired(cookie: Cookie): Boolean {
        return cookie.expiresAt <= System.currentTimeMillis()
    }

    fun hasCfClearance(url: String = DEFAULT_BASE_URL): Boolean {
        val cookies = getCookieMap(url)
        return !cookies[CF_CLEARANCE_COOKIE].isNullOrBlank()
    }

    fun hasActiveUserSession(url: String = DEFAULT_BASE_URL): Boolean {
        val cookies = getCookieMap(url)
        return !cookies["a"].isNullOrBlank() ||
            !cookies[".ASPXAUTH"].isNullOrBlank() ||
            cookies.keys.any { it.equals("iq", ignoreCase = true) }
    }

    fun getCookieMap(url: String = DEFAULT_BASE_URL): Map<String, String> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyMap()
        return loadForRequest(httpUrl).associate { it.name to it.value }
    }

    fun setCookieExplicitly(url: String, cookieString: String) {
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null) {
            val firstSegment = cookieString.substringBefore(";").trim()
            parseSessionCookie(httpUrl, firstSegment)?.let { cookie ->
                val hostMap = inMemoryCookies.getOrPut(httpUrl.host) { ConcurrentHashMap() }
                if (cookie.value.isEmpty()) {
                    hostMap.remove(cookie.name)
                } else {
                    hostMap[cookie.name] = cookie
                }
            }
        }

        val manager = getActiveCookieManagerIfEnabled() ?: return
        manager.setCookie(url, cookieString)
        scheduleFlush(manager)
    }

    fun flush() {
        try {
            getActiveCookieManagerIfEnabled()?.let { flushNow(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "Flush uyarısı: ${e.message}")
        }
    }

    private fun scheduleFlush(manager: CookieManager) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastFlushElapsedMs.get()
        if (now - previous < FLUSH_INTERVAL_MS) return
        if (!lastFlushElapsedMs.compareAndSet(previous, now)) return
        mainHandler.post {
            try {
                manager.flush()
            } catch (e: Throwable) {
                Log.w(TAG, "CookieManager.flush() uyarısı: ${e.message}")
            }
        }
    }

    private fun flushNow(manager: CookieManager) {
        lastFlushElapsedMs.set(SystemClock.elapsedRealtime())
        try {
            manager.flush()
        } catch (e: Throwable) {
            Log.w(TAG, "CookieManager.flush() uyarısı: ${e.message}")
        }
    }

    fun clearAllCookies(onComplete: ((Boolean) -> Unit)? = null) {
        inMemoryCookies.clear()
        val manager = getActiveCookieManagerIfEnabled()
        if (manager == null) {
            onComplete?.invoke(true)
            return
        }
        try {
            manager.removeAllCookies { removed ->
                flushNow(manager)
                onComplete?.invoke(removed)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "clearAllCookies uyarısı: ${e.message}")
            onComplete?.invoke(false)
        }
    }

    fun getCookiesForUrl(url: String): List<Cookie> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()
        return loadForRequest(httpUrl)
    }

    companion object {
        private const val TAG = "WebViewCookieJar"
        const val DEFAULT_BASE_URL = "https://eksisozluk.com"
        const val CF_CLEARANCE_COOKIE = "cf_clearance"
        private const val FLUSH_INTERVAL_MS = 2_000L

        fun isHardwareWebViewSupported(): Boolean {
            return try {
                val fingerprint = Build.FINGERPRINT?.lowercase().orEmpty()
                !fingerprint.contains("robolectric")
            } catch (_: Throwable) {
                true
            }
        }
    }
}
