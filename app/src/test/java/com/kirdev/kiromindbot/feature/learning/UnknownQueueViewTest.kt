package com.kirdev.kiromindbot.feature.learning

import com.kirdev.kiromindbot.feature.learning.domain.UnknownQuestion
import com.kirdev.kiromindbot.feature.learning.domain.UnknownQueueView
import com.kirdev.kiromindbot.feature.learning.domain.UnknownSort
import org.junit.Assert.assertEquals
import org.junit.Test

class UnknownQueueViewTest {
    private val items = listOf(
        UnknownQuestion("сколько стоит", "Сколько стоит?", 5, 0, 100),
        UnknownQuestion("где ты живешь", "Где ты живёшь", 1, 0, 300),
        UnknownQuestion("что стоит посмотреть", "Что стоит посмотреть", 5, 0, 200)
    )

    @Test fun recentIsNewestFirst() =
        assertEquals(listOf("где ты живешь", "что стоит посмотреть", "сколько стоит"), UnknownQueueView.of(items, "", UnknownSort.RECENT).map { it.key })

    @Test fun frequentBreaksTiesByRecency() =
        assertEquals(listOf("что стоит посмотреть", "сколько стоит", "где ты живешь"), UnknownQueueView.of(items, "", UnknownSort.FREQUENT).map { it.key })

    @Test fun searchIgnoresCaseYoAndPunctuation() {
        assertEquals(listOf("где ты живешь"), UnknownQueueView.of(items, "ЖИВЁШЬ?", UnknownSort.RECENT).map { it.key })
        assertEquals(2, UnknownQueueView.of(items, "стоит", UnknownSort.RECENT).size)
    }
}
