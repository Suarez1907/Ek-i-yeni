package com.example

import com.example.network.EksiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocompleteAndMessagingTest {

    private val parser = EksiParser()

    @Test
    fun parseAutocompleteResponse_handlesJsonObject() {
        val json = """{"titles":[{"title":"programlama"},{"title":"kotlin"}]}"""
        val result = parser.parseAutocompleteResponse(json)
        assertEquals(2, result.size)
        assertTrue(result.contains("programlama"))
        assertTrue(result.contains("kotlin"))
    }

    @Test
    fun parseAutocompleteResponse_handlesJsonArray() {
        val json = """["birinci","ikinci","ucuncu"]"""
        val result = parser.parseAutocompleteResponse(json)
        assertEquals(3, result.size)
        assertTrue(result.contains("birinci"))
    }

    @Test
    fun parseAutocompleteResponse_handlesPlainText() {
        val result = parser.parseAutocompleteResponse("ilk\nikinci\nucuncu")
        assertEquals(3, result.size)
        assertTrue(result.contains("ikinci"))
    }

    @Test
    fun parseAutocompleteResponse_decodesHtmlEntities() {
        val result = parser.parseAutocompleteResponse("""["java &amp; kotlin"]""")
        assertTrue(result.contains("java & kotlin"))
        assertFalse(result.contains("&amp;"))
    }

    @Test
    fun parseAutocompleteResponse_handlesEmptyAndBroken() {
        assertTrue(parser.parseAutocompleteResponse("").isEmpty())
        assertTrue(parser.parseAutocompleteResponse("{broken").isEmpty())
    }

    @Test
    fun parseAutocompleteResponse_singleLetterAndDedupe() {
        val result = parser.parseAutocompleteResponse("""["a","a","b"]""")
        assertEquals(2, result.size)
    }

    @Test
    fun buildAutocompleteUrl_containsQuery() {
        val url = parser.buildAutocompleteUrl("a")
        assertNotNull(url)
        assertTrue(url.contains("a"))
    }

    @Test
    fun parseMessageThreads_emptyReturnsEmpty() {
        assertTrue(parser.parseMessageThreads("").isEmpty())
        assertTrue(parser.parseMessageThreads("<html></html>").isEmpty())
    }

    @Test
    fun parseMessageThreads_parsesHtmlFixture() {
        val html = """
            <ul>
              <li data-thread-id="1"><a href="/mesaj/1">Kullanıcı 1</a><span>Son mesaj</span></li>
              <li data-thread-id="2"><a href="/mesaj/2">Kullanıcı 2</a><span>Merhaba</span></li>
            </ul>
        """.trimIndent()
        val threads = parser.parseMessageThreads(html)
        assertEquals(2, threads.size)
    }

    @Test
    fun parseMessageThread_emptyReturnsNull() {
        val thread = parser.parseMessageThread("")
        assertNull(thread)
    }

    @Test
    fun parseEntryCreateResult_handlesSuccess() {
        val json = """{"success":true,"message":"Gönderildi"}"""
        val result = parser.parseEntryCreateResult(json)
        assertTrue(result.isSuccess)
    }

    @Test
    fun parseEntryCreateResult_handlesError() {
        val json = """{"success":false,"error":"Hata"}"""
        val result = parser.parseEntryCreateResult(json)
        assertFalse(result.isSuccess)
        assertNotNull(result.message)
    }
}
