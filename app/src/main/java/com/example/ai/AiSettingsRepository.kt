package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secretStore: EncryptedSecretStore
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AiProviderConfig {
        val storedProvider = prefs.getString(KEY_PROVIDER, null)
        if (storedProvider == LEGACY_OPENAI) {
            secretStore.clear(LEGACY_OPENAI)
            prefs.edit()
                .putString(KEY_PROVIDER, AiProviderId.GEMINI.storageKey)
                .remove(KEY_BASE_URL)
                .remove(KEY_MODEL)
                .apply()
        }
        return AiProviderConfig(
            provider = AiProviderId.GEMINI,
            smartFilterEnabled = prefs.getBoolean(KEY_SMART_FILTER, false),
            cloudFilterEnabled = prefs.getBoolean(KEY_CLOUD_FILTER, false),
            personalFeedEnabled = prefs.getBoolean(KEY_PERSONAL, false),
            redditClientId = prefs.getString(KEY_REDDIT_CLIENT, null).orEmpty().trim()
        )
    }

    fun save(config: AiProviderConfig) {
        prefs.edit()
            .putString(KEY_PROVIDER, AiProviderId.GEMINI.storageKey)
            .remove(KEY_BASE_URL)
            .remove(KEY_MODEL)
            .putBoolean(KEY_SMART_FILTER, config.smartFilterEnabled)
            .putBoolean(KEY_CLOUD_FILTER, config.cloudFilterEnabled)
            .putBoolean(KEY_PERSONAL, config.personalFeedEnabled)
            .putString(KEY_REDDIT_CLIENT, config.redditClientId.trim())
            .apply()
    }

    fun saveRedditClientId(raw: String) {
        prefs.edit().putString(KEY_REDDIT_CLIENT, raw.trim()).apply()
    }

    fun redditDeviceId(): String {
        val existing = prefs.getString(KEY_REDDIT_DEVICE, null)
        if (!existing.isNullOrBlank()) return existing
        val created = java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        prefs.edit().putString(KEY_REDDIT_DEVICE, created).apply()
        return created
    }

    private companion object {
        const val PREFS = "eksin_ai_settings"
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "openai_base_url"
        const val KEY_MODEL = "openai_model"
        const val KEY_SMART_FILTER = "smart_filter"
        const val KEY_CLOUD_FILTER = "cloud_filter"
        const val KEY_PERSONAL = "personal_feed"
        const val KEY_REDDIT_CLIENT = "reddit_client_id"
        const val KEY_REDDIT_DEVICE = "reddit_device_id"
        const val LEGACY_OPENAI = "openai_compatible"
    }
}
