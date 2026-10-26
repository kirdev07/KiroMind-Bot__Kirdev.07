package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.learning.domain.InMemoryUnknownQuestionStore
import org.junit.Assert.*
import org.junit.Test

class ConversationMemoryTest {
    private var now = 0L
    private val memory = ConversationMemory(maxTurns = 3, ttlMillis = 1_000, maxConversations = 2, clock = { now })
    private val alice = ConversationKey(1, 10)
    private val bob = ConversationKey(1, 20)

    @Test fun chatsAndUsersAreSeparate() {
        memory.remember(alice, Turn(true, "", 6, "6:0", now))
        assertEquals(6L, memory.lastBotTurn(alice)?.entryId)
        assertNull(memory.lastBotTurn(bob))
        assertNull(memory.lastBotTurn(ConversationKey(2, 10)))
    }

    @Test fun keepsOnlyLastTurns() {
        (1..5L).forEach { memory.remember(alice, Turn(false, "t$it", atMillis = now)) }
        assertEquals(listOf("t3", "t4", "t5"), memory.recent(alice).map { it.normalizedText })
    }

    @Test fun expiresAfterSilence() {
        memory.remember(alice, Turn(true, "", 6, null, now))
        now = 1_001
        assertTrue(memory.recent(alice).isEmpty())
    }

    @Test fun evictsLeastRecentConversation() {
        memory.remember(alice, Turn(false, "a", atMillis = now))
        memory.remember(bob, Turn(false, "b", atMillis = now))
        memory.remember(ConversationKey(3, 30), Turn(false, "c", atMillis = now))
        assertEquals(2, memory.size())
        assertTrue(memory.recent(alice).isEmpty())
    }
}

class UnknownQuestionStoreTest {
    @Test fun mergesByNormalizedText() {
        val store = InMemoryUnknownQuestionStore()
        assertTrue(store.record("Где купить слона?", 1))
        assertTrue(store.record("где  купить СЛОНА", 5))
        assertFalse(store.record("?!", 6))
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals(2, all[0].count)
        assertEquals(1L, all[0].firstSeenMillis)
        assertEquals(5L, all[0].lastSeenMillis)
    }

    @Test fun capacityDropsOldest() {
        val store = InMemoryUnknownQuestionStore(capacity = 2)
        store.record("один", 1); store.record("два", 2); store.record("три", 3)
        assertEquals(listOf("три", "два"), store.all().map { it.key })
    }
}
