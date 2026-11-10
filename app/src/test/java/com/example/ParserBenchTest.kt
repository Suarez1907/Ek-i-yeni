package com.example

import com.example.data.remote.EksiParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM timing for the gündem parser. Not a device cold start.
 * Prints one line so a before/after run can be compared from the test output.
 */
class ParserBenchTest {
    @Test
    fun parseLeftFrame_repeatableTiming() {
        val parser = EksiParser()
        val html = buildGundemHtml(topicCount = 50)
        repeat(10) { parser.parseLeftFrameTopics(html, false) }
        val start = System.nanoTime()
        val iterations = 40
        var lastSize = 0
        repeat(iterations) {
            lastSize = parser.parseLeftFrameTopics(html, false).size
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        println("PARSER_BENCH parseLeftFrameTopics x$iterations of 50 topics = ${"%.1f".format(elapsedMs)} ms")
        assertEquals(50, lastSize)
    }

    private fun buildGundemHtml(topicCount: Int): String {
        val items = buildString {
            repeat(topicCount) { index ->
                append(
                    """<li><a href="/baslik-$index--${1000 + index}?a=popular">baslik $index <small>${index + 1}</small></a></li>"""
                )
            }
        }
        return """<html><body><ul class="topic-list partial">$items</ul></body></html>"""
    }
}
