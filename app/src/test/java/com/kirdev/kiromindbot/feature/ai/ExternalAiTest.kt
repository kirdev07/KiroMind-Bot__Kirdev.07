package com.kirdev.kiromindbot.feature.ai

import com.anthropic.models.beta.messages.BetaOutputConfig
import com.kirdev.kiromindbot.feature.ai.data.ClaudeProvider
import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.engine.domain.*
import com.kirdev.kiromindbot.feature.learning.domain.InMemoryUnknownQuestionStore
import com.kirdev.kiromindbot.feature.security.SecretRead
import com.kirdev.kiromindbot.feature.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import kotlin.random.Random

class DailyQuotaTest {
    @Test fun limitsPerDayAndResetsAtMidnight() {
        var now = 0L
        val quota = DailyQuota({ now }, ZoneOffset.UTC)
        repeat(3) { assertTrue(quota.tryAcquire(3)) }
        assertFalse(quota.tryAcquire(3))
        assertEquals(3, quota.usedToday())
        now = 24 * 3600_000L // next UTC day
        assertEquals(0, quota.usedToday())
        assertTrue(quota.tryAcquire(3))
    }
}

class ExternalAiPipelineTest {
    private val events = mutableListOf<DiagnosticEvent>()
    private val unknown = InMemoryUnknownQuestionStore()
    private val seen = mutableListOf<AiRequest>()
    private var failWith: String? = null
    private val ai = object : ExternalAiProvider {
        override val name = "Claude"
        override suspend fun complete(request: AiRequest): String {
            seen += request
            failWith?.let { throw AiFailure(it) }
            return "Ответ ИИ"
        }
    }
    private val pipeline = MessagePipeline(
        FuzzyMindCore(), { listOf(AnswerEntry(1, "привет", "Привет!")) }, ResponseSelector(Random(1)), ConversationMemory(),
        unknown, { events += it }, { true }, externalAi = ai, dispatcher = Dispatchers.Unconfined,
        aiQuota = DailyQuota()
    )
    private val on = BehaviorSettings(externalAiEnabled = true, aiDailyLimit = 2)
    private fun go(text: String, s: BehaviorSettings = on) = runBlocking { pipeline.process(IncomingMessage(1, ChatKind.PRIVATE, 7, text), s) }

    @Test fun localAnswerWinsAndAiOnlyForUnknown() {
        assertEquals("Привет!", (go("привет") as Outcome.Reply).reply.text)
        assertTrue(seen.isEmpty())
        val r = (go("сколько звёзд в галактике") as Outcome.Reply).reply
        assertTrue(r.unverified)
        assertEquals("external-ai", r.strategy)
        // Still queued for the owner, never saved as an answer.
        assertEquals(1, unknown.all().size)
    }

    @Test fun offByDefault() {
        assertTrue(go("сколько звёзд", BehaviorSettings()) is Outcome.NoAnswer)
        assertTrue(seen.isEmpty())
    }

    @Test fun dailyLimitStopsCalls() {
        go("вопрос один"); go("вопрос два")
        assertTrue(go("вопрос три") is Outcome.NoAnswer)
        assertEquals(2, seen.size)
        assertTrue(events.any { it.message.startsWith("Дневной лимит Claude") })
    }

    @Test fun contextOnlyWhenShared() {
        go("первое сообщение"); go("второе сообщение")
        assertTrue(seen.all { it.history.isEmpty() })
        seen.clear()
        go("третье сообщение", on.copy(shareContextWithAi = true, aiDailyLimit = 10))
        assertTrue(seen.single().history.isNotEmpty())
    }

    @Test fun failureFallsBackSilentlyWithSafeReason() {
        failWith = "лимит Anthropic, попробуйте позже"
        assertTrue(go("секретный вопрос 555") is Outcome.NoAnswer)
        assertTrue(events.any { it.message == "Claude: лимит Anthropic, попробуйте позже" })
        assertTrue(events.none { it.message.contains("секретный") || it.message.contains("555") })
    }
}

class ClaudeProviderTest {
    private val noKey = object : SecretStore {
        override suspend fun save(name: String, secret: String) = false
        override suspend fun read(name: String) = SecretRead.Missing
        override suspend fun delete(name: String) {}
    }

    @Test fun requestShape() {
        val p = ClaudeProvider(noKey).buildParams(AiRequest("Как дела?", listOf("привет")))
        assertEquals("claude-opus-5-5", p.model().asString())
        assertEquals(2048L, p.maxTokens())
        assertEquals(BetaOutputConfig.Effort.LOW, p.outputConfig().get().effort().get())
        assertTrue(p.betas().get().any { it.asString() == "server-side-fallback-2026-07-01" })
        assertEquals("default", p._additionalBodyProperties()["fallbacks"]?.asStringOrThrow())
        assertTrue(p.messages().single().toString().contains("привет"))
    }

    @Test fun missingKeyFailsClosed() {
        val e = assertThrows(AiFailure::class.java) { runBlocking { ClaudeProvider(noKey).complete(AiRequest("x")) } }
        assertEquals("ключ не задан", e.reason)
    }
}
