package com.kirdev.kiromindbot.feature.telegram.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.engine.domain.BehaviorSettings
import com.kirdev.kiromindbot.feature.engine.domain.DiagnosticEvent
import com.kirdev.kiromindbot.feature.engine.domain.DiagnosticSink
import com.kirdev.kiromindbot.feature.engine.domain.MessagePipeline
import com.kirdev.kiromindbot.feature.engine.domain.Outcome
import com.kirdev.kiromindbot.feature.telegram.api.BotIdentity
import com.kirdev.kiromindbot.feature.telegram.api.ConnectionStatus
import com.kirdev.kiromindbot.feature.telegram.api.FileIdStore
import com.kirdev.kiromindbot.feature.telegram.api.MediaFile
import com.kirdev.kiromindbot.feature.telegram.api.MediaUnavailableException
import com.kirdev.kiromindbot.feature.telegram.api.OutgoingAction
import com.kirdev.kiromindbot.feature.telegram.api.TelegramApiException
import com.kirdev.kiromindbot.feature.telegram.api.TelegramBotApi
import com.kirdev.kiromindbot.feature.telegram.api.TelegramNetworkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Long-polling loop: getUpdates -> MessagePipeline -> send. Runs until cancelled.
 *
 * - Updates queued while the bot was off are skipped on start, so it never answers stale messages.
 * - 401/404 (bad token) and 409 (webhook or second poller) stop the loop with an error status.
 * - 429 waits retry_after; network errors back off 2s..60s and keep going.
 * - Messages to one chat are spaced by [perChatIntervalMillis] to stay within Telegram limits.
 * Diagnostics never include message text, chat ids or the token.
 */
class TelegramBotRunner(
    private val api: TelegramBotApi,
    private val pipeline: MessagePipeline,
    private val settings: () -> BehaviorSettings,
    private val onTeach: (question: String, answer: String) -> AnswerEntry?,
    private val diagnostics: DiagnosticSink,
    private val fileIds: FileIdStore? = null,
    private val pollTimeoutSeconds: Int = 25,
    private val perChatIntervalMillis: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val statusFlow = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connecting)
    val status: StateFlow<ConnectionStatus> = statusFlow
    private val lastSentAt = HashMap<Long, Long>()

    suspend fun run() {
        var backoff = 2_000L
        var bot: BotIdentity? = null
        var offset: Long? = null
        while (coroutineContext.isActive) {
            try {
                if (bot == null) {
                    statusFlow.value = ConnectionStatus.Connecting
                    bot = api.getMe()
                    offset = api.getUpdates(-1, 0).maxOfOrNull { it.updateId }?.plus(1)
                    statusFlow.value = ConnectionStatus.Online(bot.username)
                    emit(DiagnosticEvent.Level.SUCCESS, "Telegram: подключён как @${bot.username}")
                }
                val updates = api.getUpdates(offset, pollTimeoutSeconds)
                if (statusFlow.value !is ConnectionStatus.Online) statusFlow.value = ConnectionStatus.Online(bot.username)
                backoff = 2_000L
                for (update in updates) {
                    offset = update.updateId + 1
                    val message = update.message ?: continue
                    val incoming = message.toIncoming(bot) ?: continue
                    handle(incoming.chatId, message.messageId, pipeline.process(incoming, settings().copy(botUsername = bot.username)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramApiException) {
                when (e.code) {
                    401, 404 -> return fail("Неверный токен — проверьте его в настройках")
                    409 -> return fail("Конфликт: у бота включён webhook или запущен другой экземпляр")
                    429 -> {
                        val wait = (e.retryAfterSeconds ?: 5) * 1000L
                        emit(DiagnosticEvent.Level.WARNING, "Telegram: лимит запросов, пауза ${wait / 1000} с")
                        delay(wait)
                    }
                    else -> backoff = retry("ошибка Telegram ${e.code}", backoff)
                }
            } catch (e: TelegramNetworkException) {
                backoff = retry("нет связи (${e.kind})", backoff)
            }
        }
    }

    private suspend fun handle(chatId: Long, messageId: Long, outcome: Outcome) {
        when (outcome) {
            is Outcome.Reply -> {
                val plan = planReply(chatId, messageId, outcome.reply)
                if (plan.skippedMedia > 0) emit(DiagnosticEvent.Level.WARNING, "Пропущено демо-вложений: ${plan.skippedMedia}")
                plan.actions.forEach { send(it) }
            }
            is Outcome.Teach -> {
                val saved = onTeach(outcome.question, outcome.answer)
                send(OutgoingAction.Text(chatId, if (saved != null) "Запомнил (#${saved.id})." else "Не удалось сохранить ответ.", messageId))
            }
            is Outcome.NoAnswer, is Outcome.Ignored, Outcome.Stopped -> Unit
        }
    }

    private suspend fun send(action: OutgoingAction) {
        lastSentAt[action.chatId]?.let { last ->
            val wait = last + perChatIntervalMillis - clock()
            if (wait > 0) delay(wait)
        }
        try {
            sendWithCache(action)
        } catch (e: TelegramApiException) {
            if (e.code == 429) {
                delay((e.retryAfterSeconds ?: 5) * 1000L)
                sendWithCache(action)
            } else {
                // A single bad message (blocked by user, bad file id) must not stop the bot.
                emit(DiagnosticEvent.Level.WARNING, "Telegram не принял ответ: ${e.code}")
            }
        } catch (e: MediaUnavailableException) {
            emit(DiagnosticEvent.Level.WARNING, "Вложение не отправлено: ${e.reason}")
        } finally {
            lastSentAt[action.chatId] = clock()
        }
    }

    /**
     * A local file is uploaded once; afterwards its cached file_id is sent instead.
     * If Telegram rejects a cached id (400), the entry is dropped and the file uploaded again.
     */
    private suspend fun sendWithCache(action: OutgoingAction) {
        val local = (action as? OutgoingAction.Media)?.file as? MediaFile.Local
        if (action !is OutgoingAction.Media || local == null || fileIds == null) {
            api.send(action)
            return
        }
        fileIds.get(local.uri, action.method)?.let { cached ->
            try {
                api.send(action.copy(file = MediaFile.Remote(cached)))
                return
            } catch (e: TelegramApiException) {
                if (e.code != 400) throw e
                fileIds.remove(local.uri, action.method)
            }
        }
        api.send(action)?.let { fileIds.put(local.uri, action.method, it) }
    }

    private suspend fun retry(reason: String, backoff: Long): Long {
        statusFlow.value = ConnectionStatus.Reconnecting(reason)
        emit(DiagnosticEvent.Level.WARNING, "Telegram: $reason, повтор через ${backoff / 1000} с")
        delay(backoff)
        return min(backoff * 2, 60_000L)
    }

    private fun fail(summary: String) {
        statusFlow.value = ConnectionStatus.Error(summary)
        emit(DiagnosticEvent.Level.ERROR, "Telegram: $summary")
    }

    private fun emit(level: DiagnosticEvent.Level, message: String) = diagnostics.emit(DiagnosticEvent(level, message))
}
