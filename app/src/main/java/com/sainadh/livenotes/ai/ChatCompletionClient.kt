package com.sainadh.livenotes.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class LlmProvider(val displayName: String, val baseUrl: String, val defaultModel: String) {
    OPENAI(
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-5.4"
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com/chat/completions",
        defaultModel = "deepseek-chat"
    ),
    QWEN(
        displayName = "Qwen",
        baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
        defaultModel = "qwen-plus"
    )
}

data class LlmSummaryRequest(
    val provider: LlmProvider,
    val apiKey: String,
    val model: String,
    val priorSummary: String,
    val runningContext: String,
    val recentTranscript: String
)

data class LlmConnectionRequest(
    val provider: LlmProvider,
    val apiKey: String,
    val model: String
)

data class LlmConnectionResult(
    val provider: LlmProvider,
    val model: String,
    val preview: String
)

data class LlmSummaryResult(
    val summary: String,
    val runningContext: String,
    val actionItems: List<String>
)

class ChatCompletionClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
    }
) {
    suspend fun summarizeConversation(request: LlmSummaryRequest): Result<LlmSummaryResult> {
        return runCatching {
            val resolvedModel = request.model.ifBlank { request.provider.defaultModel }
            val response = withContext(Dispatchers.IO) {
                executeChatRequest(
                    provider = request.provider,
                    apiKey = request.apiKey,
                    payload = ChatCompletionsRequest(
                    model = resolvedModel,
                    messages = listOf(
                        Message(
                            role = "system",
                            content = """
                                You maintain live meeting notes for a mobile app.
                                Return strictly valid JSON with this shape:
                                {
                                  \"summary\": \"short paragraph\",
                                  \"runningContext\": \"compact cumulative context for the next call\",
                                  \"actionItems\": [\"owner + task\"]
                                }
                                Keep summaries concise and factual.
                            """.trimIndent()
                        ),
                        Message(
                            role = "user",
                            content = """
                                Previous summary:
                                ${request.priorSummary.ifBlank { "(none yet)" }}

                                Running context:
                                ${request.runningContext.ifBlank { "(none yet)" }}

                                New transcript chunk window:
                                ${request.recentTranscript}
                            """.trimIndent()
                        )
                    ),
                    temperature = 0.2
                )
                )
            }
            val content = response.choices.firstOrNull()?.message?.content.orEmpty()
            parseStructuredContent(content)
        }
    }

    suspend fun testConnection(request: LlmConnectionRequest): Result<LlmConnectionResult> {
        return runCatching {
            val resolvedModel = request.model.ifBlank { request.provider.defaultModel }
            val response = withContext(Dispatchers.IO) {
                executeChatRequest(
                    provider = request.provider,
                    apiKey = request.apiKey,
                    payload = ChatCompletionsRequest(
                    model = resolvedModel,
                    messages = listOf(
                        Message(role = "system", content = "Reply briefly."),
                        Message(role = "user", content = "Reply with the single word OK.")
                    ),
                    temperature = 0.0
                )
                )
            }
            val preview = response.choices.firstOrNull()?.message?.content.orEmpty().trim()
            if (preview.isBlank()) {
                error("Model returned an empty response")
            }
            LlmConnectionResult(
                provider = request.provider,
                model = resolvedModel,
                preview = preview
            )
        }
    }

    internal fun parseStructuredContent(raw: String): LlmSummaryResult {
        val normalized = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return json.decodeFromString(LlmSummaryResultPayload.serializer(), normalized).toDomain()
    }

    private fun executeChatRequest(
        provider: LlmProvider,
        apiKey: String,
        payload: ChatCompletionsRequest
    ): ChatCompletionsResponse {
        val resolvedKey = apiKey.trim()
        if (resolvedKey.isBlank()) {
            error("Missing API key")
        }
        val body = json.encodeToString(ChatCompletionsRequest.serializer(), payload)
        val httpRequest = Request.Builder()
            .url(provider.baseUrl)
            .addHeader("Authorization", "Bearer $resolvedKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("LLM request failed: ${response.code} $responseText")
            }
            return json.decodeFromString(ChatCompletionsResponse.serializer(), responseText)
        }
    }
}

@Serializable
private data class ChatCompletionsRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double
)

@Serializable
private data class Message(
    val role: String,
    val content: String
)

@Serializable
private data class ChatCompletionsResponse(
    val choices: List<Choice>
)

@Serializable
private data class Choice(
    val message: AssistantMessage
)

@Serializable
private data class AssistantMessage(
    val role: String,
    val content: String
)

@Serializable
private data class LlmSummaryResultPayload(
    @SerialName("summary") val summary: String,
    @SerialName("runningContext") val runningContext: String,
    @SerialName("actionItems") val actionItems: List<String> = emptyList()
) {
    fun toDomain(): LlmSummaryResult = LlmSummaryResult(
        summary = summary,
        runningContext = runningContext,
        actionItems = actionItems.filter { it.isNotBlank() }
    )
}
