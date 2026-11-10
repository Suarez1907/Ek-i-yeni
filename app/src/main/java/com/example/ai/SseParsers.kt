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

    /**
     * OpenAI-compatible chat completion stream. Returns null when the stream says `[DONE]`.
     * Reasoning fields are ignored so chain-of-thought is not shown as the answer.
     */
    fun openAiDelta(dataJson: String): String? {
        val trimmed = dataJson.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed == "[DONE]") return null
        val root = JSONObject(trimmed)
        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { "Sağlayıcı akışı hata döndürdü." }
            throw IllegalStateException(message)
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return ""
        val delta = choice.optJSONObject("delta") ?: return ""
        return delta.optString("content")
    }
}

object OpenAiEndpoints {
    fun chatCompletionsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return "${DeepSeekPreset.BASE_URL}/chat/completions"
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }
}
