package com.example

import com.example.network.extractSmartSearchQuery
import com.example.network.extractStatusLinks
import com.example.network.parseFeedStatusLinks
import com.example.network.parseOEmbed
import com.example.network.parseRedditComments
import com.example.network.parseRedditPosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SocialFeedTest {
    @Test
    fun smartQuery_stripsDatesYearsParentheticalsAndStopwords() {
        assertEquals(
            "deprem istanbul",
            extractSmartSearchQuery("3 kasım 2026 deprem ve istanbul")
        )
        assertEquals(
            "yapay zeka",
            extractSmartSearchQuery("Yapay Zeka (128) için olması")
        )
        assertEquals("seçim", extractSmartSearchQuery("2024 seçim"))
        assertEquals("ekşi", extractSmartSearchQuery("ekşi (#42)"))
    }

    @Test
    fun redditJson_readsPreviewAndSurvivesGarbage() {
        val raw = """
            {"data":{"children":[
              {"kind":"t3","data":{
                "id":"abc","title":"Başlık","subreddit_name_prefixed":"r/turkey",
                "author":"ali","ups":12,"num_comments":3,"selftext":"gövde",
                "permalink":"/r/turkey/comments/abc/baslik/",
                "preview":{"images":[{"source":{"url":"https://preview.redd.it/a.jpg?width=10&amp;auto=webp"}}]}
              }},
              {"kind":"t1","data":{"id":"skip"}}
            ]}}
        """.trimIndent()
        val posts = parseRedditPosts(raw)
        assertEquals(1, posts.size)
        assertEquals("r/turkey", posts[0].subreddit)
        assertEquals("u/ali", posts[0].author)
        assertEquals(12, posts[0].ups)
        assertEquals("https://preview.redd.it/a.jpg?width=10&auto=webp", posts[0].imageUrl)
        assertTrue(parseRedditPosts("not-json").isEmpty())
        assertTrue(parseRedditPosts("").isEmpty())
    }

    @Test
    fun redditComments_readSecondListing() {
        val raw = """
            [{},{"data":{"children":[
              {"kind":"t1","data":{"id":"c1","author":"ayse","body":"katılıyorum","ups":4}},
              {"kind":"more","data":{"id":"m1"}}
            ]}}]
        """.trimIndent()
        val comments = parseRedditComments(raw)
        assertEquals("u/ayse", comments.single().author)
        assertEquals("katılıyorum", comments.single().body)
        assertTrue(parseRedditComments("{").isEmpty())
    }

    @Test
    fun oEmbed_readsAuthorAndParagraph() {
        val raw = """
            {"author_name":"jack","url":"https://x.com/jack/status/20",
             "html":"<blockquote class=\"twitter-tweet\"><p lang=\"en\">just setting up my twttr</p></blockquote>"}
        """.trimIndent()
        val post = parseOEmbed(raw, "https://x.com/jack/status/20")
        assertEquals("jack", post?.author)
        assertEquals("just setting up my twttr", post?.text)
        assertEquals("20", post?.id)
        assertEquals(null, parseOEmbed("[]", "https://x.com/jack/status/20"))
    }

    @Test
    fun statusLinks_areDedupedAndCanonical() {
        val text = """
            bak https://twitter.com/Ali/status/99 ve https://x.com/ali/status/99
            bir de x.com/veli/status/100
        """.trimIndent()
        assertEquals(
            listOf("https://x.com/Ali/status/99", "https://x.com/veli/status/100"),
            extractStatusLinks(listOf(text))
        )
        val botWall = "<html>Making sure you're not a bot!</html> https://x.com/a/status/1"
        assertTrue(parseFeedStatusLinks(botWall).isEmpty())
    }
}
