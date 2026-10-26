package com.kirdev.kiromindbot.feature.ai.data

import com.kirdev.kiromindbot.feature.engine.domain.AiFailure
import com.kirdev.kiromindbot.feature.engine.domain.AiRequest
import com.kirdev.kiromindbot.feature.engine.domain.ExternalAiProvider
import com.kirdev.kiromindbot.feature.security.SecretRead
import com.kirdev.kiromindbot.feature.security.SecretStore
import com.kirdev.kiromindbot.feature.settings.domain.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Supported external providers. [secretName] is where each key lives in the SecretStore. */
enum class AiKind(val title: String, val secretName: String, val keyHint: String) {
    GEMINI("Gemini", "gemini_api_key", "Ключ из Google AI Studio"),
    CLAUDE("Claude", "anthropic_api_key", "Ключ sk-ant-…"),
    YANDEX("YandexGPT", "yandex_api_key", "API-ключ сервисного аккаунта"),
    OPENAI("OpenAI-совместимый", "openai_compat_api_key", "Ключ API (Groq, OpenRouter…)");

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: GEMINI
    }
}

/**
 * The single [ExternalAiProvider] the pipeline sees. Routes each request to the provider the
 * owner selected; keys are read from the Keystore-backed store per request and never logged.
 */
class AiRouter(
    private val secrets: SecretStore,
    private val settings: () -> AppPreferences
) : ExternalAiProvider {

    private suspend fun key(kind: AiKind) = (secrets.read(kind.secretName) as? SecretRead.Value)?.secret

    val claude = ClaudeProvider(secrets)
    val gemini = GeminiProvider({ key(AiKind.GEMINI) }, { settings().geminiModel })
    val yandex = YandexGptProvider({ key(AiKind.YANDEX) }, { settings().yandexFolderId }, { settings().yandexModel })
    val openAi = OpenAiCompatibleProvider({ key(AiKind.OPENAI) }, { settings().openAiBaseUrl }, { settings().openAiModel })

    val active: AiKind get() = AiKind.of(settings().aiProvider)

    private fun provider(kind: AiKind): ExternalAiProvider = when (kind) {
        AiKind.CLAUDE -> claude
        AiKind.GEMINI -> gemini
        AiKind.YANDEX -> yandex
        AiKind.OPENAI -> openAi
    }

    override val name: String get() = provider(active).name

    override suspend fun complete(request: AiRequest): String = provider(active).complete(request)

    private val keysFlow = MutableStateFlow<Set<AiKind>>(emptySet())
    /** Providers that have a readable stored key. */
    val keys: StateFlow<Set<AiKind>> = keysFlow

    suspend fun refreshKeys() {
        keysFlow.value = AiKind.entries.filter { key(it) != null }.toSet()
    }

    /** Verifies the key with the provider, then stores it encrypted. Returns an error text or null. */
    suspend fun saveKey(kind: AiKind, raw: String): String? {
        val k = raw.trim()
        if (k.length < 20 || k.any { it.isWhitespace() }) return "Ключ выглядит неполным — проверьте копирование"
        if (kind == AiKind.CLAUDE && !k.startsWith("sk-ant-")) return "Ключ Claude начинается с sk-ant-"
        val problem = try {
            when (kind) {
                AiKind.CLAUDE -> claude.check(k)
                AiKind.GEMINI -> gemini.check(k)
                AiKind.YANDEX -> yandex.check(k)
                AiKind.OPENAI -> openAi.check(k)
            }
        } catch (e: AiFailure) {
            e.reason
        }
        if (problem != null) return problem
        if (!secrets.save(kind.secretName, k)) return "Не удалось зашифровать ключ — он не сохранён"
        if (kind == AiKind.CLAUDE) claude.reset()
        refreshKeys()
        return null
    }

    /** Available models for providers that can list them; null when the provider has no list. */
    suspend fun listModels(kind: AiKind): List<String>? = when (kind) {
        AiKind.GEMINI -> gemini.listModels()
        AiKind.OPENAI -> openAi.listModels()
        AiKind.CLAUDE, AiKind.YANDEX -> null
    }

    suspend fun deleteKey(kind: AiKind) {
        secrets.delete(kind.secretName)
        if (kind == AiKind.CLAUDE) claude.reset()
        refreshKeys()
    }
}
