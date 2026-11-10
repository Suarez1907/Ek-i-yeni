package com.example.ai

/**
 * One streaming text event. Providers emit deltas; the UI throttles them.
 */
sealed interface AiTextChunk {
    data class Delta(val text: String) : AiTextChunk
    data object Done : AiTextChunk
    data class Failure(val message: String) : AiTextChunk
}

enum class AiProviderId(val storageKey: String, val label: String, val supportsImages: Boolean) {
    GEMINI("gemini", "Gemini", true),
    OPENAI_COMPATIBLE("openai_compatible", "OpenAI uyumlu", false);

    companion object {
        fun fromStorage(raw: String?): AiProviderId {
            return entries.firstOrNull { it.storageKey == raw } ?: GEMINI
        }
    }
}

/**
 * DeepSeek's OpenAI-compatible API as documented at https://api-docs.deepseek.com/
 * (base URL without /v1, model `deepseek-flash`, optional `deepseek-v4-pro`).
 */
object DeepSeekPreset {
    const val BASE_URL = "https://api.deepseek.com"
    const val MODEL = "deepseek-flash"
    const val MODEL_PRO = "deepseek-v4-pro"
}

data class AiProviderConfig(
    val provider: AiProviderId = AiProviderId.GEMINI,
    val openAiBaseUrl: String = DeepSeekPreset.BASE_URL,
    val openAiModel: String = DeepSeekPreset.MODEL,
    val smartFilterEnabled: Boolean = false,
    val cloudFilterEnabled: Boolean = false,
    val personalFeedEnabled: Boolean = false
)
