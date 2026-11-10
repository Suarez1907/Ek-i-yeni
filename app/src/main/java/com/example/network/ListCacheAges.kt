package com.example.network

enum class ListCacheAge {
    FRESH,
    STALE,
    EXPIRED
}

/**
 * Fresh list HTML is reused as-is. Stale list HTML is shown immediately while a
 * refresh runs. Anything older is a miss.
 */
object ListCacheAges {
    const val FRESH_MS = 90_000L
    const val STALE_MS = 30 * 60_000L

    fun classify(ageMs: Long, freshMs: Long = FRESH_MS, staleMs: Long = STALE_MS): ListCacheAge {
        if (ageMs < 0L) return ListCacheAge.EXPIRED
        return when {
            ageMs <= freshMs -> ListCacheAge.FRESH
            ageMs <= staleMs -> ListCacheAge.STALE
            else -> ListCacheAge.EXPIRED
        }
    }
}
