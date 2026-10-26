package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import org.junit.Assert.*
import org.junit.Test

class FuzzyMindCoreTest {
    private val engine = FuzzyMindCore()
    private val sample = listOf(
        AnswerEntry(1, "Привет", "Здравствуйте!"),
        AnswerEntry(2, "Как дела?", "Отлично!"),
        AnswerEntry(3, "Пришли фотографию кота", "Держи кота"),
        AnswerEntry(4, "Ёлка", "Новогодняя"),
        AnswerEntry(5, "Пустой", "")
    )

    private fun idOf(text: String) = (engine.resolve(text, sample) as? MatchResult.Found)?.entry?.id

    @Test fun exactIgnoresCasePunctuationAndSpaces() {
        val r = engine.resolve("  КАК,  дела!!! ", sample) as MatchResult.Found
        assertEquals(2L, r.entry.id)
        assertEquals("exact", r.strategy)
    }

    @Test fun yoIsSameAsYe() = assertEquals(4L, idOf("елка"))

    @Test fun toleratesTypo() {
        val r = engine.resolve("привте", sample) as MatchResult.Found
        assertEquals(1L, r.entry.id)
        assertEquals("fuzzy", r.strategy)
        assertTrue(r.score < 1.0)
    }

    @Test fun matchesWordForms() = assertEquals(3L, idOf("пришли фотографии котов"))

    @Test fun unrelatedTextIsNotMatched() = assertNull(idOf("погода в Москве"))

    @Test fun shortWordsNeedExactMatch() = assertNull(idOf("да"))

    @Test fun invalidEntriesAreSkipped() = assertNull(idOf("пустой"))

    @Test fun blankAndSymbolsOnlyAreNotMatched() {
        assertTrue(engine.resolve("   ", sample) is MatchResult.NotFound)
        assertTrue(engine.resolve("?!...", sample) is MatchResult.NotFound)
    }

    @Test fun extraWordsLowerScore() {
        val r = engine.resolve("как дела у тебя сегодня утром", sample)
        assertTrue(r is MatchResult.NotFound || (r as MatchResult.Found).score < 0.9)
    }

    @Test fun stemmerKeepsShortRoots() {
        assertEquals("кот", TextNormalizer.stem("кот"))
        assertEquals("кот", TextNormalizer.stem("кота"))
        assertEquals("фотографи", TextNormalizer.stem("фотографию"))
    }
}
