package com.charles.livecaptionn.story

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class LlmUsageStats(
    val calls: Int = 0,
    val totalTokens: Long = 0L,
    val lastError: String? = null
)

data class LlmCorrectionResult(
    val text: String,
    val corrected: Boolean,
    val matchedLine: Int? = null
)

/**
 * Optional, token-minimal OpenAI-compatible LLM post-processor (works with
 * Alibaba Cloud Bailian / DashScope, DeepSeek, SiliconFlow, etc.).
 *
 * Uses a short sliding window of Chinese story lines ONLY to fix misheard
 * homophones, dropped words, and proper nouns while keeping natural spoken English.
 */
class LlmCaptionCorrector {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private val mutableStats = MutableStateFlow(LlmUsageStats())
    val stats: StateFlow<LlmUsageStats> = mutableStats.asStateFlow()

    fun resetStats() {
        mutableStats.value = LlmUsageStats()
    }

    suspend fun correct(
        rawEnglish: String,
        previousEnglish: String?,
        windowLines: List<Pair<Int, String>>,
        baseUrl: String,
        apiKey: String,
        model: String
    ): LlmCorrectionResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanBase = baseUrl.trim().trimEnd('/')
        if (cleanKey.isBlank() || cleanBase.isBlank() || rawEnglish.isBlank()) {
            return@withContext LlmCorrectionResult(rawEnglish, corrected = false)
        }

        val endpoint = if (cleanBase.endsWith("/chat/completions")) cleanBase else "$cleanBase/chat/completions"
        val userPrompt = buildUserPrompt(rawEnglish, previousEnglish, windowLines)
        val payload = ChatRequest(
            model = model.trim().ifBlank { "qwen-turbo" },
            temperature = 0.0,
            max_tokens = 90,
            messages = listOf(
                ChatMessage("system", SYSTEM_PROMPT),
                ChatMessage("user", userPrompt)
            )
        )

        try {
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $cleanKey")
                .header("Content-Type", "application/json")
                .post(requestAdapter.toJson(payload).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    mutableStats.value = mutableStats.value.copy(lastError = "HTTP ${resp.code}")
                    return@withContext LlmCorrectionResult(rawEnglish, corrected = false)
                }
                val parsed = responseAdapter.fromJson(body)
                val usageTokens = parsed?.usage?.total_tokens ?: 0L
                val content = parsed?.choices?.firstOrNull()?.message?.content.orEmpty()
                mutableStats.value = LlmUsageStats(
                    calls = mutableStats.value.calls + 1,
                    totalTokens = mutableStats.value.totalTokens + usageTokens,
                    lastError = null
                )
                parseModelOutput(content, rawEnglish)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LLM correction skipped: ${t.message}")
            mutableStats.value = mutableStats.value.copy(lastError = t.message ?: "请求失败")
            LlmCorrectionResult(rawEnglish, corrected = false)
        }
    }

    private data class ChatMessage(val role: String, val content: String)
    private data class ChatRequest(
        val model: String,
        val temperature: Double,
        val max_tokens: Int,
        val messages: List<ChatMessage>
    )
    private data class ChatChoice(val message: ChatMessage? = null)
    private data class ChatUsage(val total_tokens: Long = 0L)
    private data class ChatResponse(
        val choices: List<ChatChoice> = emptyList(),
        val usage: ChatUsage? = null
    )
    private data class OutputJson(val en: String? = null, val line: Int? = null)

    companion object {
        private const val TAG = "LlmCaptionCorrector"
        private const val SYSTEM_PROMPT =
            "Fix noisy English ASR of Reverse: 1999 voice lines using the nearby Chinese script ONLY to resolve misheard homophones, dropped words, and proper nouns. Keep natural spoken English phrasing; do NOT re-translate literally. Return strict JSON: {\"en\":\"...\",\"line\":<matched_line_num_or_0>}"

        private val moshi: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        private val requestAdapter = moshi.adapter(ChatRequest::class.java)
        private val responseAdapter = moshi.adapter(ChatResponse::class.java)
        private val outputAdapter = moshi.adapter(OutputJson::class.java)

        internal fun buildUserPrompt(
            rawEnglish: String,
            previousEnglish: String?,
            windowLines: List<Pair<Int, String>>
        ): String = buildString {
            if (windowLines.isNotEmpty()) {
                append("ZH:\n")
                for ((idx, line) in windowLines) {
                    append(idx).append(": ").append(line.take(90)).append('\n')
                }
            }
            if (!previousEnglish.isNullOrBlank()) {
                append("Prev EN: ").append(previousEnglish.take(120)).append('\n')
            }
            append("ASR EN: ").append(rawEnglish)
        }

        internal fun parseModelOutput(content: String, fallback: String): LlmCorrectionResult {
            val trimmed = content.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start < 0 || end <= start) {
                return LlmCorrectionResult(fallback, corrected = false)
            }
            val parsed = runCatching { outputAdapter.fromJson(trimmed.substring(start, end + 1)) }.getOrNull()
                ?: return LlmCorrectionResult(fallback, corrected = false)
            val en = parsed.en?.trim().orEmpty()
            val line = parsed.line?.takeIf { it > 0 }
            if (en.isBlank()) return LlmCorrectionResult(fallback, corrected = false, matchedLine = line)
            val changed = !en.equals(fallback.trim(), ignoreCase = false)
            return LlmCorrectionResult(text = en, corrected = changed, matchedLine = line)
        }
    }
}
