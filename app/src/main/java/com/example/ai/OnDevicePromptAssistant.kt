package com.example.ai

import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device Gemini Nano hook. The ML Kit GenAI artifact is not linked (see
 * [NanoPromptBridge]), so [isReady] stays false and the filter keeps the
 * local result. Nothing here runs at startup.
 */
@Singleton
class OnDevicePromptAssistant @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    @Volatile
    private var available: Boolean? = null

    suspend fun isReady(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        if (appContext.packageName.isBlank()) return false
        available?.let { return it }
        val ready = NanoPromptBridge.isAvailable()
        available = ready
        return ready
    }

    suspend fun complete(prompt: String): String? {
        if (!isReady()) return null
        return NanoPromptBridge.complete(prompt)
    }
}
