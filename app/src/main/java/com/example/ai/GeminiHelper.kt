package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.EntryItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenRouter analiz modları:
 * - [FIVE_BULLET_SUMMARY]: "Başlığı 5 maddede özetle"
 * - [DEBATE_AND_CONSENSUS]: "Genel fikir ayrılıklarını ve baskın görüşü çıkar"
 */
enum class GeminiAnalysisMode(val title: String, val subtitle: String) {
    FIVE_BULLET_SUMMARY(
        title = "Başlığı 5 Maddede Özetle",
        subtitle = "Öne çıkan bilgileri 5 net maddede özetler"
    ),
    DEBATE_AND_CONSENSUS(
        title = "Fikir Ayrılıklarını ve Baskın Görüşü Çıkar",
        subtitle = "Tartışma eksenlerini, karşıt kampları ve ortak kanaati analiz eder"
    )
}

/**
 * Streaming sırasında UI katmanına iletilen durum modeli.
 */
sealed interface GeminiStreamState {
    data object Idle : GeminiStreamState
    data class AwaitingApiKey(
        val mode: GeminiAnalysisMode = GeminiAnalysisMode.FIVE_BULLET_SUMMARY
    ) : GeminiStreamState
    data class Loading(val mode: GeminiAnalysisMode) : GeminiStreamState
    data class Streaming(
        val mode: GeminiAnalysisMode,
        val partialText: String,
        val latestChunk: String
    ) : GeminiStreamState
    data class Completed(
        val mode: GeminiAnalysisMode,
        val fullText: String
    ) : GeminiStreamState
    data class Error(
        val mode: GeminiAnalysisMode,
        val message: String
    ) : GeminiStreamState
}

/**
 * AI Görsel Üretim sonucu:
 * - [Success]: Görsel üretim başarıyla tamamlandığında döner.
 * - [LocalFallbackPreview]: Yerel önizleme gerektiğinde döner.
 * - [Error]: API anahtarı eksikliği veya ağ hatası gibi doğrudan kullanıcıya gösterilecek hata durumu.
 */
sealed class GeminiImageGenerationResult {
    data class Success(
        val bitmap: Bitmap,
        val modelUsed: String
    ) : GeminiImageGenerationResult()

    data class LocalFallbackPreview(
        val bitmap: Bitmap,
        val reason: String,
        val modelLabel: String
    ) : GeminiImageGenerationResult()

    data class Error(
        val reason: String
    ) : GeminiImageGenerationResult()
}

/**
 * Google Services / Firebase gerektirmeden doğrudan OpenRouter REST API anahtarı ve
 * OkHttp istemcisi üzerinden çalışan güvenli yardımcı sınıf:
 *
 * 1. **API Anahtarı Güvenliği**:
 *    - API anahtarı hiçbir zaman URL içinde (`?key=...`) gönderilmez; yalnızca `Authorization`
 *      HTTP header'ı ile iletilir ve loglara yazılmaz.
 *    - API anahtarı cihazda Android Keystore (`AES/GCM/NoPadding`) ile şifrelenerek
 *      `eksin_gemini_prefs` içinde Base64 (`ciphertext` + `iv`) olarak saklanır.
 *    - Eski düz metin (`gemini_api_key`) kaydı varsa ilk okumada otomatik olarak şifreli formata
 *      taşınır (`migrate`) ve düz metin kaydı silinir.
 *    - Şifre çözme hatası olursa uygulama çökmez; bozuk kayıt temizlenir ve kullanıcıya anahtarını
 *      yeniden girmesi gerektiği bildirilir.
 * 2. **Gerçek SSE Streaming**:
 *    - `https://openrouter.ai/api/v1/chat/completions` uç noktasından gelen `data: ` satırlarını
 *      gerçek zamanlı okuyup anında `Flow` üzerinden UI'a akıtır.
 *    - 75 saniye toplam zaman aşımı ve ilk byte/chunk için 20 saniye (`FIRST_CHUNK_TIMEOUT_MS`)
 *      zaman aşımı uygular; kullanıcı ekrandan çıktığında aktif `OkHttp` çağrısını anında iptal eder.
 * 3. **Otomatik Model Fallback Zinciri (404 / 429 / 503)**:
 *    [OPENROUTER_FALLBACK_MODELS] sırasıyla denenir.
 */
