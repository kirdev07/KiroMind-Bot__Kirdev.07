package com.kirdev.kiromindbot.feature.ai.data

import com.kirdev.kiromindbot.feature.engine.domain.AiFailure
import com.kirdev.kiromindbot.feature.engine.domain.AiRequest
import com.kirdev.kiromindbot.feature.engine.domain.ExternalAiProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini (generativelanguage.googleapis.com). Has a free tier with a key from Google AI Studio.
 * [model] is editable in settings; the default alias follows Google's current Flash model.
 */
class GeminiProvider(
    private val key: suspend () -> String?,
    private val model: () -> String,
    private val http: suspend (String, Map<String, String>, JSONObject?) -> JSONObject = { u, h, b -> AiHttp.send("Gemini", u, h, b) }
) : ExternalAiProvider {
    override val name = "Gemini"

    override suspend fun complete(request: AiRequest): String {
        val k = key() ?: throw AiFailure("ключ не задан")
        return parse(http(url(model()), headers(k), body(request)))
    }

    /** Free check: model metadata, no generation. */
    suspend fun check(k: String): String? = runCatching { http(BASE + model().trim(), headers(k), null); null }
        .getOrElse { (it as? AiFailure)?.reason ?: "ошибка Gemini" }

    /** Models this key can use for text generation (free metadata call). */
    suspend fun listModels(): List<String> {
        val k = key() ?: throw AiFailure("ключ не задан")
        return parseModels(http(BASE.trimEnd('/') + "?pageSize=200", headers(k), null))
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

        fun url(model: String) = "$BASE${model.trim()}:generateContent"
        fun headers(key: String) = mapOf("x-goog-api-key" to key)

        fun body(request: AiRequest): JSONObject = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", AiPrompt.SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", AiPrompt.userText(request))))))
            // Room for the model's internal reasoning on thinking-capable Flash models.
            .put("generationConfig", JSONObject().put("maxOutputTokens", 2048))

        fun parseModels(json: JSONObject): List<String> {
            val models = json.optJSONArray("models") ?: return emptyList()
            return (0 until models.length()).mapNotNull { i ->
                val m = models.optJSONObject(i) ?: return@mapNotNull null
                val methods = m.optJSONArray("supportedGenerationMethods")
                val canGenerate = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                m.optString("name").removePrefix("models/").takeIf { canGenerate && it.isNotEmpty() }
            }.sorted()
        }

        fun parse(json: JSONObject): String {
            json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }
                ?.let { throw AiFailure("запрос отклонён фильтрами Gemini") }
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: throw AiFailure("Gemini не дал ответа")
            if (candidate.optString("finishReason") == "SAFETY") throw AiFailure("ответ отклонён фильтрами Gemini")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            return (0 until parts.length()).mapNotNull { i ->
                parts.optJSONObject(i)?.takeIf { !it.optBoolean("thought") }?.optString("text")
            }.joinToString("").trim()
        }
    }
}

