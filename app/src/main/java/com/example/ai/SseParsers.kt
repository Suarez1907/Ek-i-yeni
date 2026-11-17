package com.example.ai

import org.json.JSONObject

/**
 * Pulls text deltas out of one SSE `data:` payload. Does not execute anything
 * inside the payload.
 */
object SseParsers {
    fun geminiDelta(dataJson: String): String {
        if (dataJson.isBlank() || dataJson == "[DONE]") return ""
        val root = JSONObject(dataJson)
        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { "Gemini akışı hata döndürdü." }
            throw IllegalStateException(message)
        }
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return ""
        val builder = StringBuilder()
        for (index in 0 until parts.length()) {
            val text = parts.optJSONObject(index)?.optString("text").orEmpty()
            if (text.isNotEmpty()) builder.append(text)
        }
        return builder.toString()
    }

}
