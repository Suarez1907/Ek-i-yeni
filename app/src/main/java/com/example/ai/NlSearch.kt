package com.example.ai

import com.example.data.model.SearchFilter
import com.example.data.model.SearchSortOrder
import org.json.JSONObject

data class NlSearchParse(
    val filter: SearchFilter?,
    val error: String?
)

object NlSearchParser {
    private const val MAX_KEYWORDS = 80

    fun parse(raw: String): NlSearchParse {
        val jsonText = extractObject(raw) ?: return NlSearchParse(null, "Arama cümlesi JSON'a çevrilemedi.")
        val json = try {
            JSONObject(jsonText)
        } catch (_: Throwable) {
            return NlSearchParse(null, "Arama cümlesi JSON'a çevrilemedi.")
        }
        val keywords = json.optString("keywords").trim()
        if (keywords.isBlank()) return NlSearchParse(null, "Anahtar kelime boş.")
        if (keywords.length > MAX_KEYWORDS) {
            return NlSearchParse(null, "Anahtar kelime çok uzun.")
        }
        val author = json.optString("author").trim().removePrefix("@").take(40)
        val sort = when (json.optString("sortOrder").trim()) {
            "Date" -> SearchSortOrder.DATE
            "Topic" -> SearchSortOrder.TOPIC
            "Count", "" -> SearchSortOrder.COUNT
            else -> return NlSearchParse(null, "Sıralama değeri geçersiz.")
        }
        val niceOnly = json.optBoolean("niceOnly", false)
        return NlSearchParse(
            filter = SearchFilter(
                keywords = keywords,
                author = author,
                sortOrder = sort,
                niceOnly = niceOnly
            ),
            error = null
        )
    }

    private fun extractObject(raw: String): String? {
        val withoutFence = raw
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = withoutFence.indexOf('{')
        val end = withoutFence.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return withoutFence.substring(start, end + 1)
    }
}

object FilterDecisionParser {
    fun hiddenIds(raw: String, allowed: Set<String>): Set<String> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptySet()
        val root = try {
            JSONObject(raw.substring(start, end + 1))
        } catch (_: Throwable) {
            return emptySet()
        }
        val hide = root.optJSONArray("hide") ?: return emptySet()
        val ids = mutableSetOf<String>()
        for (index in 0 until hide.length()) {
            val id = hide.optString(index).filter { it.isDigit() }
            if (id.isNotBlank() && id in allowed) ids.add(id)
        }
        return ids
    }
}

data class DiscussionPoint(
    val side: String,
    val claim: String,
    val entryId: String
)

object DiscussionMapParser {
    fun parse(raw: String): List<DiscussionPoint> {
        val jsonText = raw.replace("```json", "").replace("```", "")
        val start = jsonText.indexOf('{')
        val end = jsonText.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = try {
            JSONObject(jsonText.substring(start, end + 1))
        } catch (_: Throwable) {
            return emptyList()
        }
        val sides = root.optJSONArray("sides") ?: return emptyList()
        val points = mutableListOf<DiscussionPoint>()
        for (sideIndex in 0 until sides.length()) {
            val side = sides.optJSONObject(sideIndex) ?: continue
            val name = side.optString("name").trim().take(40)
            val claims = side.optJSONArray("points") ?: continue
            for (pointIndex in 0 until claims.length()) {
                val point = claims.optJSONObject(pointIndex) ?: continue
                val claim = point.optString("claim").trim()
                val entryId = point.optString("entryId").trim().filter { it.isDigit() }
                if (name.isNotBlank() && claim.isNotBlank() && entryId.isNotBlank()) {
                    points.add(DiscussionPoint(name, claim.take(180), entryId))
                }
            }
        }
        return points.take(12)
    }
}