/** YandexGPT via Yandex Cloud Foundation Models. Needs an API key and the cloud folder id. */
class YandexGptProvider(
    private val key: suspend () -> String?,
    private val folderId: () -> String,
    private val model: () -> String,
    private val http: suspend (String, Map<String, String>, JSONObject?) -> JSONObject = { u, h, b -> AiHttp.send("YandexGPT", u, h, b) }
) : ExternalAiProvider {
    override val name = "YandexGPT"

    override suspend fun complete(request: AiRequest): String {
        val k = key() ?: throw AiFailure("ключ не задан")
        val folder = folderId().trim().ifEmpty { throw AiFailure("не указан ID каталога Yandex Cloud") }
        return parse(http(URL, headers(k, folder), body(request, folder, model())))
    }

    /** Yandex has no free metadata endpoint: a 5-token completion verifies key, folder and model. */
    suspend fun check(k: String): String? {
        val folder = folderId().trim().ifEmpty { return "сначала укажите ID каталога" }
        return runCatching { http(URL, headers(k, folder), body(AiRequest("Ответь: ok"), folder, model(), maxTokens = 5)); null }
            .getOrElse { (it as? AiFailure)?.reason ?: "ошибка YandexGPT" }
    }

    companion object {
        const val DEFAULT_MODEL = "yandexgpt-lite/latest"
        private const val URL = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"

        fun headers(key: String, folder: String) = mapOf("Authorization" to "Api-Key $key", "x-folder-id" to folder)

        fun body(request: AiRequest, folder: String, model: String, maxTokens: Int = 500): JSONObject = JSONObject()
            .put("modelUri", "gpt://$folder/${model.trim()}")
            .put("completionOptions", JSONObject().put("stream", false).put("temperature", 0.6).put("maxTokens", maxTokens.toString()))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("text", AiPrompt.SYSTEM))
                .put(JSONObject().put("role", "user").put("text", AiPrompt.userText(request))))

        fun parse(json: JSONObject): String {
            val alt = json.optJSONObject("result")?.optJSONArray("alternatives")?.optJSONObject(0)
                ?: throw AiFailure("YandexGPT не дал ответа")
            if (alt.optString("status").contains("CONTENT_FILTER")) throw AiFailure("ответ отклонён фильтрами YandexGPT")
            return alt.optJSONObject("message")?.optString("text").orEmpty().trim()
        }
    }
}

/**
 * Any OpenAI-compatible Chat Completions API (Groq, OpenRouter, a self-hosted server...).
 * Base URL must be HTTPS. Presets in [PRESETS] fill URL and model; both stay editable.
 */
class OpenAiCompatibleProvider(
    private val key: suspend () -> String?,
    private val baseUrl: () -> String,
    private val model: () -> String,
    private val http: suspend (String, Map<String, String>, JSONObject?) -> JSONObject = { u, h, b -> AiHttp.send("OpenAI-API", u, h, b) }
) : ExternalAiProvider {
    override val name = "OpenAI-API"

    override suspend fun complete(request: AiRequest): String {
        val k = key() ?: throw AiFailure("ключ не задан")
        return parse(http(endpoint(baseUrl(), "chat/completions"), headers(k), body(request, model())))
    }

    /** Free check: list models. */
    suspend fun check(k: String): String? = runCatching { http(endpoint(baseUrl(), "models"), headers(k), null); null }
        .getOrElse { (it as? AiFailure)?.reason ?: "ошибка API" }

    /** Model ids offered by the service for this key (free call). Speech/embedding models are hidden. */
    suspend fun listModels(): List<String> {
        val k = key() ?: throw AiFailure("ключ не задан")
        return parseModels(http(endpoint(baseUrl(), "models"), headers(k), null))
    }

    data class Preset(val title: String, val baseUrl: String, val model: String)

    companion object {
        val PRESETS = listOf(
            Preset("Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-20b"),
            Preset("OpenRouter", "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free"),
            Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini")
        )

        fun endpoint(base: String, path: String): String {
            val b = base.trim().trimEnd('/')
            AiHttp.requireHttps(b)
            return "$b/$path"
        }

        fun headers(key: String) = mapOf("Authorization" to "Bearer $key")

        fun body(request: AiRequest, model: String): JSONObject = JSONObject()
            .put("model", model.trim())
            .put("max_tokens", 500)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", AiPrompt.SYSTEM))
                .put(JSONObject().put("role", "user").put("content", AiPrompt.userText(request))))

        private val notChat = Regex("whisper|tts|orpheus|embed|guard|moderation|transcribe|speech|image|dall-e", RegexOption.IGNORE_CASE)

        fun parseModels(json: JSONObject): List<String> {
            val data = json.optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).mapNotNull { i ->
                data.optJSONObject(i)?.takeIf { it.optBoolean("active", true) }?.optString("id")?.takeIf { it.isNotEmpty() && !notChat.containsMatchIn(it) }
            }.sorted()
        }

        fun parse(json: JSONObject): String {
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: throw AiFailure("API не дал ответа")
            if (choice.optString("finish_reason") == "content_filter") throw AiFailure("ответ отклонён фильтрами API")
            return choice.optJSONObject("message")?.optString("content").orEmpty().trim()
        }
    }
}
