package com.example.ai

import com.example.util.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams text from the selected provider. The client has no cookie jar and
 * the prompt is the only body. Results are cached by the caller-supplied key.
 */
@Singleton
class AiTextGateway @Inject constructor(
    private val geminiHelper: GeminiHelper,
    private val settingsRepository: AiSettingsRepository,
    private val secretStore: EncryptedSecretStore
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val cache = object : android.util.LruCache<String, String>(32) {}

    fun cached(key: String): String? = cache.get(key)

    fun store(key: String, value: String) {
        if (key.isNotBlank() && value.isNotBlank()) cache.put(key, value)
    }

    fun currentConfig(): AiProviderConfig = settingsRepository.load()

    fun stream(prompt: String): Flow<AiTextChunk> = flow {
        val config = settingsRepository.load()
        when (config.provider) {
            AiProviderId.GEMINI -> emitAllGemini(prompt)
            AiProviderId.OPENAI_COMPATIBLE -> emitAllOpenAi(prompt, config)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AiTextChunk>.emitAllGemini(prompt: String) {
        val apiKey = geminiHelper.getUserSavedApiKey()
        if (apiKey.isBlank()) {
            emit(AiTextChunk.Failure("Gemini API anahtarı yok. Profil ekranından kaydedin."))
            return
        }
        var failed = "Gemini yanıt vermedi."
        for (model in GeminiModels.SUMMARY_FALLBACK_CHAIN) {
            currentCoroutineContext().ensureActive()
            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                }))
                put("generationConfig", JSONObject().put("maxOutputTokens", AiPrompts.MAX_OUTPUT_TOKENS))
            }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse"
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("x-goog-api-key", apiKey)
                .header("Accept", "text/event-stream")
                .build()
            val outcome = readSse(request) { line ->
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) "" else SseParsers.geminiDelta(data)
            }
            if (outcome == StreamRead.SUCCESS) return
            failed = outcome.message
            if (!outcome.retryable) break
        }
        emit(AiTextChunk.Failure(failed))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AiTextChunk>.emitAllOpenAi(
        prompt: String,
        config: AiProviderConfig
    ) {
        val apiKey = secretStore.read(OPENAI_SECRET)
        if (apiKey.isBlank()) {
            emit(AiTextChunk.Failure("OpenAI uyumlu sağlayıcı anahtarı yok."))
            return
        }
        val payload = JSONObject().apply {
            put("model", config.openAiModel.ifBlank { DeepSeekPreset.MODEL })
            put("stream", true)
            put("max_tokens", AiPrompts.MAX_OUTPUT_TOKENS)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
            if (config.openAiBaseUrl.contains("deepseek.com", ignoreCase = true)) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
        }
        val request = Request.Builder()
            .url(OpenAiEndpoints.chatCompletionsUrl(config.openAiBaseUrl))
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .build()
        val outcome = readSse(request) { line ->
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) "" else SseParsers.openAiDelta(data)
        }
        if (outcome != StreamRead.SUCCESS) {
            emit(AiTextChunk.Failure(outcome.message))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AiTextChunk>.readSse(
        request: Request,
        decode: (String) -> String?
    ): StreamRead {
        val call = httpClient.newCall(request)
        currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        return try {
            val response = withTimeout(20_000) { call.await() }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val retry = resp.code == 404 || resp.code == 429 || resp.code == 503
                    return StreamRead("HTTP ${resp.code}", retry)
                }
                val body = resp.body ?: return StreamRead("Boş yanıt", false)
                var sawText = false
                BufferedReader(InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = readLine(reader, call) ?: break
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("data:")) continue
                        val delta = decode(trimmed)
                        if (delta == null) break
                        if (delta.isNotEmpty()) {
                            sawText = true
                            emit(AiTextChunk.Delta(delta))
                        }
                    }
                }
                if (!sawText) return StreamRead("Model boş yanıt döndürdü.", true)
                emit(AiTextChunk.Done)
                StreamRead.SUCCESS
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            StreamRead(error.message ?: "Akış kesildi", false)
        }
    }

    private suspend fun readLine(reader: BufferedReader, call: Call): String? {
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.IO) {
            try {
                reader.readLine()
            } catch (io: IOException) {
                if (call.isCanceled() || !isActive) throw CancellationException("AI stream cancelled", io)
                throw io
            }
        }
    }

    private class StreamRead(val message: String, val retryable: Boolean) {
        companion object {
            val SUCCESS = StreamRead("", false)
        }
    }

    companion object {
        const val OPENAI_SECRET = "openai_compatible"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
