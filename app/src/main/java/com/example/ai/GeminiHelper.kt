package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.EntryItem
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.HarmBlockThreshold
import com.google.firebase.ai.type.HarmCategory
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SafetySetting
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

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
 * Firebase AI Logic (`Firebase.ai`) ve `gemini-3.8-flash` modelini kullanarak
 * o an okunan başlıktaki entry'leri analiz eden ve sonuçları `generateContentStream`
 * ile gerçek zamanlı (streaming) döndüren yardımcı sınıf.
 */
@Singleton
class GeminiHelper @Inject constructor() {

    private val safetySettings = listOf(
        SafetySetting(HarmCategory.HARASSMENT, HarmBlockThreshold.ONLY_HIGH),
        SafetySetting(HarmCategory.HATE_SPEECH, HarmBlockThreshold.ONLY_HIGH),
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, HarmBlockThreshold.ONLY_HIGH),
        SafetySetting(HarmCategory.DANGEROUS_CONTENT, HarmBlockThreshold.ONLY_HIGH)
    )

    private val textGenerationConfig = generationConfig {
        temperature = 0.35f
        topK = 40
        topP = 0.95f
        maxOutputTokens = 2048
    }

    /**
     * Lazy initialization (`by lazy`) ile `Firebase.ai.generativeModel("gemini-3.8-flash")`
     * örneğini ilk kullanımda güvenli şekilde başlatır.
     */
    private val textModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = textGenerationConfig,
            safetySettings = safetySettings
        )
    }

    private val nanoBananaImageModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = IMAGE_MODEL_DEFAULT,
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.IMAGE)
            }
        )
    }

    private val proImageModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = IMAGE_MODEL_PRO,
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.IMAGE)
            }
        )
    }

    /**
     * a) "Başlığı 5 maddede özetle" işlemini `generateContentStream` üzerinden
     * kümülatif ve anlık metin akışı ([Flow]) olarak döndürür.
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
     * b) "Genel fikir ayrılıklarını ve baskın görüşü çıkar" işlemini `generateContentStream`
     * üzerinden kümülatif ve anlık metin akışı ([Flow]) olarak döndürür.
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
     * Seçilen [GeminiAnalysisMode] için prompt oluşturur ve `textModel.generateContentStream(prompt)`
     * akışını 60 saniyelik gerçekçi zaman aşımı (`withTimeout(60_000L)`) ile başlatır.
     */
    fun executeStreamingAnalysis(
        mode: GeminiAnalysisMode,
        topicTitle: String,
        entries: List<EntryItem>
    ): Flow<GeminiStreamState> = flow {
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

        val prompt = buildPromptForMode(
            mode = mode,
            topicTitle = topicTitle,
            entries = entries
        )

        val accumulated = StringBuilder()

        try {
            withTimeout(60_000L) {
                textModel.generateContentStream(prompt).collect { responseChunk ->
                    val chunkText = responseChunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        accumulated.append(chunkText)
                        emit(
                            GeminiStreamState.Streaming(
                                mode = mode,
                                partialText = accumulated.toString(),
                                latestChunk = chunkText
                            )
                        )
                    }
                }
            }

            val finalResult = accumulated.toString().trim()
            if (finalResult.isNotEmpty()) {
                emit(
                    GeminiStreamState.Completed(
                        mode = mode,
                        fullText = finalResult
                    )
                )
            } else {
                emit(
                    GeminiStreamState.Error(
                        mode = mode,
                        message = "Model boş yanıt döndürdü."
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini streaming analizi zaman aşımına uğradı", e)
            emit(
                GeminiStreamState.Error(
                    mode = mode,
                    message = "Yanıt süresi 60 saniyeyi aştı. Lütfen tekrar deneyin."
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini streaming analizi sırasında hata oluştu", e)
            emit(
                GeminiStreamState.Error(
                    mode = mode,
                    message = e.localizedMessage ?: "Gemini streaming analizi sırasında hata oluştu."
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Firebase AI Logic (`gemini-nano-banana-2.1` veya `gemini-3-pro-image`) ile
     * metinden görsel üretir.
     */
    suspend fun generateImageFromPrompt(
        promptText: String,
        modelId: String = IMAGE_MODEL_DEFAULT
    ): Bitmap? {
        val targetModel = if (modelId == IMAGE_MODEL_PRO) proImageModel else nanoBananaImageModel
        val response = withTimeout(60_000L) {
            targetModel.generateContent(promptText)
        }
        val imagePart = response.candidates.firstOrNull()
            ?.content?.parts?.filterIsInstance<ImagePart>()
            ?.firstOrNull()
        return imagePart?.image
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
        const val MODEL_NAME = "gemini-3.8-flash"
        const val IMAGE_MODEL_DEFAULT = "gemini-nano-banana-2.1"
        const val IMAGE_MODEL_PRO = "gemini-3-pro-image"
        private const val MAX_ENTRIES_FOR_CONTEXT = 40
        private const val MAX_CHARS_PER_ENTRY = 1200
    }
}
