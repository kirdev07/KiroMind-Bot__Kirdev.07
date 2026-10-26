package com.kirdev.kiromindbot.feature.answers.data

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import kotlinx.coroutines.flow.StateFlow

interface AnswerRepository {
    val entries: StateFlow<List<AnswerEntry>>
    fun upsert(answer: AnswerEntry)
    fun toggleFavorite(id: Long)
    fun remove(id: Long)
    fun find(id: Long): AnswerEntry?
    /** Replaces the whole base atomically (import, restore). */
    fun replaceAll(entries: List<AnswerEntry>)
}