@Singleton
class GeminiHelper @Inject constructor(
    @ApplicationContext private val appContext: Context,
    baseOkHttpClient: OkHttpClient
) {

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _keyDecryptionWarning = MutableStateFlow<String?>(null)
    val keyDecryptionWarning: StateFlow<String?> = _keyDecryptionWarning.asStateFlow()

    /**
     * Ekşi Sözlük özel Cloudflare/User-Agent interceptor'larından etkilenmeyen,
     * doğrudan OpenRouter REST API için yapılandırılmış OkHttp istemcisi.
     */
    private val geminiHttpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    init {
        migrateLegacyPlainTextKeyIfNeeded()
    }

    /**
     * Eski sürümde düz metin (`gemini_api_key`) olarak kaydedilmiş bir anahtar varsa,
     * ilk okumada Android Keystore (`AES/GCM/NoPadding`) ile şifrelenmiş formata taşır
     * ve düz metin kaydını kalıcı olarak siler.
     */
    private fun migrateLegacyPlainTextKeyIfNeeded() {
        try {
            val legacyPlain = prefs.getString(KEY_LEGACY_PLAIN_API_KEY, null)?.trim()
            if (!legacyPlain.isNullOrBlank()) {
                val encryptedOk = encryptAndStoreApiKey(legacyPlain)
                if (encryptedOk) {
                    prefs.edit().remove(KEY_LEGACY_PLAIN_API_KEY).apply()
                }
            } else if (prefs.contains(KEY_LEGACY_PLAIN_API_KEY)) {
                prefs.edit().remove(KEY_LEGACY_PLAIN_API_KEY).apply()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Eski API anahtarı şifreli formata taşınırken uyarı: ${e.javaClass.simpleName}")
            prefs.edit().remove(KEY_LEGACY_PLAIN_API_KEY).apply()
        }
    }

    private fun getOrCreateAesSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
        val existingEntry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) {
            return existingEntry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptAndStoreApiKey(plainApiKey: String): Boolean {
        return try {
            val secretKey = getOrCreateAesSecretKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val ivBytes = cipher.iv
            val cipherBytes = cipher.doFinal(plainApiKey.toByteArray(StandardCharsets.UTF_8))

            val encodedCipher = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            val encodedIv = Base64.encodeToString(ivBytes, Base64.NO_WRAP)

            prefs.edit()
                .putString(KEY_ENCRYPTED_API_KEY_CIPHERTEXT, encodedCipher)
                .putString(KEY_ENCRYPTED_API_KEY_IV, encodedIv)
                .remove(KEY_LEGACY_PLAIN_API_KEY)
                .apply()
            _keyDecryptionWarning.value = null
            true
        } catch (e: Throwable) {
            Log.w(TAG, "API anahtarı Android Keystore ile şifrelenirken hata: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Kullanıcının Android Keystore (`AES/GCM/NoPadding`) ile şifrelenerek kaydedilmiş
     * özel API anahtarını çözer ve döndürür.
     * Eğer şifre çözme başarısız olursa uygulamayı çökertmez; bozuk kaydı temizler,
     * anahtarı boş kabul eder ve kullanıcıya uyarı mesajı yayınlar.
     */
    fun getUserSavedApiKey(): String {
        migrateLegacyPlainTextKeyIfNeeded()

        val encodedCipher = prefs.getString(KEY_ENCRYPTED_API_KEY_CIPHERTEXT, null)
        val encodedIv = prefs.getString(KEY_ENCRYPTED_API_KEY_IV, null)

        if (encodedCipher.isNullOrBlank() || encodedIv.isNullOrBlank()) {
            return ""
        }

        return try {
            val cipherBytes = Base64.decode(encodedCipher, Base64.NO_WRAP)
            val ivBytes = Base64.decode(encodedIv, Base64.NO_WRAP)

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val secretKeyEntry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
                ?: throw IllegalStateException("Keystore anahtarı bulunamadı.")

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKeyEntry.secretKey, spec)
            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, StandardCharsets.UTF_8).trim()
        } catch (e: Throwable) {
            Log.w(TAG, "Şifreli API anahtarı çözülemedi, bozuk kayıt temizleniyor: ${e.javaClass.simpleName}")
            clearCorruptedEncryptedKeyEntries()
            _keyDecryptionWarning.value =
                "Kayıtlı API anahtarının şifresi çözülemediği için güvenlik amacıyla sıfırlandı. Lütfen anahtarınızı tekrar girin."
            ""
        }
    }

    private fun clearCorruptedEncryptedKeyEntries() {
        try {
            prefs.edit()
                .remove(KEY_ENCRYPTED_API_KEY_CIPHERTEXT)
                .remove(KEY_ENCRYPTED_API_KEY_IV)
                .remove(KEY_LEGACY_PLAIN_API_KEY)
                .apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * Kullanıcının girdiği OpenRouter API anahtarını Android Keystore (`AES/GCM/NoPadding`)
     * ile şifreleyerek `eksin_gemini_prefs` içine kaydeder.
     */
    fun saveUserApiKey(apiKey: String) {
        val cleaned = apiKey.trim()
        if (cleaned.isEmpty()) {
            clearUserApiKey()
            return
        }
        encryptAndStoreApiKey(cleaned)
    }

    /**
     * Kayıtlı API anahtarını `eksin_gemini_prefs` içinden temizler.
     */
    fun clearUserApiKey() {
        _keyDecryptionWarning.value = null
        prefs.edit()
            .remove(KEY_ENCRYPTED_API_KEY_CIPHERTEXT)
            .remove(KEY_ENCRYPTED_API_KEY_IV)
            .remove(KEY_LEGACY_PLAIN_API_KEY)
            .apply()
    }

    fun dismissKeyDecryptionWarning() {
        _keyDecryptionWarning.value = null
    }

    /**
     * Kullanıcı kendi anahtarını girip kaydetmeden otomatik istek atılmaması için yalnızca
     * cihazda şifreli olarak saklanan kullanıcı anahtarı dikkate alınır; yoksa BuildConfig
     * üzerinden gelen OpenRouter anahtarı kullanılır.
     */
    fun resolveActiveApiKey(): String {
        return getUserSavedApiKey().ifBlank { BuildConfig.OPENROUTER_API_KEY }
    }

    fun hasValidApiKey(): Boolean = getUserSavedApiKey().isNotBlank()

    /**
     * a) "Başlığı 5 maddede özetle" işlemini akış ([Flow]) olarak döndürür.
     */
    fun summarizeTopicInFiveBulletsStream(
        topicTitle: String,
        entries: List<EntryItem>
    ): Flow<GeminiStreamState> {
        return executeStreamingAnalysis(
            mode = GeminiAnalysisMode.FIVE_BULLET_SUMMARY,
            topicTitle = topicTitle,
            entries = entries
        )
    }

    /**
     * b) "Genel fikir ayrılıklarını ve baskın görüşü çıkar" işlemini akış ([Flow]) olarak döndürür.
     */
    fun extractDisagreementsAndDominantViewStream(
        topicTitle: String,
        entries: List<EntryItem>
    ): Flow<GeminiStreamState> {
        return executeStreamingAnalysis(
            mode = GeminiAnalysisMode.DEBATE_AND_CONSENSUS,
            topicTitle = topicTitle,
            entries = entries
        )
    }

    /**
     * Seçilen [GeminiAnalysisMode] için prompt oluşturur ve doğrudan `OkHttp` üzerinden
     * `https://openrouter.ai/api/v1/chat/completions` uç noktasına (`Authorization: Bearer`
     * header'ı ile, URL'de anahtar olmadan) gerçek zamanlı SSE isteği atarak gelen
     * `data: ` paketlerini anında [GeminiStreamState.Streaming] olarak yayınlar.
     */
    fun executeStreamingAnalysis(
        mode: GeminiAnalysisMode,
        topicTitle: String,
        entries: List<EntryItem>
    ): Flow<GeminiStreamState> = flow {
        val apiKey = resolveActiveApiKey()
        if (apiKey.isBlank()) {
            emit(GeminiStreamState.AwaitingApiKey(mode))
            return@flow
        }

        emit(GeminiStreamState.Loading(mode))

        if (entries.isEmpty()) {
            emit(
                GeminiStreamState.Error(
                    mode = mode,
                    message = "Analiz edilecek entry bulunamadı. Lütfen önce başlığın yüklenmesini bekleyin."
                )
            )
            return@flow
        }

        val promptText = buildPromptForMode(
            mode = mode,
            topicTitle = topicTitle,
            entries = entries
        )

        val payloadString = JSONObject().apply {
            put("model", BuildConfig.OPENROUTER_MODEL)
            put("stream", true)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", promptText)
                    }
                )
            )
        }.toString()

        try {
            withTimeout(TOTAL_STREAM_TIMEOUT_MS) {
                var lastErrorMessage = "OpenRouter API çağrısı başarısız oldu."
                var streamCompletedSuccessfully = false

                for ((index, modelName) in OPENROUTER_FALLBACK_MODELS.withIndex()) {
                    currentCoroutineContext().ensureActive()

                    val url = "https://openrouter.ai/api/v1/chat/completions"
                    val request = Request.Builder()
                        .url(url)
                        .post(payloadString.toRequestBody(JSON_MEDIA_TYPE))
                        .header("Authorization", "Bearer $apiKey")
                        .header("HTTP-Referer", "https://eksin-oled.app")
                        .header("X-Title", "Eksin OLED")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("Accept", "text/event-stream")
                        .build()

                    val call = geminiHttpClient.newCall(request)
                    var shouldFallbackToNextModel = false

                    try {
                        val response = withTimeout(FIRST_CHUNK_TIMEOUT_MS) {
                            withContext(Dispatchers.IO) {
                                call.execute()
                            }
                        }

                        response.use { resp ->
                            if (!resp.isSuccessful) {
                                val code = resp.code
                                val errorBody = resp.body?.string().orEmpty()
                                val apiErrorMessage = extractApiErrorMessage(errorBody, code, modelName)
                                lastErrorMessage = apiErrorMessage

                                val canFallback = (code == 404 || code == 429 || code == 503) &&
                                    index < OPENROUTER_FALLBACK_MODELS.lastIndex
                                if (canFallback) {
                                    Log.w(
                                        TAG,
                                        "SSE model '$modelName' HTTP $code döndürdü; yedek modele (${OPENROUTER_FALLBACK_MODELS[index + 1]}) geçiliyor."
                                    )
                                    shouldFallbackToNextModel = true
                                } else {
                                    throw IllegalStateException(apiErrorMessage)
                                }
                            } else {
                                val body = resp.body
                                    ?: throw IllegalStateException("OpenRouter SSE yanıt gövdesi boş döndü ($modelName).")

                                val accumulated = StringBuilder()
                                var receivedFirstChunk = false
                                var malformedJsonCount = 0

                                BufferedReader(
                                    InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)
                                ).use { reader ->
                                    while (true) {
                                        currentCoroutineContext().ensureActive()

                                        val line: String? = if (!receivedFirstChunk) {
                                            withTimeout(FIRST_CHUNK_TIMEOUT_MS) {
                                                withContext(Dispatchers.IO) {
                                                    reader.readLine()
                                                }
                                            }
                                        } else {
                                            withContext(Dispatchers.IO) {
                                                reader.readLine()
                                            }
                                        }

                                        if (line == null) break
                                        val trimmed = line.trim()
                                        if (trimmed.isEmpty() || trimmed.startsWith(":")) continue

                                        if (trimmed.startsWith("data:")) {
                                            val dataPayload = trimmed.removePrefix("data:").trim()
                                            if (dataPayload.isEmpty() || dataPayload == "[DONE]") continue

                                            try {
                                                val chunkText = parseSseChunkText(dataPayload)
                                                if (chunkText.isNotEmpty()) {
                                                    receivedFirstChunk = true
                                                    accumulated.append(chunkText)
                                                    emit(
                                                        GeminiStreamState.Streaming(
                                                            mode = mode,
                                                            partialText = accumulated.toString(),
                                                            latestChunk = chunkText
                                                        )
                                                    )
                                                }
                                            } catch (jsonEx: Exception) {
                                                malformedJsonCount += 1
                                                Log.w(
                                                    TAG,
                                                    "SSE veri satırı ayrıştırılamadı ($modelName): ${jsonEx.message}"
                                                )
                                            }
                                        }
                                    }
                                }

                                val finalFullText = accumulated.toString().trim()
                                if (finalFullText.isNotEmpty()) {
                                    emit(
                                        GeminiStreamState.Completed(
                                            mode = mode,
                                            fullText = finalFullText
                                        )
                                    )
                                    streamCompletedSuccessfully = true
                                } else {
                                    val emptyReason = if (malformedJsonCount > 0) {
                                        "OpenRouter SSE akışı bozuk veri döndürdü ($modelName). Lütfen tekrar deneyin."
                                    } else {
                                        "OpenRouter boş yanıt döndürdü ($modelName). Lütfen tekrar deneyin."
                                    }
                                    lastErrorMessage = emptyReason
                                    if (index < OPENROUTER_FALLBACK_MODELS.lastIndex) {
                                        Log.w(TAG, "$emptyReason Yedek modele geçiliyor.")
                                        shouldFallbackToNextModel = true
                                    } else {
                                        throw IllegalStateException(emptyReason)
                                    }
                                }
                            }
                        }
                    } catch (cancelEx: CancellationException) {
                        call.cancel()
                        throw cancelEx
                    } catch (e: Exception) {
                        call.cancel()
                        throw e
                    }

                    if (streamCompletedSuccessfully) {
                        break
                    }
                    if (!shouldFallbackToNextModel) {
                        break
                    }
                }

                if (!streamCompletedSuccessfully) {
                    emit(
                        GeminiStreamState.Error(
                            mode = mode,
                            message = lastErrorMessage
                        )
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "OpenRouter SSE analizi zaman aşımına uğradı: ${e.message}")
            emit(
                GeminiStreamState.Error(
                    mode = mode,
                    message = "Yanıt süresi zaman aşımına uğradı (ilk paket 20 sn / toplam 75 sn sınırı). Lütfen tekrar deneyin."
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "OpenRouter SSE çağrısı sırasında hata oluştu: ${e.message}")
            emit(
                GeminiStreamState.Error(
                    mode = mode,
                    message = e.localizedMessage ?: "OpenRouter API çağrısı sırasında hata oluştu."
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private fun parseSseChunkText(jsonPayload: String): String {
        val rootJson = JSONObject(jsonPayload)
        rootJson.optJSONObject("error")?.let { errObj ->
            val errMsg = errObj.optString("message", "Bilinmeyen OpenRouter SSE hatası")
            throw IllegalStateException(errMsg)
        }

        val choices = rootJson.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""

        val firstChoice = choices.optJSONObject(0) ?: return ""
        val delta = firstChoice.optJSONObject("delta") ?: return ""
        return delta.optString("content", "")
    }

    private fun extractApiErrorMessage(rawBody: String, httpCode: Int, modelName: String): String {
        return try {
            val json = JSONObject(rawBody)
            val errObj = json.optJSONObject("error")
            val msg = errObj?.optString("message").orEmpty()
            if (msg.isNotBlank()) {
                "OpenRouter API Hatası ($httpCode - $modelName): $msg"
            } else {
                "OpenRouter API Hatası (HTTP $httpCode - $modelName)"
            }
        } catch (_: Exception) {
            "OpenRouter API Hatası (HTTP $httpCode - $modelName)"
        }
    }

    /**
     * AI Görsel Stüdyosu Şeffaflığı:
     * OpenRouter metin API'si görsel üretimini desteklemediği için bu metot her zaman
     * [GeminiImageGenerationResult.Error] döndürür.
     */
    suspend fun generateImageFromPrompt(
        promptText: String,
        modelId: String = IMAGE_MODEL_DEFAULT,
        targetWidth: Int = 1024,
        targetHeight: Int = 1024
    ): GeminiImageGenerationResult = withContext(Dispatchers.Default) {
        GeminiImageGenerationResult.Error(
            reason = "Görsel üretimi OpenRouter metin API'si ile desteklenmiyor."
        )
    }

    private fun buildPromptForMode(
        mode: GeminiAnalysisMode,
        topicTitle: String,
        entries: List<EntryItem>
    ): String {
        val formattedEntries = formatEntriesForContext(entries)

        return when (mode) {
            GeminiAnalysisMode.FIVE_BULLET_SUMMARY -> """
                Sen deneyimli bir Ekşi Sözlük okuru ve içerik analistisin.
                Aşağıda "$topicTitle" başlığında yazılmış entry'ler yer almaktadır.

                GÖREVİN:
                Başlığı tam olarak 5 maddede, net, akıcı ve Türkçe olarak özetle.
                - Her madde somut bir bilgi, iddia, olay örgüsü veya sözlük yazarlarının dikkat çektiği önemli bir detayı içersin.
                - Gereksiz giriş cümleleri kurma, doğrudan 1'den 5'e kadar numaralandırılmış maddeler halinde yaz.
                - Önemli yazar tespitlerini veya yüksek favori alan noktaları maddelerde belirt.

                BAŞLIK: $topicTitle
                ENTRY'LER:
                $formattedEntries
            """.trimIndent()

            GeminiAnalysisMode.DEBATE_AND_CONSENSUS -> """
                Sen deneyimli bir Ekşi Sözlük sosyoloğu ve tartışma analistisin.
                Aşağıda "$topicTitle" başlığında yazılmış entry'ler yer almaktadır.

                GÖREVİN:
                Bu başlıktaki genel fikir ayrılıklarını ve baskın görüşü Türkçe olarak analiz et. Yanıtını şu 3 net alt başlık altında yapılandır:
                1. **Temel Fikir Ayrılıkları & Kutuplaşma Eksenleri**: Yazarlar hangi konularda ikiye (veya daha fazla kampa) bölünmüş? Karşıt argümanlar neler?
                2. **Baskın Görüş (Sözlük Konsensüsü)**: Entry'lerin çoğunluğunda ve yüksek favori alan yorumlarda ağır basan ana fikir ve duygusal ton (ironik, öfkeli, destekleyici, şüpheci vb.) nedir?
                3. **Dikkat Çeken Azınlık / Alternatif Tespitler**: Çoğunluktan farklı olarak öne sürülen özgün veya önemli karşıt tespitler neler?

                BAŞLIK: $topicTitle
                ENTRY'LER:
                $formattedEntries
            """.trimIndent()
        }
    }

    /**
     * Token sınırlarını aşmadan maksimum bağlam kalitesi sağlamak için
     * entry'leri yazar adı, tarih, favori sayısı ve içerikle biçimlendirir.
     */
    private fun formatEntriesForContext(entries: List<EntryItem>): String {
        return entries
            .take(MAX_ENTRIES_FOR_CONTEXT)
            .joinToString(separator = "\n\n---\n\n") { entry ->
                val favTag = if (entry.favoriteCount > 0) " [Fav: ${entry.favoriteCount}]" else ""
                val text = entry.plainText.take(MAX_CHARS_PER_ENTRY)
                "Entry #${entry.id} | Yazar: @${entry.author} | Tarih: ${entry.date}$favTag\n$text"
            }
    }

    companion object {
        private const val TAG = "GeminiHelper"
        const val PREFS_NAME = "eksin_gemini_prefs"
        private const val KEY_LEGACY_PLAIN_API_KEY = "gemini_api_key"
        private const val KEY_ENCRYPTED_API_KEY_CIPHERTEXT = "gemini_api_key_ciphertext"
        private const val KEY_ENCRYPTED_API_KEY_IV = "gemini_api_key_iv"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "eksin_gemini_aes_key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        private const val TOTAL_STREAM_TIMEOUT_MS = 75_000L
        private const val FIRST_CHUNK_TIMEOUT_MS = 20_000L

        const val AI_PROVIDER_NAME = "OpenRouter"
        const val AI_PROVIDER_BASE_URL = "https://openrouter.ai/api/v1"

        const val MODEL_NAME = "openai/gpt-4o-mini"
        val OPENROUTER_FALLBACK_MODELS = listOf(
            "openai/gpt-4o-mini",
            "openai/gpt-4o",
            "anthropic/claude-3.5-sonnet",
            "google/gemini-2.0-flash-001"
        )
        const val IMAGE_MODEL_DEFAULT = "openai/gpt-4o-mini"
        const val IMAGE_MODEL_PRO = "openai/gpt-4o-mini"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ENTRIES_FOR_CONTEXT = 40
        private const val MAX_CHARS_PER_ENTRY = 1200
    }
}
