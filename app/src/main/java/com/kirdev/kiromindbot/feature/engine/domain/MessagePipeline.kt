/*
 * Copyright 2026 KiroMind Bot contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.learning.domain.UnknownQuestionStore
import com.kirdev.kiromindbot.feature.protection.domain.AntiSpam
import com.kirdev.kiromindbot.feature.protection.domain.Blacklist
import com.kirdev.kiromindbot.feature.protection.domain.SpamVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class ChatKind { PRIVATE, GROUP }

/** Transport-neutral incoming message; the Telegram module maps updates into this. */
data class IncomingMessage(
    val chatId: Long,
    val chatKind: ChatKind,
    val senderId: Long,
    val text: String,
    val senderIsBot: Boolean = false,
    val fromSelf: Boolean = false,
    val mentionsBot: Boolean = false,
    val replyToBot: Boolean = false,
    /** Author of the message this one replies to; lets the owner /block by replying. */
    val replyToUserId: Long? = null
)

/** Behaviour switches. Defaults are the safe ones. */
data class BehaviorSettings(
    val contextEnabled: Boolean = true,
    val persona: Persona = Persona.NEUTRAL,
    val groupsEnabled: Boolean = false,
    val groupReplyToAll: Boolean = false,
    val groupPrefix: String = "Бот,",
    val botUsername: String = "",
    val logUnknown: Boolean = true,
    val ownerIds: Set<Long> = emptySet(),
    val antiSpam: Boolean = true,
    val autoBan: Boolean = false,
    val externalAiEnabled: Boolean = false,
    val shareContextWithAi: Boolean = false,
    val aiTimeoutMillis: Long = 25_000,
    val aiDailyLimit: Int = 50
)

/** Ready-to-send result; the Telegram module uploads [media] in the given order. */
data class BotReply(
    val text: String,
    val media: List<AnswerMedia>,
    val entryId: Long?,
    val variantId: String?,
    val strategy: String,
    val score: Double,
    val reason: String,
    /** True for external AI text: it is not verified knowledge and is never saved automatically. */
    val unverified: Boolean = false
)

sealed interface Outcome {
    data class Reply(val reply: BotReply) : Outcome
    data class NoAnswer(val reason: String, val recorded: Boolean) : Outcome
    data class Ignored(val reason: String) : Outcome
    /** An authorized owner taught a new pair; the caller saves it to the answer repository. */
    data class Teach(val question: String, val answer: String) : Outcome
    data object Stopped : Outcome
}

/** Diagnostics never carry message text, tokens or user ids. */
data class DiagnosticEvent(val level: Level, val message: String) {
    enum class Level { INFO, SUCCESS, WARNING, ERROR }
}

fun interface DiagnosticSink { fun emit(event: DiagnosticEvent) }

/**
 * Runs one message through the MindCore steps:
 * validate -> commands/chat rules -> context -> match -> variant -> learning -> optional AI -> diagnostics.
 * Work happens on [dispatcher], never on the Android main thread; cancellation stops it between steps.
 */
