package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import com.kirdev.kiromindbot.feature.answers.domain.ReplyVariant
import com.kirdev.kiromindbot.feature.learning.domain.InMemoryUnknownQuestionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MessagePipelineTest {
    private var running = true
    private val events = mutableListOf<DiagnosticEvent>()
    private val unknown = InMemoryUnknownQuestionStore()
    private var base = listOf(
        AnswerEntry(1, "Привет", "Привет!", alternatives = listOf(ReplyVariant("1:1", "Здравствуй!"))),
        AnswerEntry(6, "Как дела?", "Отлично! А у тебя?"),
        AnswerEntry(7, "Тоже хорошо", "Рад слышать!", afterEntryId = 6),
        AnswerEntry(8, "Кот", "", media = listOf(
            AnswerMedia("b", MediaKind.VOICE, "мяу.ogg", "tg-file:b", order = 2),
            AnswerMedia("a", MediaKind.PHOTO, "кот.jpg", "tg-file:a", order = 1)
        ))
    )

    private fun pipeline(ai: ExternalAiProvider? = null) = MessagePipeline(
        engine = FuzzyMindCore(),
        answers = { base },
        selector = ResponseSelector(Random(1)),
        memory = ConversationMemory(),
        unknown = unknown,
        diagnostics = { events += it },
        isRunning = { running },
        externalAi = ai,
        dispatcher = Dispatchers.Unconfined
    )

    private val p = pipeline()
    private val settings = BehaviorSettings(ownerIds = setOf(99))
    private fun pm(text: String, user: Long = 10, chat: Long = 1) = IncomingMessage(chat, ChatKind.PRIVATE, user, text)
    private fun gm(text: String, mention: Boolean = false) = IncomingMessage(-5, ChatKind.GROUP, 10, text, mentionsBot = mention)
    private fun go(m: IncomingMessage, s: BehaviorSettings = settings, pipe: MessagePipeline = p) = runBlocking { pipe.process(m, s) }
    private fun replyOf(o: Outcome) = (o as Outcome.Reply).reply

    @Test fun answersPrivateMessage() {
        val r = replyOf(go(pm("привте")))
        assertEquals(1L, r.entryId)
        assertEquals("fuzzy", r.strategy)
    }

    @Test fun contextContinuesDialogue() {
        assertEquals(6L, replyOf(go(pm("как дела"))).entryId)
        val r = replyOf(go(pm("тоже хорошо")))
        assertEquals(7L, r.entryId)
        assertEquals("context-exact", r.strategy)
    }

    @Test fun contextIsPerUserAndSwitchable() {
        go(pm("как дела", user = 10))
        assertTrue(go(pm("тоже хорошо", user = 20)) is Outcome.NoAnswer)
        go(pm("как дела", user = 30))
        assertTrue(go(pm("тоже хорошо", user = 30), settings.copy(contextEnabled = false)) is Outcome.NoAnswer)
    }

    @Test fun doesNotRepeatVariantToSameUser() {
        val ids = (1..10).map { replyOf(go(pm("привет"))).variantId }
        ids.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test fun mediaIsOrderedAndTextMayBeEmpty() {
        val r = replyOf(go(pm("кот")))
        assertEquals("", r.text)
        assertEquals(listOf("a", "b"), r.media.map { it.id })
    }

    @Test fun unknownIsRecordedAndMerged() {
        val o = go(pm("где купить слона")) as Outcome.NoAnswer
        assertTrue(o.recorded)
        go(pm("Где купить СЛОНА?!"))
        assertEquals(2, unknown.all().single().count)
        assertFalse((go(pm("что-то новое"), settings.copy(logUnknown = false)) as Outcome.NoAnswer).recorded)
    }

    @Test fun ignoresSelfAndOtherBots() {
        assertTrue(go(pm("привет").copy(fromSelf = true)) is Outcome.Ignored)
        assertTrue(go(pm("привет").copy(senderIsBot = true)) is Outcome.Ignored)
    }

    @Test fun stoppedBotProcessesNothing() {
        running = false
        assertEquals(Outcome.Stopped, go(pm("привет")))
    }

    @Test fun groupRules() {
        val on = settings.copy(groupsEnabled = true, botUsername = "kiromind_bot")
        assertTrue(go(gm("Бот, привет")) is Outcome.Ignored) // groups disabled by default
        assertTrue(go(gm("привет"), on) is Outcome.Ignored)  // not addressed
        assertEquals(1L, replyOf(go(gm("Бот, привет"), on)).entryId)
        assertEquals(1L, replyOf(go(gm("@kiromind_bot привет"), on)).entryId)
        assertEquals(1L, replyOf(go(gm("привет", mention = true), on)).entryId)
        assertEquals(1L, replyOf(go(gm("/ask@kiromind_bot привет"), on)).entryId)
        assertEquals(1L, replyOf(go(gm("привет"), on.copy(groupReplyToAll = true))).entryId)
    }

    @Test fun teachingOnlyForOwners() {
        assertTrue(go(pm("/teach слон = Большой", user = 10)) is Outcome.Ignored)
        assertEquals(Outcome.Teach("слон", "Большой"), go(pm("/teach слон = Большой", user = 99)))
    }

    @Test fun brokenEntryDoesNotCrash() {
        base = listOf(AnswerEntry(1, "Привет", "Привет!")) + listOf(AnswerEntry(2, "Сломано", "   "))
        assertTrue(go(pm("сломано")) is Outcome.NoAnswer)
        base = object : AbstractList<AnswerEntry>() {
            override val size = 1
            override fun get(index: Int): AnswerEntry = error("повреждено")
        }
        assertTrue(go(pm("привет")) is Outcome.Ignored)
        assertEquals(DiagnosticEvent.Level.ERROR, events.last().level)
    }

    @Test fun externalAiOnlyWhenEnabledAndUnverified() {
        val ai = object : ExternalAiProvider {
            var calls = 0
            override val name = "TestAI"
            override suspend fun complete(request: AiRequest): String { calls++; assertTrue(request.history.isEmpty()); return "ИИ-ответ" }
        }
        val pipe = pipeline(ai)
        assertTrue(go(pm("слон"), pipe = pipe) is Outcome.NoAnswer)
        assertEquals(0, ai.calls)
        val r = replyOf(go(pm("слон"), settings.copy(externalAiEnabled = true), pipe))
        assertTrue(r.unverified)
        assertEquals(1, ai.calls)
    }

    @Test fun externalAiFailureFallsBackLocally() {
        val slow = object : ExternalAiProvider {
            override val name = "Slow"
            override suspend fun complete(request: AiRequest): String { delay(10_000); return "поздно" }
        }
        val o = go(pm("слон"), settings.copy(externalAiEnabled = true, aiTimeoutMillis = 50), pipeline(slow))
        assertTrue(o is Outcome.NoAnswer)
        assertTrue(events.any { it.message.contains("таймаут") })
    }

    @Test fun diagnosticsContainNoMessageText() {
        go(pm("секретная фраза 12345"))
        go(pm("привет"))
        assertTrue(events.none { it.message.contains("секрет") || it.message.contains("12345") || it.message.contains("привет") })
    }
}
