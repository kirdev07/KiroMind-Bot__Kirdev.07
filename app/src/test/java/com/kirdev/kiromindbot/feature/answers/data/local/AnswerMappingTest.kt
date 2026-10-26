package com.kirdev.kiromindbot.feature.answers.data.local

import com.kirdev.kiromindbot.feature.answers.data.DemoAnswers
import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle
import com.kirdev.kiromindbot.feature.answers.domain.ReplyVariant
import org.junit.Assert.*
import org.junit.Test

class AnswerMappingTest {
    private fun roundTrip(entries: List<AnswerEntry>): List<AnswerEntry> {
        val rows = entries.mapIndexed { i, e -> e.toRows(i) }
        return assemble(rows.map { it.answer }, rows.flatMap { it.variants }, rows.flatMap { it.media })
    }

    @Test fun demoBaseSurvivesRoundTrip() = assertEquals(DemoAnswers.seed, roundTrip(DemoAnswers.seed))

    @Test fun variantMediaStaysWithItsVariantInOrder() {
        val a = AnswerMedia("a", MediaKind.PHOTO, "a.jpg", "tg-file:a", order = 0)
        val b = AnswerMedia("b", MediaKind.VOICE, "b.ogg", "tg-file:b", "audio/ogg", order = 0)
        val entry = AnswerEntry(1, "кот", "", media = listOf(a), afterEntryId = 9, alternatives = listOf(
            ReplyVariant("1:x", "мяу", listOf(b, a.copy(id = "c", order = 1)), ReplyStyle.CHEEKY, 4)
        ))
        val back = roundTrip(listOf(entry)).single()
        assertEquals(entry, back)
        assertEquals(listOf("b", "c"), back.alternatives.single().media.map { it.id })
    }

    @Test fun orderFollowsPosition() {
        val rows = listOf(AnswerEntry(1, "a", "x").toRows(5), AnswerEntry(2, "b", "y").toRows(-1))
        assertEquals(listOf(2L, 1L), assemble(rows.map { it.answer }, emptyList(), emptyList()).map { it.id })
    }

    @Test fun unknownEnumNamesDegradeSafely() {
        val answer = AnswerEntity(1, "q", "r", false, null, 0)
        val variant = VariantEntity("1:1", 1, "v", "FUTURE_STYLE", 1, 0)
        val media = MediaEntity("m", 1, null, "HOLOGRAM", "x", "y", null, 0)
        val e = assemble(listOf(answer), listOf(variant), listOf(media)).single()
        assertEquals(ReplyStyle.NEUTRAL, e.alternatives.single().style)
        assertTrue(e.media.isEmpty())
    }
}
