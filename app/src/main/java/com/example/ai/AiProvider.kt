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
    GEMINI("gemini", "Gemini", true);

    companion object {
        fun fromStorage(raw: String?): AiProviderId = GEMINI
    }
}

data class AiProviderConfig(
    val provider: AiProviderId = AiProviderId.GEMINI,
    val smartFilterEnabled: Boolean = false,
    val cloudFilterEnabled: Boolean = false,
    val personalFeedEnabled: Boolean = false,
    val redditClientId: String = ""
)
