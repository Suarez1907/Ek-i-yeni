package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AiProviderConfig {
        return AiProviderConfig(
            provider = AiProviderId.fromStorage(prefs.getString(KEY_PROVIDER, null)),
            openAiBaseUrl = prefs.getString(KEY_BASE_URL, null)?.ifBlank { null }
                ?: DeepSeekPreset.BASE_URL,
            openAiModel = prefs.getString(KEY_MODEL, null)?.ifBlank { null }
                ?: DeepSeekPreset.MODEL,
            smartFilterEnabled = prefs.getBoolean(KEY_SMART_FILTER, false),
            cloudFilterEnabled = prefs.getBoolean(KEY_CLOUD_FILTER, false),
            personalFeedEnabled = prefs.getBoolean(KEY_PERSONAL, false)
        )
    }

    fun save(config: AiProviderConfig) {
        prefs.edit()
            .putString(KEY_PROVIDER, config.provider.storageKey)
            .putString(KEY_BASE_URL, config.openAiBaseUrl.trim())
            .putString(KEY_MODEL, config.openAiModel.trim())
            .putBoolean(KEY_SMART_FILTER, config.smartFilterEnabled)
            .putBoolean(KEY_CLOUD_FILTER, config.cloudFilterEnabled)
            .putBoolean(KEY_PERSONAL, config.personalFeedEnabled)
            .apply()
    }

    private companion object {
        const val PREFS = "eksin_ai_settings"
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "openai_base_url"
        const val KEY_MODEL = "openai_model"
        const val KEY_SMART_FILTER = "smart_filter"
        const val KEY_CLOUD_FILTER = "cloud_filter"
        const val KEY_PERSONAL = "personal_feed"
    }
}
