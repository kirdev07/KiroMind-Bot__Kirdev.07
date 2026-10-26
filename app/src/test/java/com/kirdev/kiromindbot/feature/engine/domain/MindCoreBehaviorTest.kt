package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import org.junit.Assert.*
import org.junit.Test

/** Spec section 1 and 3: understanding, ambiguity guard, follow-ups, scale. */
class MindCoreBehaviorTest {
    private val engine = FuzzyMindCore()

    private fun found(text: String, base: List<AnswerEntry>, ctx: MatchContext = MatchContext.NONE) =
        engine.resolve(text, base, ctx) as? MatchResult.Found

    @Test fun stretchedLettersAndSwapTypoMatch() {
        val base = listOf(AnswerEntry(1, "привет", "Привет!"))
        assertEquals(1L, found("привееет", base)?.entry?.id)
        assertEquals(1L, found("привте", base)?.entry?.id)
        assertEquals(1L, found("ПРИВЕТ!!!", base)?.entry?.id)
    }

    @Test fun wordOrderDoesNotMatter() {
        val base = listOf(AnswerEntry(1, "покажи мне кота", "Кот"))
        assertEquals(1L, found("кота мне покажи", base)?.entry?.id)
    }

    @Test fun englishWorks() {
        val base = listOf(AnswerEntry(1, "how are you", "Fine"))
        assertEquals(1L, found("how are yuo", base)?.entry?.id)
    }

    @Test fun shortWordsDoNotCollide() {
        val base = listOf(AnswerEntry(1, "да", "Ага"), AnswerEntry(2, "не", "Нет так нет"))
        assertNull(found("до", base))
        assertEquals(2L, found("не", base)?.entry?.id)
    }

    @Test fun ambiguousNearTieIsNotFound() {
        val base = listOf(AnswerEntry(1, "купить кота", "А"), AnswerEntry(2, "купить кита", "Б"))
        // "кат" is one edit away from both "кот" and "кит".
        val r = engine.resolve("купить кат", base)
        assertTrue(r is MatchResult.NotFound)
        assertTrue((r as MatchResult.NotFound).reason.startsWith("неоднозначно"))
    }

    @Test fun duplicateQuestionsAreNotAmbiguous() {
        val base = listOf(AnswerEntry(1, "как дела", "А"), AnswerEntry(2, "как дела", "Б"))
        assertEquals(1L, found("как делла", base)?.entry?.id)
    }

    @Test fun diagnosticsExplainChoice() {
        val r = found("привте", listOf(AnswerEntry(7, "привет", "Привет!")))!!
        assertEquals("fuzzy", r.strategy)
        assertTrue(r.score in 0.72..0.99)
        assertTrue(r.reason.contains("привет"))
    }

    @Test fun followUpOnlyInsideItsContext() {
        val base = listOf(
            AnswerEntry(6, "как дела", "Отлично! А у тебя?"),
            AnswerEntry(7, "тоже хорошо", "Рад слышать!", afterEntryId = 6),
            AnswerEntry(9, "тоже хорошо", "Общий ответ")
        )
        assertEquals(9L, found("тоже хорошо", base)?.entry?.id)
        val inContext = found("тоже хорошо", base, MatchContext(6))!!
        assertEquals(7L, inContext.entry.id)
        assertEquals("context-exact", inContext.strategy)
    }

    @Test fun followUpWithoutContextIsHidden() {
        val base = listOf(AnswerEntry(7, "тоже хорошо", "Рад слышать!", afterEntryId = 6))
        assertNull(found("тоже хорошо", base))
        assertNull(found("тоже хорошо", base, MatchContext(1)))
    }

    @Test fun damagedEntriesAreSkippedByIndex() {
        val base = listOf(AnswerEntry(1, "", "x"), AnswerEntry(2, "!!!", "y"), AnswerEntry(3, "ок", "z"))
        val index = AnswerIndex.build(base)
        assertEquals(1, index.items.size)
        assertEquals(2, index.skipped)
    }

    @Test fun largeBaseIsFastAndCorrect() {
        val words = listOf("кот", "собака", "погода", "машина", "музыка", "работа", "город", "книга", "фильм", "игра",
            "telegram", "робот", "поезд", "море", "лес", "чай", "кофе", "школа", "друг", "время")
        val base = (1..20_000L).map { i ->
            val a = words[(i % words.size).toInt()]
            val b = words[((i / words.size) % words.size).toInt()]
            AnswerEntry(i, "$a $b номер$i", "ответ $i")
        }
        val query = base[12344].question.replace("номер", "номр")
        engine.resolve("прогрев", base) // builds the index once
        val started = System.nanoTime()
        repeat(50) { engine.resolve(query, base) }
        val r = engine.resolve(query, base)
        val perQueryMs = (System.nanoTime() - started) / 51 / 1_000_000.0
        println("MindCore 20k: $perQueryMs ms/query")
        assertEquals(12345L, (r as MatchResult.Found).entry.id)
        assertTrue("query took $perQueryMs ms", perQueryMs < 150)
    }
}