class MessagePipeline(
    private val engine: MindCore,
    private val answers: () -> List<AnswerEntry>,
    private val selector: ResponseSelector,
    private val memory: ConversationMemory,
    private val unknown: UnknownQuestionStore,
    private val diagnostics: DiagnosticSink,
    private val isRunning: () -> Boolean,
    private val externalAi: ExternalAiProvider? = null,
    private val aiLimiter: RateLimiter = RateLimiter(perMinute = 20),
    private val aiQuota: DailyQuota = DailyQuota(),
    private val blacklist: Blacklist? = null,
    private val antiSpam: AntiSpam? = null,
    /** Called after the owner sent /stop; the reply is still delivered first. */
    private val onStopRequested: () -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun process(message: IncomingMessage, settings: BehaviorSettings): Outcome = withContext(dispatcher) {
        try {
            run(message, settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.ERROR, "Ошибка обработки: ${e::class.simpleName}"))
            Outcome.Ignored("внутренняя ошибка")
        }
    }

    private suspend fun run(message: IncomingMessage, settings: BehaviorSettings): Outcome {
        // 1. Admissibility.
        if (!isRunning()) return Outcome.Stopped
        if (message.fromSelf) return Outcome.Ignored("собственное сообщение")
        if (message.senderIsBot) return ignored("сообщение от бота — защита от циклов")
        val isOwner = message.senderId in settings.ownerIds
        // Silent: logging every message of a blocked user would flood the journal.
        if (!isOwner && blacklist?.isBlocked(message.senderId, clock()) == true) return Outcome.Ignored("в чёрном списке")
        var text = message.text.trim()
        if (text.isEmpty()) return Outcome.Ignored("пустое сообщение")

        // 2. Commands and chat rules.
        var addressed = message.chatKind == ChatKind.PRIVATE || message.mentionsBot || message.replyToBot
        if (text.startsWith("/")) {
            val command = text.substringBefore(' ').substringBefore('@').lowercase()
            val argument = text.substringAfter(' ', "").trim()
            when (command) {
                "/teach" -> return teach(message, settings, argument)
                "/ask" -> { text = argument; addressed = true }
                "/start", "/help" -> if (addressed) return commandReply(if (isOwner) OWNER_HELP else GREETING) else return Outcome.Ignored("команда не боту")
                "/block", "/unblock", "/blocked", "/stop" ->
                    return if (isOwner) ownerCommand(command, argument, message, settings) else ignored("команда владельца от неавторизованного пользователя")
                else -> return Outcome.Ignored("неизвестная команда")
            }
        }
        if (message.chatKind == ChatKind.GROUP) {
            if (!settings.groupsEnabled) return Outcome.Ignored("группы выключены")
            stripAddress(text, settings)?.let { text = it; addressed = true }
            if (!addressed && !settings.groupReplyToAll) return Outcome.Ignored("к боту не обращались")
        }
        if (TextNormalizer.normalize(text).isEmpty()) return Outcome.Ignored("нет текста")
        if (settings.antiSpam && !isOwner && antiSpam != null) {
            val verdict = antiSpam.check(message.senderId, text, clock(), settings.autoBan)
            if (verdict is SpamVerdict.Drop) {
                verdict.autoBlockMinutes?.let { minutes ->
                    blacklist?.block(message.senderId, "антиспам: ${verdict.reason}", clock(), clock() + minutes * 60_000L)
                    diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "Антиспам: пользователь заблокирован на $minutes мин"))
                } ?: diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "Антиспам: сообщение пропущено (${verdict.reason})"))
                return Outcome.Ignored("антиспам: ${verdict.reason}")
            }
        }
        coroutineCheck()

        // 3. Context.
        val key = ConversationKey(message.chatId, message.senderId)
        val lastBot = if (settings.contextEnabled) memory.lastBotTurn(key) else null
        val now = clock()

        // 4–5. Exact, then fuzzy match.
        val result = engine.resolve(text, answers(), MatchContext(lastBot?.entryId))
        coroutineCheck()
        if (!isRunning()) return Outcome.Stopped
        // Turns are kept even with context off: anti-repeat needs them, matching just ignores them.
        memory.remember(key, Turn(false, TextNormalizer.normalize(text), atMillis = now))

        // 6. Variant.
        if (result is MatchResult.Found) {
            val variant = selector.select(result.entry, settings.persona, memory.recent(key).lastOrNull { it.fromBot && it.entryId == result.entry.id }?.variantId)
            if (variant != null) {
                memory.remember(key, Turn(true, "", result.entry.id, variant.id, now))
                diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.SUCCESS,
                    "Ответ #${result.entry.id}: ${result.strategy} ${(result.score * 100).toInt()}%"))
                return Outcome.Reply(BotReply(variant.text, variant.media.sortedBy { it.order }, result.entry.id, variant.id,
                    result.strategy, result.score, result.reason))
            }
            diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.ERROR, "Запись #${result.entry.id} без пригодных вариантов"))
        }
        val reason = (result as? MatchResult.NotFound)?.reason ?: "нет пригодного варианта"

        // 7. Learning queue.
        val recorded = settings.logUnknown && unknown.record(text, now)

        // 8. Optional external AI.
        if (settings.externalAiEnabled && externalAi != null) {
            askAi(text, key, settings)?.let { return Outcome.Reply(it) }
        }

        // 10. Diagnostics.
        diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.WARNING,
            if (recorded) "Нет ответа, вопрос добавлен в «Без ответа»" else "Нет ответа"))
        return Outcome.NoAnswer(reason, recorded)
    }

    private fun teach(message: IncomingMessage, settings: BehaviorSettings, argument: String): Outcome {
        if (message.senderId !in settings.ownerIds) return ignored("команда обучения от неавторизованного пользователя")
        val question = argument.substringBefore('=').trim()
        val answer = argument.substringAfter('=', "").trim()
        if (question.isEmpty() || answer.isEmpty()) return Outcome.Ignored("формат: /teach вопрос = ответ")
        diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.INFO, "Владелец добавил ответ"))
        return Outcome.Teach(question, answer)
    }

    private fun ownerCommand(command: String, argument: String, message: IncomingMessage, settings: BehaviorSettings): Outcome {
        val list = blacklist ?: return commandReply("Чёрный список недоступен")
        // Target: an explicit id, or the author of the replied-to message.
        val target = argument.substringBefore(' ').toLongOrNull() ?: message.replyToUserId
        val reason = (if (argument.substringBefore(' ').toLongOrNull() != null) argument.substringAfter(' ', "") else argument).trim()
        return when (command) {
            "/block" -> when {
                target == null -> commandReply("Укажите ID: /block 123456 причина — или ответьте /block на сообщение")
                target in settings.ownerIds -> commandReply("Владельца заблокировать нельзя")
                else -> {
                    list.block(target, reason.ifEmpty { "владелец" }, clock())
                    antiSpam?.forget(target)
                    diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.INFO, "Владелец заблокировал пользователя"))
                    commandReply("Заблокирован: $target")
                }
            }
            "/unblock" -> when {
                target == null -> commandReply("Укажите ID: /unblock 123456")
                list.unblock(target) -> {
                    diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.INFO, "Владелец разблокировал пользователя"))
                    commandReply("Разблокирован: $target")
                }
                else -> commandReply("$target не в чёрном списке")
            }
            "/blocked" -> {
                val now = clock()
                val active = list.items.value.filter { it.isActive(now) }
                commandReply(if (active.isEmpty()) "Чёрный список пуст" else
                    "Заблокированы (${active.size}):\n" + active.take(20).joinToString("\n") { b ->
                        "${b.userId} — ${b.reason}" + (b.untilMillis?.let { " (ещё ${((it - now) / 60_000).coerceAtLeast(1)} мин)" } ?: "")
                    })
            }
            "/stop" -> {
                diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "Владелец остановил бота командой"))
                onStopRequested()
                commandReply("Бот остановлен. Включить снова можно кнопкой в приложении.")
            }
            else -> Outcome.Ignored("неизвестная команда")
        }
    }

    private fun commandReply(text: String) = Outcome.Reply(BotReply(text, emptyList(), null, null, "command", 1.0, ""))

    /** Returns text without the bot prefix or @mention, or null when neither is present. */
    private fun stripAddress(text: String, settings: BehaviorSettings): String? {
        val handles = listOfNotNull(settings.groupPrefix.takeIf { it.isNotBlank() },
            settings.botUsername.takeIf { it.isNotBlank() }?.let { "@" + it.removePrefix("@") })
        val hit = handles.firstOrNull { text.startsWith(it, ignoreCase = true) } ?: return null
        return text.substring(hit.length).trimStart(',', ' ', ':')
    }

    private suspend fun askAi(text: String, key: ConversationKey, settings: BehaviorSettings): BotReply? {
        val provider = externalAi ?: return null
        if (!aiLimiter.tryAcquire()) {
            diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "Лимит запросов к ${provider.name} в минуту"))
            return null
        }
        if (!aiQuota.tryAcquire(settings.aiDailyLimit)) {
            diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "Дневной лимит ${provider.name} исчерпан (${settings.aiDailyLimit})"))
            return null
        }
        val history = if (settings.shareContextWithAi) memory.recent(key).filter { !it.fromBot }.map { it.normalizedText } else emptyList()
        return try {
            val answer = withTimeout(settings.aiTimeoutMillis) { provider.complete(AiRequest(text, history)) }.trim()
            if (answer.isEmpty()) {
                diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "${provider.name}: пустой ответ"))
                null
            } else BotReply(answer, emptyList(), null, null, "external-ai", 0.0, provider.name, unverified = true).also {
                diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.SUCCESS, "Ответ ${provider.name} (не проверен)"))
            }
        } catch (e: TimeoutCancellationException) {
            diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "${provider.name}: таймаут"))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiFailure) {
            diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "${provider.name}: ${e.reason}"))
            null
        } catch (e: Exception) {
            diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "${provider.name} недоступен"))
            null
        }
    }

    private fun ignored(reason: String): Outcome {
        diagnostics.emit(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "Пропущено: $reason"))
        return Outcome.Ignored(reason)
    }

    private companion object {
        const val GREETING = "Привет! Я KiroMind Bot. Просто напишите сообщение."
        const val OWNER_HELP = "Команды владельца:\n" +
            "/teach вопрос = ответ — добавить ответ\n" +
            "/block ID [причина] — заблокировать (или ответьте /block на сообщение)\n" +
            "/unblock ID — разблокировать\n" +
            "/blocked — чёрный список\n" +
            "/stop — остановить бота"
    }

    private suspend fun coroutineCheck() = kotlin.coroutines.coroutineContext.ensureActive()
}
