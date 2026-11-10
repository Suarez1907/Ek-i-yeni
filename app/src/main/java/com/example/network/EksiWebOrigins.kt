package com.example.network

import android.net.Uri

/**
 * Origins the hidden fetch WebView and the auth WebView are allowed to script or navigate.
 */
object EksiWebOrigins {
    val MESSAGE_LISTENER_RULES: Set<String> = setOf(
        "https://eksisozluk.com",
        "https://eksisozluk111.com",
        "https://eksisozluk1923.com",
        "https://*.eksisozluk.com"
    )

    fun isAllowedOrigin(origin: Uri?): Boolean {
        val host = origin?.host?.lowercase() ?: return false
        if (origin.scheme?.equals("https", ignoreCase = true) != true) return false
        return isEksiHost(host)
    }

    fun isAllowedNavigation(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.trim().lowercase()
        if (lower.startsWith("about:blank")) return true
        if (
            lower.startsWith("javascript:") ||
            lower.startsWith("data:") ||
            lower.startsWith("file:") ||
            lower.startsWith("content:")
        ) {
            return false
        }
        val uri = try {
            Uri.parse(url)
        } catch (_: Throwable) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        if (isEksiHost(host)) return true
        // Cloudflare Turnstile / interstitial assets sometimes navigate the main frame.
        return host == "challenges.cloudflare.com" || host.endsWith(".cloudflare.com")
    }

    fun isEksiHost(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized == "eksisozluk.com" ||
            normalized.endsWith(".eksisozluk.com") ||
            normalized == "eksisozluk111.com" ||
            normalized == "eksisozluk1923.com"
    }
}
