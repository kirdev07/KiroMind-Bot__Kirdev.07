/*
 * Copyright 2026 KiroMind Bot contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.kirdev.kiromindbot.feature.learning.domain

import com.kirdev.kiromindbot.feature.engine.domain.TextNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A question MindCore could not answer, merged by normalized text. */
data class UnknownQuestion(
    val key: String,
    val sample: String,
    val count: Int,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long
)

/** The "Без ответа" queue. Only the owner turns an item into a real answer. */
interface UnknownQuestionStore {
    /** Newest first; observed by the "Без ответа" screen. */
    val items: StateFlow<List<UnknownQuestion>>
    /** Returns false when the text is empty after normalization. */
    fun record(text: String, atMillis: Long): Boolean
    fun all(): List<UnknownQuestion>
    fun dismiss(key: String)
}

enum class UnknownSort { RECENT, FREQUENT }

/** What the "Без ответа" screen shows: a normalized-text search, then the chosen order. */
object UnknownQueueView {
    fun of(items: List<UnknownQuestion>, query: String, sort: UnknownSort): List<UnknownQuestion> {
        val q = TextNormalizer.normalize(query)
        val hits = if (q.isEmpty()) items else items.filter { it.key.contains(q) }
        return when (sort) {
            UnknownSort.RECENT -> hits.sortedByDescending { it.lastSeenMillis }
            UnknownSort.FREQUENT -> hits.sortedWith(compareByDescending<UnknownQuestion> { it.count }.thenByDescending { it.lastSeenMillis })
        }
    }
}

/** In-memory store until the Room repository lands; keeps the [capacity] most recent questions. */
class InMemoryUnknownQuestionStore(private val capacity: Int = 500) : UnknownQuestionStore {
    private val byKey = LinkedHashMap<String, UnknownQuestion>()
    private val state = MutableStateFlow<List<UnknownQuestion>>(emptyList())
    override val items: StateFlow<List<UnknownQuestion>> = state

    private fun publish() { state.value = byKey.values.sortedByDescending { it.lastSeenMillis } }

    @Synchronized
    override fun record(text: String, atMillis: Long): Boolean {
        val key = TextNormalizer.normalize(text)
        if (key.isEmpty()) return false
        val old = byKey.remove(key)
        byKey[key] = old?.copy(count = old.count + 1, lastSeenMillis = atMillis)
            ?: UnknownQuestion(key, text.trim().take(300), 1, atMillis, atMillis)
        while (byKey.size > capacity) byKey.remove(byKey.keys.first())
        publish()
        return true
    }

    override fun all(): List<UnknownQuestion> = state.value

    @Synchronized override fun dismiss(key: String) { if (byKey.remove(key) != null) publish() }
}
