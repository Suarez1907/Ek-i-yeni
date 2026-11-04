package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.EntryItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Gemini analiz modları:
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
 * Google Services / Firebase gerektirmeden doğrudan Google AI Studio (Free Tier) API anahtarı ve
 * OkHttp istemcisi üzerinden `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=$apiKey`
 * REST endpoint'ine JSON POST isteği atarak çalışan Gemini yardımcı sınıfı.
 *
 * HTTP 404, 429 veya 503 (yoğunluk) durumunda sırasıyla şu modellere otomatik yedek (fallback) istek yapar:
 * 1) `gemini-3.5-flash-lite`
 * 2) `gemini-flash-lite-latest`
 * 3) `gemini-2.5-flash-lite`
 * 4) `gemini-3.8-flash`
 *
 * Kullanıcının Ayarlar/Profil ekranından veya özetleme alt penceresinden girdiği API anahtarını
 * `SharedPreferences` içinde saklar.
 */
@Singleton
class GeminiHelper @Inject constructor(
    @ApplicationContext private val appContext: Context,
    baseOkHttpClient: OkHttpClient
) {

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Ekşi Sözlük özel Cloudflare/User-Agent interceptor'larından etkilenmeyen,
     * doğrudan Google Generative Language REST API için yapılandırılmış OkHttp istemcisi.
     */
    private val geminiHttpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Kullanıcının `SharedPreferences` içine kaydettiği özel API anahtarını döndürür.
     */
    fun getUserSavedApiKey(): String {
        return prefs.getString(KEY_GEMINI_API_KEY, "")?.trim().orEmpty()
    }

    /**
     * Kullanıcının girdiği ücretsiz Gemini API anahtarını `SharedPreferences` içine kaydeder.
     */
    fun saveUserApiKey(apiKey: String) {
        prefs.edit()
            .putString(KEY_GEMINI_API_KEY, apiKey.trim())
            .apply()
    }

    /**
     * Kayıtlı Gemini API anahtarını `SharedPreferences` içinden temizler.
     */
    fun clearUserApiKey() {
        prefs.edit()
            .remove(KEY_GEMINI_API_KEY)
            .apply()
    }

    /**
     * `SharedPreferences` içindeki kullanıcı anahtarını döndürür.
     * Kullanıcı kendi anahtarını girip kaydetmeden otomatik istek atılmaması için yalnızca
     * `SharedPreferences` içindeki kayıtlı anahtar dikkate alınır.
     */
    fun resolveActiveApiKey(): String {
        return getUserSavedApiKey()
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
     * `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=$apiKey`
     * REST endpoint'ine (404/429/503 durumunda otomatik fallback zinciriyle) JSON POST isteği atarak
     * yanıtı canlı akış ([GeminiStreamState]) halinde sunar.
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

        try {
            val generatedText = withTimeout(75_000L) {
                executeGenerateContentPost(apiKey = apiKey, promptText = promptText)
            }

            val cleanResult = generatedText.trim()
            if (cleanResult.isEmpty()) {
                emit(
                    GeminiStreamState.Error(
                        mode = mode,
                        message = "Gemini boş yanıt döndürdü. Lütfen tekrar deneyin."
                    )
                )
                return@flow
            }

            // Yanıtı kullanıcıya akıcı bir canlı metin akışı (streaming efekti) ile ilet
            val accumulated = StringBuilder()
            val paragraphs = cleanResult.split(Regex("(?<=\\n)"))
            for (chunk in paragraphs) {
                accumulated.append(chunk)
                emit(
                    GeminiStreamState.Streaming(
                        mode = mode,
                        partialText = accumulated.toString(),
                        latestChunk = chunk
                    )
                )
                delay(18L)
            }

            emit(
                GeminiStreamState.Completed(
                    mode = mode,
                    fullText = cleanResult
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini analizi zaman aşımına uğradı", e)
            emit(
                GeminiStreamState.Error(
                    mode = mode,
                    message = "Yanıt süresi zaman aşımına uğradı. Lütfen tekrar deneyin."
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini REST API çağrısı sırasında hata oluştu", e)
            emit(
                GeminiStreamState.Error(
                    mode = mode,
                    message = e.localizedMessage ?: "Gemini API çağrısı sırasında hata oluştu."
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Birincil olarak `gemini-3.5-flash-lite` modeline JSON POST isteği gönderir.
     * Eğer sunucu HTTP 404, 429 veya 503 (yoğunluk) hatası dönerse kullanıcıya hata göstermeden önce
     * sırasıyla şu modellere otomatik fallback (yedek istek) yapar:
     * 1) `gemini-3.5-flash-lite`
     * 2) `gemini-flash-lite-latest`
     * 3) `gemini-2.5-flash-lite`
     * 4) `gemini-3.8-flash`
     */
    private suspend fun executeGenerateContentPost(
        apiKey: String,
        promptText: String
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", promptText)
                            )
                        )
                    }
                )
            )
        }
        val payloadString = payload.toString()

        var lastErrorMessage = "Gemini API çağrısı başarısız oldu."

        for ((index, modelName) in FALLBACK_MODELS.withIndex()) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            val requestBody = payloadString.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            val resultText: String? = geminiHttpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val code = response.code
                    val apiErrorMessage = extractApiErrorMessage(rawBody, code, modelName)
                    lastErrorMessage = apiErrorMessage

                    val shouldFallback = (code == 404 || code == 429 || code == 503) &&
                        index < FALLBACK_MODELS.lastIndex
                    if (shouldFallback) {
                        Log.w(
                            TAG,
                            "Model '$modelName' HTTP $code döndürdü; yedek modele (${FALLBACK_MODELS[index + 1]}) geçiliyor."
                        )
                        return@use null
                    } else {
                        throw IllegalStateException(apiErrorMessage)
                    }
                }

                parseCandidateTextFromResponse(rawBody)
            }

            if (resultText != null) {
                return@withContext resultText
            }
        }

        throw IllegalStateException(lastErrorMessage)
    }

    private fun parseCandidateTextFromResponse(rawBody: String): String {
        val rootJson = JSONObject(rawBody)
        val candidates = rootJson.optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini yanıtında 'candidates' alanı bulunamadı.")

        if (candidates.length() == 0) {
            throw IllegalStateException("Gemini yanıtı aday metin üretmedi.")
        }

        val firstCandidate = candidates.getJSONObject(0)
        val contentObj = firstCandidate.getJSONObject("content")
        val partsArray = contentObj.getJSONArray("parts")
        if (partsArray.length() == 0) {
            throw IllegalStateException("Gemini yanıtında 'parts' alanı boş döndü.")
        }

        val firstPartText = partsArray.getJSONObject(0).optString("text", "")
        if (firstPartText.isNotEmpty() && partsArray.length() == 1) {
            return firstPartText
        }

        val builder = StringBuilder()
        for (i in 0 until partsArray.length()) {
            val partObj = partsArray.optJSONObject(i)
            val partText = partObj?.optString("text", "").orEmpty()
            if (partText.isNotEmpty()) {
                builder.append(partText)
            }
        }
        return builder.toString()
    }

    private fun extractApiErrorMessage(rawBody: String, httpCode: Int, modelName: String): String {
        return try {
            val json = JSONObject(rawBody)
            val errObj = json.optJSONObject("error")
            val msg = errObj?.optString("message").orEmpty()
            if (msg.isNotBlank()) {
                "Gemini API Hatası ($httpCode - $modelName): $msg"
            } else {
                "Gemini API Hatası (HTTP $httpCode - $modelName)"
            }
        } catch (_: Exception) {
            "Gemini API Hatası (HTTP $httpCode - $modelName)"
        }
    }

    /**
     * Görsel üretim stüdyosu için önce Gemini REST API üzerinden inlineData görsel yanıtı dener,
     * Free Tier anahtarında görsel modeli aktif değilse veya kota/yetki yoksa
     * yüksek çözünürlüklü OLED Neon Poster Bitmap'i üretir.
     */
    suspend fun generateImageFromPrompt(
        promptText: String,
        modelId: String = IMAGE_MODEL_DEFAULT,
        targetWidth: Int = 1024,
        targetHeight: Int = 1024
    ): Bitmap = withContext(Dispatchers.Default) {
        val apiKey = resolveActiveApiKey()
        if (apiKey.isNotBlank()) {
            val apiBitmap = tryGenerateImageViaRest(apiKey, promptText, modelId)
            if (apiBitmap != null) {
                return@withContext apiBitmap
            }
        }
        renderOledNeonConceptBitmap(
            promptText = promptText,
            width = targetWidth.coerceIn(512, 2048),
            height = targetHeight.coerceIn(512, 2048),
            modelLabel = modelId
        )
    }

    private suspend fun tryGenerateImageViaRest(
        apiKey: String,
        promptText: String,
        modelId: String
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val endpointModel = if (modelId.isBlank()) IMAGE_MODEL_DEFAULT else modelId
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$endpointModel:generateContent?key=$apiKey"
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", promptText))
                            )
                        }
                    )
                )
            }
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            geminiHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val parts = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: return@withContext null

                for (i in 0 until parts.length()) {
                    val inlineData = parts.optJSONObject(i)?.optJSONObject("inlineData")
                    val b64 = inlineData?.optString("data").orEmpty()
                    if (b64.isNotBlank()) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (decoded != null) return@withContext decoded
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun renderOledNeonConceptBitmap(
        promptText: String,
        width: Int,
        height: Int,
        modelLabel: String
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Gerçek OLED #000000 arka plan
        canvas.drawColor(android.graphics.Color.BLACK)

        val minDim = min(width, height).toFloat()
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.5f,
                height * 0.42f,
                minDim * 0.55f,
                intArrayOf(
                    android.graphics.Color.argb(120, 0, 230, 118),
                    android.graphics.Color.argb(45, 0, 130, 72),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)

        // Siber-grid çizgileri
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(34, 0, 230, 118)
            strokeWidth = 1.5f
        }
        val step = (minDim / 12f).coerceAtLeast(32f)
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }

        // Geometrik neon halkalar
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                android.graphics.Color.rgb(0, 230, 118),
                android.graphics.Color.rgb(29, 185, 84),
                Shader.TileMode.MIRROR
            )
        }
        val centerX = width * 0.5f
        val centerY = height * 0.42f
        for (i in 1..4) {
            val radius = minDim * (0.11f * i)
            canvas.drawCircle(centerX, centerY, radius, ringPaint)
        }

        // Yörünge noktaları
        val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.rgb(0, 230, 118)
        }
        val seed = promptText.hashCode()
        for (i in 0 until 12) {
            val angle = (i * 30.0 + (seed % 360)) * (Math.PI / 180.0)
            val orbitRadius = minDim * (0.16f + (i % 3) * 0.11f)
            val nx = (centerX + cos(angle) * orbitRadius).toFloat()
            val ny = (centerY + sin(angle) * orbitRadius).toFloat()
            canvas.drawCircle(nx, ny, 6f + (i % 3) * 2f, nodePaint)
        }

        // Alt bilgi kartı
        val cardRect = RectF(
            width * 0.07f,
            height * 0.68f,
            width * 0.93f,
            height * 0.92f
        )
        val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.argb(215, 12, 16, 14)
        }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = android.graphics.Color.rgb(0, 230, 118)
        }
        canvas.drawRoundRect(cardRect, 24f, 24f, cardBgPaint)
        canvas.drawRoundRect(cardRect, 24f, 24f, cardBorderPaint)

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0, 230, 118)
            textSize = (minDim * 0.032f).coerceIn(22f, 44f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText(
            "EKSIN OLED • $modelLabel",
            cardRect.left + 28f,
            cardRect.top + 48f,
            headerPaint
        )

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(235, 245, 238)
            textSize = (minDim * 0.028f).coerceIn(18f, 36f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val words = promptText.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        val maxWidth = cardRect.width() - 56f
        for (word in words) {
            val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (bodyPaint.measureText(candidate) <= maxWidth) {
                currentLine = StringBuilder(candidate)
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())

        var textY = cardRect.top + 96f
        for (line in lines.take(3)) {
            canvas.drawText(line, cardRect.left + 28f, textY, bodyPaint)
            textY += bodyPaint.textSize * 1.35f
        }

        return bitmap
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
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val MODEL_NAME = "gemini-3.5-flash-lite"
        val FALLBACK_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-flash-lite-latest",
            "gemini-2.5-flash-lite",
            "gemini-3.8-flash"
        )
        const val IMAGE_MODEL_DEFAULT = "gemini-3.5-flash-lite"
        const val IMAGE_MODEL_PRO = "gemini-3.5-flash-lite"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ENTRIES_FOR_CONTEXT = 40
        private const val MAX_CHARS_PER_ENTRY = 1200
    }
}
