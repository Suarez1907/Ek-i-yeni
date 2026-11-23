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
    fun parseDebeEntriesList_sortsByFavoriteCountDescending() {
        val debeHtml = """
            <html>
            <body>
                <ol class="debe-list">
                    <li data-favorite-count="12"><a href="/entry/101">on iki beğenili konu</a></li>
                    <li data-favorite-count="57"><a href="/entry/102">elli yedi beğenili konu</a></li>
                    <li data-favorite-count="31"><a href="/entry/103">otuz bir beğenili konu</a></li>
                </ol>
            </body>
            </html>
        """.trimIndent()

        val items = parser.parseDebeEntriesList(debeHtml)

        assertEquals(listOf(57, 31, 12), items.map { it.favoriteCount })
        assertEquals(listOf(1, 2, 3), items.map { it.rank })
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
    fun parseAuthorProfileEntries_extractsTopicItemEntriesAndFavorites() {
        val partialHtml = """
            <div class="topic-item">
                <h1 id="title"><a href="/yapay-zeka--7894120">yapay zeka</a></h1>
                <ul id="entry-item-list">
                    <li data-id="169000101" data-author="gece_okuru" data-favorite-count="27">
                        <div class="content">profil partial içeriği: jsoup ile ayrıştırıldı.</div>
                        <a class="entry-date" href="/entry/169000101">05.11.2026 21:15</a>
                    </li>
                </ul>
            </div>
        """.trimIndent()

        val entries = parser.parseAuthorProfileEntries(partialHtml, fallbackAuthor = "fallback_yazar")

        assertEquals(1, entries.size)
        val firstEntry = entries.first()
        assertEquals("yapay zeka", firstEntry.topicTitle)
        assertEquals("169000101", firstEntry.id)
        assertEquals(27, firstEntry.favoriteCount)
        assertEquals("05.11.2026 21:15", firstEntry.date)
        assertEquals("gece_okuru", firstEntry.author)
        assertEquals("https://eksisozluk.com/yapay-zeka--7894120", firstEntry.topicUrl)
        assertTrue(firstEntry.plainText.contains("profil partial içeriği"))
        assertTrue(firstEntry.plainText.contains("jsoup ile ayrıştırıldı"))
    }

    @Test
    fun parseCurrentUsername_extractsLoggedInNickFromTopBar() {
        val html = """
            <html><body>
                <ul id="top-navigation">
                    <li><a href="/biri/gece_okuru" class="username">gece_okuru</a></li>
                </ul>
            </body></html>
        """.trimIndent()

        val nick = parser.parseCurrentUsername(html)
        assertEquals("gece_okuru", nick)
    }

    @Test
    fun parseEntryFormMetadata_extractsTokenTitleAndTopicId() {
        val html = """
            <html><body>
                <form id="entry-add-form" action="/entry/ekle">
                    <input type="hidden" name="__RequestVerificationToken" value="abc123token" />
                    <input type="hidden" name="Id" value="7894120" />
                    <input type="hidden" name="Title" value="eksin oled" />
                </form>
            </body></html>
        """.trimIndent()

        val metadata = parser.parseEntryFormMetadata(html)
        assertNotNull(metadata)
        assertEquals("abc123token", metadata?.verificationToken)
        assertEquals("7894120", metadata?.topicId)
    }

    @Test
    fun parseAuthorProfile_extractsStatsAndRecentEntries() {
        val html = """
            <html><body>
                <section class="author-profile">
                    <h1 class="username">gece_okuru</h1>
                    <div class="stats">
                        <span class="entry-count">128</span>
                        <span class="follower-count">64</span>
                    </div>
                    <ul class="recent-entries">
                        <li><a href="/entry/169000101">bir entry</a></li>
                        <li><a href="/entry/169000102">ikinci entry</a></li>
                    </ul>
                </section>
            </body></html>
        """.trimIndent()

        val profile = parser.parseAuthorProfile(html, requestedNick = "gece_okuru")
        assertNotNull(profile)
    }

    @Test
    fun parseMessageThreads_extractsThreadsAndUnreadState() {
        val html = """
            <html><body>
                <ul class="message-threads">
                    <li class="thread unread">
                        <a href="/mesajlar/thread/42">ilk konuşma</a>
                        <span class="unread-count">2</span>
                    </li>
                    <li class="thread">
                        <a href="/mesajlar/thread/43">ikinci konuşma</a>
                    </li>
                </ul>
            </body></html>
        """.trimIndent()

        val threads = parser.parseMessageThreads(html)
        assertNotNull(threads)
    }

    @Test
    fun parseMessageThreadDetail_extractsMessagesAndReplyToken() {
        val html = """
            <html><body>
                <div class="message-thread">
                    <ul class="messages">
                        <li class="message"><div class="content">merhaba</div></li>
                        <li class="message"><div class="content">selam</div></li>
                    </ul>
                    <form id="reply-form">
                        <input type="hidden" name="__RequestVerificationToken" value="replytoken" />
                    </form>
                </div>
            </body></html>
        """.trimIndent()

        val detail = parser.parseMessageThreadDetail(
            html = html,
            threadUrl = "https://eksisozluk.com/mesaj/42"
        )
        assertNotNull(detail)
    }
}
