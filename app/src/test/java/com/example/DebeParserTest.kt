package com.example

import com.example.data.remote.EksiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fixture is a trimmed copy of the 17 Nov 2026 Wayback snapshot of
 * https://eksisozluk.com/debe. The left-hand DEBE list has titles and entry
 * links only. Favorite counts are on the unrelated home-page entry cards.
 */
@RunWith(RobolectricTestRunner::class)
class DebeParserTest {
    private val parser = EksiParser()

    @Test
    fun debeList_readsTitlesWithoutInventingFavoriteCounts() {
        val html = javaClass.classLoader!!
            .getResourceAsStream("debe-2026-11-17.html")!!
            .bufferedReader()
            .readText()
        val items = parser.parseDebeEntriesList(html)
        assertEquals(listOf("haluk bilginer", "ayşe şasa", "sosyalizm"), items.map { it.title })
        assertEquals(listOf("187921780", "187922622", "187930887"), items.map { it.entryId })
        assertTrue(items.all { it.favoriteCount == 0 })
        assertTrue(items.none { it.title.contains("muhteşem") })
    }

    @Test
    fun entryCard_readsFavoriteCountFromTheRealAttribute() {
        val html = javaClass.classLoader!!
            .getResourceAsStream("debe-2026-11-17.html")!!
            .bufferedReader()
            .readText()
        val metadata = parser.parseEntryDetailMetadata(html, "56358501")
        assertEquals(3069, metadata?.favoriteCount)
        assertEquals("bob hite", metadata?.author)
    }
}
