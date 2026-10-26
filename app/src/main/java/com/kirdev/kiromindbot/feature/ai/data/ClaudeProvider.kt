package com.kirdev.kiromindbot.feature.ai.data

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.kirdev.kiromindbot.feature.engine.domain.AiFailure
import com.kirdev.kiromindbot.feature.engine.domain.AiRequest
import com.kirdev.kiromindbot.feature.engine.domain.ExternalAiProvider
import com.kirdev.kiromindbot.feature.security.SecretNames
import com.kirdev.kiromindbot.feature.security.SecretRead
import com.kirdev.kiromindbot.feature.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.time.Duration

/**
 * Claude via the official Anthropic SDK. Used only for questions MindCore could not answer,
 * only when the owner enabled it. The API key comes from the Keystore-backed [SecretStore]
 * and lives in memory only inside the SDK client; nothing here logs prompts, replies or the key.
 */
class ClaudeProvider(
    private val secrets: SecretStore,
    private val model: String = MODEL,
    private val clientFactory: (String) -> AnthropicClient = ::defaultClient
) : ExternalAiProvider {
    override val name = "Claude"

    @Volatile private var cached: Pair<String, AnthropicClient>? = null

    /** Drops the in-memory client, e.g. after the key was replaced or deleted. */
    fun reset() { cached = null }

    override suspend fun complete(request: AiRequest): String {
        val client = client() ?: throw AiFailure("ключ не задан")
        val params = buildParams(request)
        return runInterruptible(Dispatchers.IO) {
            try {
                val message = client.beta().messages().create(params)
                // A safeguard decline arrives as HTTP 200; it is not an answer to send.
                if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw AiFailure("запрос отклонён фильтрами Claude")
                message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()
            } catch (e: AnthropicServiceException) {
                throw AiFailure(describe(e))
            } catch (e: AnthropicIoException) {
                throw AiFailure("нет связи с Anthropic")
            }
        }
    }

    /** Checks the key with the free Models API before it is stored. */
    suspend fun check(key: String): String? = runInterruptible(Dispatchers.IO) {
        try {
            clientFactory(key).models().retrieve(model)
            null
        } catch (e: AnthropicServiceException) {
            describe(e)
        } catch (e: AnthropicIoException) {
            "нет связи с Anthropic"
        }
    }

    internal fun buildParams(request: AiRequest): MessageCreateParams {
        val context = if (request.history.isEmpty()) "" else
            "Предыдущие реплики собеседника:\n" + request.history.takeLast(5).joinToString("\n") { "- $it" } + "\n\n"
        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(2048L)
            .system(SYSTEM_PROMPT)
            .addUserMessage(context + request.text)
            // Short chat replies: low effort keeps latency and cost down.
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            // If a safety classifier declines, Anthropic retries on its recommended model instead of failing.
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
    }

    private suspend fun client(): AnthropicClient? {
        val key = (secrets.read(SecretNames.ANTHROPIC_KEY) as? SecretRead.Value)?.secret ?: return null
        cached?.let { (k, c) -> if (k == key) return c }
        return clientFactory(key).also { cached = key to it }
    }

    companion object {
        const val MODEL = "claude-opus-5-5"

        const val SYSTEM_PROMPT =
            "Ты — KiroMind Bot, дружелюбный бот в Telegram. Тебя спрашивают, когда в базе ответов владельца нет подходящего ответа. " +
                "Отвечай кратко, 1–3 предложения, простым текстом без Markdown, на языке собеседника. " +
                "Не выдумывай факты о владельце бота и его делах; если не знаешь — так и скажи."

        private fun defaultClient(key: String): AnthropicClient = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(20))
            .maxRetries(1)
            .build()

        /** User-facing reason; never includes the key or message text. */
        fun describe(e: AnthropicServiceException): String = when (e) {
            is UnauthorizedException -> "ключ не принят Anthropic"
            is PermissionDeniedException -> "у ключа нет доступа к модели"
            is NotFoundException -> "модель недоступна для этого ключа"
            is RateLimitException -> "лимит Anthropic, попробуйте позже"
            is BadRequestException -> "Anthropic отклонил запрос (${e.statusCode()})"
            is InternalServerException -> if (e.statusCode() == 529) "Anthropic перегружен" else "ошибка Anthropic (${e.statusCode()})"
            else -> "ошибка Anthropic (${e.statusCode()})"
        }
    }
}
