package com.example

import com.example.data.remote.EksiParser
import com.example.network.ArchiveMatch
import com.example.util.runCatchingUnlessCancelled
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CoreFixTest {

    @Test
    fun runCatchingUnlessCancelled_rethrowsCancellation() {
        var threw = false
        try {
            runCatchingUnlessCancelled { throw CancellationException("stop") }
        } catch (cancelled: CancellationException) {
            threw = cancelled.message == "stop"
        }
        assertTrue(threw)
    }

    @Test
    fun runCatchingUnlessCancelled_capturesOtherFailures() {
        val result = runCatchingUnlessCancelled<String> { error("boom") }
        assertTrue(result.isFailure)
    }

    @Test
    fun archiveMatch_doesNotReturnUnrelatedFirstLink() {
        val links = listOf(
            "bambaşka başlık" to "/baska--1",
            "jetpack compose" to "/jetpack-compose--2"
        )
        assertEquals("/jetpack-compose--2", ArchiveMatch.selectTopicHref(links, "jetpack compose"))
        assertNull(ArchiveMatch.selectTopicHref(links, "hiç yok"))
        assertNull(ArchiveMatch.selectTopicHref(links, "   "))
    }

    @Test
    fun archiveMatch_searchDoesNotPadWithUnrelatedItems() {
        val items = listOf("spor", "siyaset", "kotlin coroutines")
        assertEquals(listOf(2), ArchiveMatch.matchingIndexes(items, "kotlin"))
        assertTrue(ArchiveMatch.matchingIndexes(items, "zzzz").isEmpty())
        assertTrue(ArchiveMatch.matchingIndexes(items, "  ").isEmpty())
    }

    @Test
    fun currentYear_usesCalendarYear() {
        val parser = EksiParser()
        val year = Calendar.getInstance().get(Calendar.YEAR)
        assertTrue(parser.isCurrentYearEntryDate("05.11.$year 21:15"))
        assertTrue(parser.isCurrentYearEntryDate("bugün 21:15"))
        assertTrue(!parser.isCurrentYearEntryDate("05.11.${year - 2} 21:15"))
    }
}
