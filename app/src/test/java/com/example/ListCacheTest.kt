package com.example

import com.example.network.ContentSource
import com.example.network.ListCacheAge
import com.example.network.ListCacheAges
import com.example.network.ListHtmlDiskCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ListCacheTest {
    @Test
    fun classify_freshStaleAndExpired() {
        assertEquals(ListCacheAge.FRESH, ListCacheAges.classify(1_000L))
        assertEquals(ListCacheAge.STALE, ListCacheAges.classify(ListCacheAges.FRESH_MS + 1))
        assertEquals(ListCacheAge.EXPIRED, ListCacheAges.classify(ListCacheAges.STALE_MS + 1))
    }

    @Test
    fun diskCache_roundTripAndExpiry() {
        val dir = File("build/test-list-cache").apply {
            deleteRecursively()
            mkdirs()
        }
        val cache = ListHtmlDiskCache(dir, maxEntries = 4)
        val now = 1_700_000_000_000L
        cache.write("AJAX|https://eksisozluk.com/basliklar/gundem", "<ul class=\"topic-list\"></ul>", ContentSource.LIVE, now)
        val hit = cache.read("AJAX|https://eksisozluk.com/basliklar/gundem", now + 1_000L)
        assertNotNull(hit)
        assertEquals(ContentSource.LIVE, hit!!.source)
        assertEquals(ListCacheAge.FRESH, ListCacheAges.classify(hit.ageMs))
        assertNull(cache.read("AJAX|https://eksisozluk.com/basliklar/gundem", now + ListCacheAges.STALE_MS + 5_000L))
    }
}
