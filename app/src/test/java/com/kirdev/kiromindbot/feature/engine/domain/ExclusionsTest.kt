package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.answers.data.local.assemble
import com.kirdev.kiromindbot.feature.answers.data.local.toRows
import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.backup.domain.BackupFormat
import org.junit.Assert.*
import org.junit.Test

class ExclusionsTest {
    private val engine = FuzzyMindCore()
    private val base = listOf(
        AnswerEntry(1, "Привет", "Здравствуйте!", category = "Приветствия", exclusions = listOf("для бабушки", "Пока")),
        AnswerEntry(2, "Как дела?", "Отлично!")
    )
    private fun idOf(text: String) = (engine.resolve(text, base) as? MatchResult.Found)?.entry?.id

    @Test fun exclusionBlocksTheAnswer() {
        assertEquals(1L, idOf("привет"))
        assertNull(idOf("Привет для бабушки"))
        assertNull(idOf("привет и пока!"))
    }

    @Test fun exclusionNeedsWholeWords() {
        // "пока" inside "показать" is not a hit.
        assertEquals(1L, idOf("привет показать"))
    }

    @Test fun exclusionIgnoresCaseAndYo() {
        val e = listOf(AnswerEntry(1, "ёлка", "Новогодняя", exclusions = listOf("ЁЛКА ПАЛКА")))
        assertNull((engine.resolve("елка палка", e) as? MatchResult.Found))
    }

    @Test fun otherAnswersStillMatch() = assertEquals(2L, idOf("как дела для бабушки"))

    @Test fun categoryDoesNotAffectMatching() =
        assertEquals(idOf("привет"), (engine.resolve("привет", base.map { it.copy(category = "") }) as? MatchResult.Found)?.entry?.id)

    @Test fun roomRowsKeepCategoryAndExclusions() {
        val rows = base.mapIndexed { i, e -> e.toRows(i) }
        val back = assemble(rows.map { it.answer }, rows.flatMap { it.variants }, rows.flatMap { it.media })
        assertEquals(base, back)
    }

    @Test fun backupKeepsCategoryAndExclusions() {
        val back = BackupFormat.decode(BackupFormat.encode(base, "1.0", 0)) as BackupFormat.Decoded.Ok
        assertEquals(base.map { it.category to it.exclusions }, back.entries.map { it.category to it.exclusions })
    }
}
