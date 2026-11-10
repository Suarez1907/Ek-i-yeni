package com.example.ai

/**
 * On-device Gemini Nano is intentionally not linked.
 *
 * `com.google.mlkit:genai-prompt:1.0.0-beta5` compiles against Kotlin metadata 2.4
 * and declares minSdk 26. This app stays on Kotlin 2.3.10 / Hilt 2.59.1 / minSdk 24.
 * Hilt refuses to process that metadata (`maximum supported version is 2.3.0`),
 * so the dependency is not on the classpath. Smart filter therefore uses the
 * local heuristics, then the optional cloud batch. It never downloads a model
 * and never blocks the entry list.
 */
internal object NanoPromptBridge {
    suspend fun isAvailable(): Boolean = false

    suspend fun complete(prompt: String): String? {
        if (prompt.isEmpty()) return null
        return null
    }
}
