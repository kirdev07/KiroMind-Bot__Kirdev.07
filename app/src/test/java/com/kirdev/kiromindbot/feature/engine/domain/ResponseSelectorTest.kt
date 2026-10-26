package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle
import com.kirdev.kiromindbot.feature.answers.domain.ReplyVariant
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ResponseSelectorTest {
    private val selector = ResponseSelector(Random(42))
    private val hello = AnswerEntry(1, "Привет", "Привет!", alternatives = listOf(
        ReplyVariant("1:1", "Здравствуй!"),
        ReplyVariant("1:2", "Рад тебя видеть!", style = ReplyStyle.FRIENDLY),
        ReplyVariant("1:3", "Добрый день.", style = ReplyStyle.POLITE)
    ))

    @Test fun neverRepeatsPreviousWhenAlternativeExists() {
        var previous: String? = null
        repeat(200) {
            val v = selector.select(hello, Persona.NEUTRAL, previous)!!
            assertNotEquals(previous, v.id)
            assertEquals(ReplyStyle.NEUTRAL, v.style)
            previous = v.id
        }
    }

    @Test fun usesAllNeutralVariantsOverTime() {
        val seen = (1..100).map { selector.select(hello, Persona.NEUTRAL, null)!!.id }.toSet()
        assertEquals(setOf("1:0", "1:1"), seen)
    }

    @Test fun singleVariantMayRepeat() {
        val single = AnswerEntry(2, "Как дела", "Хорошо")
        assertEquals("2:0", selector.select(single, Persona.NEUTRAL, "2:0")!!.id)
    }

    @Test fun personaPicksItsStyle() {
        assertEquals("1:2", selector.select(hello, Persona.FRIENDLY, null)!!.id)
        assertEquals("1:3", selector.select(hello, Persona.POLITE, null)!!.id)
    }

    @Test fun missingStyleFallsBackToNeutral() {
        repeat(20) { assertEquals(ReplyStyle.NEUTRAL, selector.select(hello, Persona.CHEEKY, null)!!.style) }
    }

    @Test fun autoUsesEveryStyle() {
        val seen = (1..200).map { selector.select(hello, Persona.AUTO, null)!!.id }.toSet()
        assertEquals(4, seen.size)
    }

    @Test fun weightShiftsChoice() {
        val e = AnswerEntry(3, "q", "редко", alternatives = listOf(ReplyVariant("3:1", "часто", weight = 9)))
        val often = (1..1000).count { selector.select(e, Persona.NEUTRAL, null)!!.id == "3:1" }
        assertTrue(often in 820..980)
    }

    @Test fun mediaOnlyVariantIsUsableAndBlankIsSkipped() {
        val photo = AnswerMedia("m", MediaKind.PHOTO, "cat.jpg", "tg-file:abc")
        val e = AnswerEntry(4, "кот", "", alternatives = listOf(ReplyVariant("4:1", "", listOf(photo))))
        repeat(10) { assertEquals("4:1", selector.select(e, Persona.NEUTRAL, null)!!.id) }
    }

    @Test fun nothingUsableReturnsNull() {
        assertNull(selector.select(AnswerEntry(5, "q", ""), Persona.NEUTRAL, null))
    }
}
