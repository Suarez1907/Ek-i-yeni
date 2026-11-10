package com.example.network

import java.io.File
import java.security.MessageDigest

/**
 * Small on-disk cache of list HTML so the next process start can paint gündem
 * before the network returns. Capped by entry count.
 */
class ListHtmlDiskCache(
    private val directory: File,
    private val maxEntries: Int = 24
) {
    fun read(cacheKey: String, nowMs: Long): DiskHtmlHit? {
        val file = fileFor(cacheKey)
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (_: Throwable) {
            return null
        }
        val firstBreak = text.indexOf('\n')
        val secondBreak = if (firstBreak >= 0) text.indexOf('\n', firstBreak + 1) else -1
        if (firstBreak <= 0 || secondBreak <= firstBreak) return null
        val storedAt = text.substring(0, firstBreak).toLongOrNull() ?: return null
        val sourceName = text.substring(firstBreak + 1, secondBreak)
        val html = text.substring(secondBreak + 1)
        if (html.isBlank()) return null
        val age = nowMs - storedAt
        if (ListCacheAges.classify(age) == ListCacheAge.EXPIRED) {
            file.delete()
            return null
        }
        val source = runCatching { ContentSource.valueOf(sourceName) }.getOrDefault(ContentSource.LIVE)
        return DiskHtmlHit(html = html, source = source, ageMs = age)
    }

    fun write(cacheKey: String, html: String, source: ContentSource, nowMs: Long) {
        if (html.isBlank()) return
        if (!directory.exists()) directory.mkdirs()
        val file = fileFor(cacheKey)
        try {
            file.writeText("$nowMs\n${source.name}\n$html")
        } catch (_: Throwable) {
            return
        }
        trimOldest()
    }

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun trimOldest() {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxEntries)
            .forEach { it.delete() }
    }

    private fun fileFor(cacheKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray())
        val name = digest.joinToString("") { byte -> "%02x".format(byte) }.take(32) + ".html"
        return File(directory, name)
    }
}

data class DiskHtmlHit(
    val html: String,
    val source: ContentSource,
    val ageMs: Long
)
