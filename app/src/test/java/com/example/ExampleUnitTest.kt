package com.example

import com.example.data.remote.EksiParser
import com.example.network.EksiHtmlSnapshotGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleUnitTest {
    @Test
    fun snapshotGenerator_and_eksiParser_workCorrectly() {
        val parser = EksiParser()
        val gundemHtml = EksiHtmlSnapshotGenerator.generateHtmlForUrl("https://eksisozluk.com/basliklar/gundem")
        val topics = parser.parseLeftFrameTopics(gundemHtml, isTrackedSection = false)
        assertTrue(topics.isNotEmpty())

        val topicHtml = EksiHtmlSnapshotGenerator.generateHtmlForUrl("https://eksisozluk.com/eksin-oled--7894120")
        val pageData = parser.parseTopicPage(topicHtml, "https://eksisozluk.com/eksin-oled--7894120", 1)
        assertTrue(pageData.entries.isNotEmpty())
        assertEquals("ekşin oled", pageData.topicTitle)
    }
}
