package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import org.junit.Assert.*
import org.junit.Test

class ExactMindCoreTest {
    private val engine = ExactMindCore()
    private val sample = listOf(
        AnswerEntry(1, "Привет", "Здравствуйте!"),
        AnswerEntry(2, "  Как   дела?  ", "Отлично!")
    )

    @Test fun ignoresWhitespaceAndCase() {
        val answer = engine.resolve(" КАК  ДЕЛА? ", sample)
        assertTrue(answer is MatchResult.Found)
        assertEquals(2L, (answer as MatchResult.Found).entry.id)
    }

    @Test fun unknownIsNotInvented() {
        assertTrue(engine.resolve("Неизвестное", sample) is MatchResult.NotFound)
    }

    @Test fun blankIsNotMatched() {
        assertTrue(engine.resolve("   ", sample) is MatchResult.NotFound)
    }

    @Test fun mediaOnlyReplyIsValid() {
        assertFalse(AnswerEntry(3, "Фото", "").isValid)
    }
}
