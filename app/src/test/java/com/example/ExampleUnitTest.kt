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

    @Test
    fun inTopicSearch_doesNotInventEntriesWhenNothingMatches() {
        val html = """
            <div id="topic"><h1 id="title" data-title="test" data-id="1"><a href="/test--1">test</a></h1>
            <ul id="entry-item-list">
              <li data-id="10" data-author="a" data-favorite-count="1">
                <div class="content">kediler hakkında bir entry</div>
              </li>
            </ul></div>
        """.trimIndent()
        val page = parser.parseTopicPage(
            html = html,
            requestedUrl = "https://eksisozluk.com/test--1?a=search&keywords=zzzz-yok",
            fallbackPage = 1
        )
        assertTrue(page.entries.isEmpty())
    }

    @Test
    fun normalizeUrl_doesNotPrefixJavascript() {
        assertEquals("", parser.normalizeUrl("javascript:alert(1)"))
        assertTrue(parser.normalizeUrl("/entry/10").startsWith("https://eksisozluk.com/entry/10"))
    }

    @Test
    fun parseEntryDetailMetadata_selectsRequestedEntryNotFirstInDocument() {
        val html = """
            <div id="topic"><h1 id="title" data-title="başlık">başlık</h1>
            <ul id="entry-item-list">
              <li data-id="111" data-author="ilk" data-favorite-count="3"><div class="content">a</div></li>
              <li data-id="222" data-author="ikinci" data-favorite-count="9"><div class="content">b</div></li>
            </ul></div>
        """.trimIndent()
        val metadata = parser.parseEntryDetailMetadata(html, targetEntryId = "222")
        assertNotNull(metadata)
        assertEquals("222", metadata!!.entryId)
        assertEquals(9, metadata.favoriteCount)
        assertEquals("ikinci", metadata.author)
    }

    @Test
    fun hiddenBkz_requiresClassTokenNotSubstring() {
        val html = """
            <div id="topic"><h1 id="title" data-title="başlık" data-id="1"><a href="/x--1">başlık</a></h1>
            <ul id="entry-item-list">
              <li data-id="10" data-author="a" data-favorite-count="1">
                <div class="content">
                  <a class="subtitle" href="https://example.com">*</a>
                  <sup class="ab"><a class="b" href="/?q=gercek">*</a></sup>
                </div>
              </li>
            </ul></div>
        """.trimIndent()
        val page = parser.parseTopicPage(html, "https://eksisozluk.com/x--1", 1)
        val annotations = page.entries.first().annotatedContent.getStringAnnotations(
            0,
            page.entries.first().annotatedContent.length
        )
        assertTrue(annotations.any { it.tag == EksiParser.TAG_HIDDEN_BKZ && it.item.contains("gercek") })
        assertTrue(annotations.none { it.tag == EksiParser.TAG_HIDDEN_BKZ && it.item.contains("example") })
    }
}
