package com.kirdev.kiromindbot.feature.protection

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.engine.domain.*
import com.kirdev.kiromindbot.feature.learning.domain.InMemoryUnknownQuestionStore
import com.kirdev.kiromindbot.feature.protection.domain.AntiSpam
import com.kirdev.kiromindbot.feature.protection.domain.InMemoryBlacklist
import com.kirdev.kiromindbot.feature.protection.domain.SpamVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AntiSpamTest {
    private val spam = AntiSpam(maxMessages = 3, windowMillis = 1_000, maxRepeats = 2, repeatWindowMillis = 5_000,
        violationsToBlock = 2, violationWindowMillis = 60_000, autoBlockMinutes = 10)

    @Test fun floodIsDroppedThenWindowResets() {
        repeat(3) { assertEquals(SpamVerdict.Allow, spam.check(1, "m$it", it * 10L, false)) }
        assertTrue(spam.check(1, "m4", 40, false) is SpamVerdict.Drop)
        assertEquals(SpamVerdict.Allow, spam.check(1, "m5", 2_000, false))
    }

    @Test fun repeatsAreDroppedByNormalizedText() {
        assertEquals(SpamVerdict.Allow, spam.check(1, "Привет!", 0, false))
        assertEquals(SpamVerdict.Allow, spam.check(1, "привет", 2_000, false))
        val v = spam.check(1, "ПРИВЕТ", 4_000, false) as SpamVerdict.Drop
        assertTrue(v.reason.contains("повтор"))
    }

    @Test fun usersAreIndependent() {
        repeat(3) { spam.check(1, "m$it", 0, false) }
        assertEquals(SpamVerdict.Allow, spam.check(2, "m", 0, false))
    }

    @Test fun autoBlockOnlyWhenEnabled() {
        repeat(3) { spam.check(1, "a$it", 0, false) }
        assertNull((spam.check(1, "x", 1, false) as SpamVerdict.Drop).autoBlockMinutes)
        assertNull((spam.check(1, "y", 2, false) as SpamVerdict.Drop).autoBlockMinutes)
        repeat(3) { spam.check(2, "a$it", 0, true) }
        assertNull((spam.check(2, "x", 1, true) as SpamVerdict.Drop).autoBlockMinutes)
        assertEquals(10, (spam.check(2, "y", 2, true) as SpamVerdict.Drop).autoBlockMinutes)
    }
}

class BlacklistTest {
    @Test fun temporaryBlockExpires() {
        val list = InMemoryBlacklist()
        list.block(5, "спам", now = 0, untilMillis = 100)
        assertTrue(list.isBlocked(5, 50))
        assertFalse(list.isBlocked(5, 100))
        assertTrue(list.items.value.isEmpty())
    }

    @Test fun permanentUntilUnblocked() {
        val list = InMemoryBlacklist()
        list.block(5, "x", 0)
        assertTrue(list.isBlocked(5, Long.MAX_VALUE - 1))
        assertTrue(list.unblock(5))
        assertFalse(list.unblock(5))
        assertFalse(list.isBlocked(5, 0))
    }
}

class OwnerCommandsTest {
    private var now = 0L
    private var stopped = 0
    private val events = mutableListOf<DiagnosticEvent>()
    private val blacklist = InMemoryBlacklist()
    private val owner = 99L
    private val settings = BehaviorSettings(ownerIds = setOf(owner), antiSpam = true, autoBan = true)
    private val pipeline = MessagePipeline(
        FuzzyMindCore(), { listOf(AnswerEntry(1, "привет", "Привет!")) }, ResponseSelector(Random(1)), ConversationMemory(),
        InMemoryUnknownQuestionStore(), { events += it }, { true },
        dispatcher = Dispatchers.Unconfined, clock = { now },
        blacklist = blacklist, antiSpam = AntiSpam(maxMessages = 3, windowMillis = 10_000, violationsToBlock = 2),
        onStopRequested = { stopped++ }
    )

    private fun msg(text: String, from: Long = 7, replyTo: Long? = null, group: Boolean = false) =
        IncomingMessage(1, if (group) ChatKind.GROUP else ChatKind.PRIVATE, from, text, replyToUserId = replyTo)
    private fun go(m: IncomingMessage, s: BehaviorSettings = settings) = runBlocking { pipeline.process(m, s) }
    private fun replyText(o: Outcome) = (o as Outcome.Reply).reply.text

    @Test fun blockAndUnblockById() {
        assertEquals("Заблокирован: 7", replyText(go(msg("/block 7 флуд", from = owner))))
        assertEquals("флуд", blacklist.items.value.single().reason)
        assertTrue(go(msg("привет")) is Outcome.Ignored)
        assertTrue(replyText(go(msg("/blocked", from = owner))).contains("7 — флуд"))
        assertEquals("Разблокирован: 7", replyText(go(msg("/unblock 7", from = owner))))
        assertEquals("Привет!", replyText(go(msg("привет"))))
    }

    @Test fun blockByReplyingToMessage() {
        replyText(go(msg("/block", from = owner, replyTo = 42)))
        assertTrue(blacklist.isBlocked(42, 0))
    }

    @Test fun ownerCannotBeBlockedAndCommandsNeedOwner() {
        assertEquals("Владельца заблокировать нельзя", replyText(go(msg("/block 99", from = owner))))
        assertTrue(go(msg("/block 5", from = 7)) is Outcome.Ignored)
        assertTrue(go(msg("/stop", from = 7)) is Outcome.Ignored)
        assertFalse(blacklist.isBlocked(5, 0))
        assertEquals(0, stopped)
    }

    @Test fun stopRepliesThenRequestsStop() {
        assertTrue(replyText(go(msg("/stop", from = owner))).startsWith("Бот остановлен"))
        assertEquals(1, stopped)
    }

    @Test fun startGreetsAnyoneHelpListsOwnerCommands() {
        assertTrue(replyText(go(msg("/start"))).startsWith("Привет!"))
        assertTrue(replyText(go(msg("/help", from = owner))).contains("/block"))
        assertTrue(go(msg("/start", group = true), settings.copy(groupsEnabled = true)) is Outcome.Ignored)
    }

    @Test fun antiSpamAutoBlocksButNeverTheOwner() {
        repeat(3) { go(msg("привет $it")) }
        assertTrue(go(msg("ещё")) is Outcome.Ignored)          // 1st violation
        assertFalse(blacklist.isBlocked(7, now))
        assertTrue(go(msg("и ещё")) is Outcome.Ignored)        // 2nd -> auto block
        assertTrue(blacklist.isBlocked(7, now))
        assertNotNull(blacklist.items.value.single().untilMillis)
        // The journal says what happened, never who.
        assertTrue(events.any { it.message == "Антиспам: пользователь заблокирован на 60 мин" })
        assertTrue(events.none { Regex("\\b7\\b").containsMatchIn(it.message) })
        repeat(10) { assertTrue(go(msg("привет", from = owner)) is Outcome.Reply) }
    }

    @Test fun spamFilterCanBeSwitchedOff() {
        repeat(10) { assertTrue(go(msg("привет $it"), settings.copy(antiSpam = false)) !is Outcome.Ignored) }
    }
}
