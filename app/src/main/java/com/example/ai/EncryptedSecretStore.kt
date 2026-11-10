package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores one extra provider key the same way the Gemini key is stored:
 * AES/GCM in Android Keystore, ciphertext in private prefs. A decrypt failure
 * keeps the ciphertext so a transient keystore error does not wipe the key.
 */
@Singleton
class EncryptedSecretStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(name: String, secret: String) {
        val cleaned = secret.trim()
        if (cleaned.isEmpty()) {
            clear(name)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(cleaned.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString(cipherKey(name), Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey(name), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun read(name: String): String {
        val encoded = prefs.getString(cipherKey(name), null) ?: return ""
        val iv = prefs.getString(ivKey(name), null) ?: return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            val plain = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP))
            String(plain, StandardCharsets.UTF_8).trim()
        } catch (_: Throwable) {
            ""
        }
    }

    fun has(name: String): Boolean {
        return !prefs.getString(cipherKey(name), null).isNullOrBlank()
    }

    fun clear(name: String) {
        prefs.edit().remove(cipherKey(name)).remove(ivKey(name)).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun cipherKey(name: String) = "${name}_cipher"
    private fun ivKey(name: String) = "${name}_iv"

    private companion object {
        const val PREFS = "eksin_ai_secret_prefs"
        const val ALIAS = "eksin_ai_provider_aes"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
