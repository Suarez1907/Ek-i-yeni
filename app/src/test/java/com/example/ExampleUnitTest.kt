package com.example

import com.example.data.model.SearchFilter
import com.example.data.model.SearchSortOrder
import com.example.data.remote.EksiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleUnitTest {

    private val parser = EksiParser()

    @Test
    fun buildSearchUrl_encodesKeywordsSortOrderAndPage() {
        val url = parser.buildSearchUrl(
            filter = SearchFilter(
                keywords = "yapay zeka",
                sortOrder = SearchSortOrder.COUNT
            ),
            page = 3
        )
        assertTrue(url.contains("/basliklar/ara?"))
        assertTrue(url.contains("SearchForm.Keywords="))
        assertTrue(url.contains("SearchForm.SortOrder=Count"))
        assertTrue(url.contains("p=3"))
    }

    @Test
    fun stripTrackedParameters_removesTrackedQueryAndKeepsBaseTopicUrl() {
        val rawTrackedUrl = "https://eksisozluk.com/eksin-oled-alternatifi--7890123?a=tracked&p=2"
        val stripped = parser.stripTrackedParameters(rawTrackedUrl)
        assertEquals("https://eksisozluk.com/eksin-oled-alternatifi--7890123", stripped)
        assertFalse(stripped.contains("a=tracked"))
    }

    @Test
    fun parseLeftFrameTopics_parsesValidHtmlCorrectly() {
        val sampleGundemHtml = """
            <!DOCTYPE html>
            <html lang="tr">
            <body>
                <ul class="topic-list partial">
                    <li>
                        <a href="/yapay-zeka-mimarisi--7894120?a=popular">yapay zeka mimarisi <small>42</small></a>
                    </li>
                    <li>
                        <a href="/jetpack-compose-performans--7894121?a=popular">jetpack compose performans <small>18</small></a>
                    </li>
                </ul>
            </body>
            </html>
        """.trimIndent()

        val topics = parser.parseLeftFrameTopics(sampleGundemHtml, isTrackedSection = false)
        assertEquals(2, topics.size)
        assertEquals("yapay zeka mimarisi", topics.first().title)
        assertEquals(42, topics.first().entryCount)
    }

    @Test
    fun parseTopicPage_extractsEntriesAndBkzAnnotations() {
        val sampleTopicHtml = """
            <!DOCTYPE html>
            <html lang="tr">
            <body>
                <div id="topic">
                    <h1 id="title" data-title="eksin oled" data-id="7894120">
                        <a href="/eksin-oled--7894120">eksin oled</a>
                    </h1>
                    <div class="pager" data-currentpage="1" data-pagecount="4"></div>
                    <ul id="entry-item-list">
                        <li data-id="169000101" data-author="gece_okuru" data-favorite-count="27">
                            <div class="content">gerçek siyah tema ve (bkz: jetpack compose) desteği harika olmuş.</div>
                            <footer>
                                <div class="info">
                                    <a class="entry-date permalink" href="/entry/169000101">05.11.2026 21:15</a>
                                    <a class="entry-author" href="/biri/gece_okuru">gece_okuru</a>
                                </div>
                            </footer>
                        </li>
                    </ul>
                </div>
            </body>
            </html>
        """.trimIndent()

        val pageData = parser.parseTopicPage(
            html = sampleTopicHtml,
            requestedUrl = "https://eksisozluk.com/eksin-oled--7894120",
            fallbackPage = 1
        )
        assertEquals("eksin oled", pageData.topicTitle)
        assertEquals(4, pageData.pageCount)
        assertEquals(1, pageData.entries.size)
        val firstEntry = pageData.entries.first()
        assertNotNull(firstEntry.annotatedContent)
        assertTrue(firstEntry.plainText.contains("jetpack compose"))
    }
}
