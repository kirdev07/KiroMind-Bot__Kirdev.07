package com.kirdev.kiromindbot.feature.telegram.data

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.engine.domain.BehaviorSettings
import com.kirdev.kiromindbot.feature.engine.domain.DiagnosticSink
import com.kirdev.kiromindbot.feature.engine.domain.MessagePipeline
import com.kirdev.kiromindbot.feature.security.SecretNames
import com.kirdev.kiromindbot.feature.security.SecretRead
import com.kirdev.kiromindbot.feature.security.SecretStore
import com.kirdev.kiromindbot.feature.telegram.api.ConnectionStatus
import com.kirdev.kiromindbot.feature.telegram.api.FileIdStore
import com.kirdev.kiromindbot.feature.telegram.api.TelegramApiException
import com.kirdev.kiromindbot.feature.telegram.api.TelegramBotApi
import com.kirdev.kiromindbot.feature.telegram.api.TelegramNetworkException
import com.kirdev.kiromindbot.feature.telegram.domain.TelegramBotRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Token lifecycle and the single polling job. All calls from the UI are main-safe. */
class TelegramController(
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
    private val pipeline: MessagePipeline,
    private val settings: () -> BehaviorSettings,
    private val onTeach: (String, String) -> AnswerEntry?,
    private val diagnostics: DiagnosticSink,
    private val apiFactory: (String) -> TelegramBotApi = { HttpTelegramBotApi(it) },
    private val fileIds: FileIdStore? = null,
    /** Called after a new token was stored, e.g. to start a bot that is already switched on. */
    private val onTokenSaved: () -> Unit = {}
) {
    private val statusFlow = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = statusFlow
    private val hasTokenFlow = MutableStateFlow(false)
    val hasToken: StateFlow<Boolean> = hasTokenFlow
    private var job: Job? = null

    init { scope.launch { hasTokenFlow.value = secrets.read(SecretNames.TELEGRAM_TOKEN) is SecretRead.Value } }

    /** Checks the token with getMe and stores it encrypted only if Telegram accepts it. */
    suspend fun saveToken(raw: String): TokenCheck {
        val token = raw.trim()
        if (!TOKEN_SHAPE.matches(token)) return TokenCheck.Invalid("Токен выглядит как 123456:ABC… — проверьте копирование")
        val check = check(apiFactory(token))
        if (check is TokenCheck.Ok && !secrets.save(SecretNames.TELEGRAM_TOKEN, token)) {
            return TokenCheck.Invalid("Не удалось зашифровать токен — он не сохранён")
        }
        if (check is TokenCheck.Ok) {
            hasTokenFlow.value = true
            stop()
            onTokenSaved()
        }
        return check
    }

    suspend fun checkSaved(): TokenCheck = when (val t = secrets.read(SecretNames.TELEGRAM_TOKEN)) {
        is SecretRead.Value -> check(apiFactory(t.secret))
        SecretRead.Missing -> TokenCheck.Invalid("Токен не сохранён")
        SecretRead.Unreadable -> TokenCheck.Invalid("Сохранённый токен не расшифровывается — введите его заново")
    }

    suspend fun hasStoredToken(): Boolean = secrets.read(SecretNames.TELEGRAM_TOKEN) is SecretRead.Value

    suspend fun deleteToken() {
        stop()
        secrets.delete(SecretNames.TELEGRAM_TOKEN)
        hasTokenFlow.value = false
        statusFlow.value = ConnectionStatus.NoToken
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val token = (secrets.read(SecretNames.TELEGRAM_TOKEN) as? SecretRead.Value)?.secret
            if (token == null) { statusFlow.value = ConnectionStatus.NoToken; return@launch }
            val runner = TelegramBotRunner(apiFactory(token), pipeline, settings, onTeach, diagnostics, fileIds)
            val mirror = launch { runner.status.collect { statusFlow.value = it } }
            try { runner.run() } finally { mirror.cancel() }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        statusFlow.value = ConnectionStatus.Disconnected
    }

    private suspend fun check(api: TelegramBotApi): TokenCheck = try {
        TokenCheck.Ok(api.getMe().username)
    } catch (e: TelegramApiException) {
        TokenCheck.Invalid(if (e.code == 401 || e.code == 404) "Telegram отклонил токен" else "Ошибка Telegram ${e.code}")
    } catch (e: TelegramNetworkException) {
        TokenCheck.Invalid("Нет связи с Telegram (${e.kind})")
    }

    private companion object {
        val TOKEN_SHAPE = Regex("^\\d{5,15}:[A-Za-z0-9_-]{30,64}$")
    }
}

sealed interface TokenCheck {
    data class Ok(val username: String) : TokenCheck
    data class Invalid(val reason: String) : TokenCheck
}
