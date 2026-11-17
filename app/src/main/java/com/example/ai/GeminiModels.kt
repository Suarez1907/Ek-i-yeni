package com.example.ai

/**
 * Model ids checked against the Gemini API deprecations list
 * (https://ai.google.dev/gemini-api/docs/deprecations) and the image generation guide
 * (https://ai.google.dev/gemini-api/docs/image-generation).
 *
 * Text:
 * - `gemini-4-hydrogen` is the Gemini API model id for Gemini 4 Hydrogen
 *   (used by generateContent; a missing or rejected id falls through on HTTP 400/404).
 * - `gemini-3.5-flash-lite` (21 Jul 2026, no shutdown announced) is the next summary model.
 * - `gemini-3.8-flash` (2 Sep 2026, no shutdown announced) is the next text fallback.
 * - `gemini-2.5-flash-lite` remains available for older keys.
 * `gemini-flash-lite-latest` is not a documented model id and is not used.
 *
 * Image:
 * - `gemini-nano-banana-2.1` (Nano Banana 2.1, 6 Oct 2026) is the default image model.
 * - `gemini-3-pro-image` (28 May 2026) is the higher quality studio model.
 * `gemini-3.5-flash-lite` is a text model and must not be sent as an image endpoint.
 */
object GeminiModels {
    const val SUMMARY_HYDROGEN = "gemini-4-hydrogen"
    const val SUMMARY_PRIMARY = SUMMARY_HYDROGEN
    const val SUMMARY_FLASH_LITE = "gemini-3.5-flash-lite"
    const val SUMMARY_FLASH = "gemini-3.8-flash"
    const val SUMMARY_LEGACY = "gemini-2.5-flash-lite"

    val SUMMARY_FALLBACK_CHAIN: List<String> = listOf(
        SUMMARY_HYDROGEN,
        SUMMARY_FLASH_LITE,
        SUMMARY_FLASH,
        SUMMARY_LEGACY
    )

    const val IMAGE_FAST = "gemini-nano-banana-2.1"
    const val IMAGE_PRO = "gemini-3-pro-image"
    const val IMAGE_DEFAULT = IMAGE_FAST

    const val SUMMARY_DISPLAY_NAME = "Gemini 4 Hydrogen"
}
