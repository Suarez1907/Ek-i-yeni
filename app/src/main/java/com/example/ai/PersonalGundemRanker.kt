package com.example.ai

import com.example.data.model.TopicItem

/**
 * Ranks topics the reader has already opened. Growth since the last seen
 * entry count comes first. Topics with no local history keep their original order
 * behind those. No network and no model call.
 */
object PersonalGundemRanker {
    fun forReader(topics: List<TopicItem>, lastSeenEntryCounts: Map<String, Int>): List<TopicItem> {
        if (lastSeenEntryCounts.isEmpty()) return emptyList()
        return rank(topics, lastSeenEntryCounts).filter { score(it, lastSeenEntryCounts) > 0 }
    }

    fun rank(topics: List<TopicItem>, lastSeenEntryCounts: Map<String, Int>): List<TopicItem> {
        if (topics.isEmpty() || lastSeenEntryCounts.isEmpty()) return topics
        return topics
            .mapIndexed { index, topic -> Triple(topic, score(topic, lastSeenEntryCounts), index) }
            .sortedWith(compareByDescending<Triple<TopicItem, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    private fun score(topic: TopicItem, lastSeenEntryCounts: Map<String, Int>): Int {
        val urlKey = topic.cleanUrl.trim().lowercase()
        val titleKey = "title:${topic.title.trim().lowercase()}"
        val seen = lastSeenEntryCounts[urlKey] ?: lastSeenEntryCounts[titleKey] ?: return 0
        val growth = (topic.entryCount - seen).coerceAtLeast(0)
        return 1_000 + growth * 10
    }
}
