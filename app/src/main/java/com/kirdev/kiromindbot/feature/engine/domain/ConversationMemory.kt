/*
 * Copyright 2026 KiroMind Bot contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.kirdev.kiromindbot.feature.engine.domain

/** One person in one chat. Groups get a separate history for every participant. */
data class ConversationKey(val chatId: Long, val userId: Long)

/** Stored turns hold only normalized text and ids, never raw messages or media. */
data class Turn(
    val fromBot: Boolean,
    val normalizedText: String,
    val entryId: Long? = null,
    val variantId: String? = null,
    val atMillis: Long
)

/**
 * Short-term, in-memory dialogue memory. Thread-safe.
 * A conversation keeps at most [maxTurns] turns and is forgotten after [ttlMillis] of silence;
 * the least recently used conversations are evicted beyond [maxConversations].
 */
class ConversationMemory(
    private val maxTurns: Int = 10,
    private val ttlMillis: Long = 30 * 60_000L,
    private val maxConversations: Int = 2_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val conversations = object : LinkedHashMap<ConversationKey, ArrayDeque<Turn>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConversationKey, ArrayDeque<Turn>>) = size > maxConversations
    }

    @Synchronized
    fun recent(key: ConversationKey): List<Turn> {
        val turns = conversations[key] ?: return emptyList()
        if (clock() - (turns.lastOrNull()?.atMillis ?: 0L) > ttlMillis) {
            conversations.remove(key)
            return emptyList()
        }
        return turns.toList()
    }

    fun lastBotTurn(key: ConversationKey): Turn? = recent(key).lastOrNull { it.fromBot }

    @Synchronized
    fun remember(key: ConversationKey, turn: Turn) {
        recent(key) // drops an expired history first
        val turns = conversations.getOrPut(key) { ArrayDeque() }
        turns.addLast(turn)
        while (turns.size > maxTurns) turns.removeFirst()
    }

    @Synchronized fun forget(key: ConversationKey) { conversations.remove(key) }

    @Synchronized fun clear() = conversations.clear()

    @Synchronized fun size(): Int = conversations.size
}
