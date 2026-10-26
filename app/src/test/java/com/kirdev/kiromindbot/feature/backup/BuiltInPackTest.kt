package com.kirdev.kiromindbot.feature.backup

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle
import com.kirdev.kiromindbot.feature.backup.domain.BuiltInPack
import com.kirdev.kiromindbot.feature.engine.domain.FuzzyMindCore
import com.kirdev.kiromindbot.feature.engine.domain.MatchResult
import com.kirdev.kiromindbot.feature.engine.domain.Persona
import com.kirdev.kiromindbot.feature.engine.domain.ResponseSelector
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class BuiltInPackTest {
    private val small = """{"format":"kiromind-base","version":3,"topics":[
      {"category":"Приветствия","replies":[["NEUTRAL","Привет!"],["CHEEKY","Здорово"]],"questions":["привет","слушай привет"]},
      {"category":"О боте","replies":[["NEUTRAL","Я бот"]],"questions":["кто ты"]}
    ]}"""

    @Test fun parsesTopicsIntoAnswers() {
        val pack = BuiltInPack.parse(small, firstId = 10)!!
        assertEquals(3, pack.version)
        assertEquals(listOf("привет", "слушай привет", "кто ты"), pack.entries.map { it.question })
        assertEquals(listOf(10L, 11L, 12L), pack.entries.map { it.id })
        val hi = pack.entries.first()
        assertEquals("Привет!", hi.reply)
        assertEquals(ReplyStyle.CHEEKY, hi.alternatives.single().style)
        assertEquals("Приветствия", hi.category)
    }

    @Test fun rejectsForeignFiles() {
        assertNull(BuiltInPack.parse("""{"format":"kiromind-backup","answers":[]}""", 1))
        assertNull(BuiltInPack.parse("not json", 1))
    }

    @Test fun mergeNeverOverwritesOwnerAnswers() {
        val mine = listOf(AnswerEntry(5, "Привет!", "Мой собственный ответ"))
        val merged = BuiltInPack.mergeInto(mine, BuiltInPack.parse(small, 1)!!.entries)
        assertEquals("Мой собственный ответ", merged.first { it.id == 5L }.reply)
        assertEquals(listOf("слушай привет", "кто ты"), merged.drop(1).map { it.question })
        assertTrue(merged.drop(1).all { it.id > 5 })
        val ids = merged.flatMap { e -> e.alternatives.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        // Merging again adds nothing.
        assertEquals(merged, BuiltInPack.mergeInto(merged, BuiltInPack.parse(small, 1)!!.entries))
    }
}

/** Quality of the real shipped base (assets/base), the way MindCore sees it on the phone. */
class ShippedBaseQualityTest {
    private val base: List<AnswerEntry> by lazy {
        val file = listOf("src/main/assets/base/kiromind-base.json", "app/src/main/assets/base/kiromind-base.json")
            .map(::File).first { it.exists() }
        val text = file.readText(Charsets.UTF_8)
        BuiltInPack.parse(text, 1)!!.entries
    }
    private val engine = FuzzyMindCore()
    private fun reply(text: String) = (engine.resolve(text, base) as? MatchResult.Found)?.entry?.reply

    @Test fun hasThirtyThousandValidAnswers() {
        assertEquals(30_000, base.size)
        assertTrue(base.all { it.isValid })
        assertEquals(base.size, base.map { com.kirdev.kiromindbot.feature.engine.domain.TextNormalizer.normalize(it.question) }.toSet().size)
    }

    @Test fun everyDayPhrasesGetTheRightTopic() {
        assertEquals("Привет!", reply("Привет"))
        assertEquals("Привет!", reply("слушай, привет!"))
        assertEquals("Привет!", reply("приветт"))                 // typo
        assertEquals("Всё хорошо, спасибо! А у тебя?", reply("как дела?"))
        assertEquals("Всё хорошо, спасибо! А у тебя?", reply("ну как дела бот"))
        assertEquals("Пока!", reply("ладно, пока"))
        assertEquals("Пожалуйста!", reply("спасибо большое"))
    }

    @Test fun talksAboutKirdev() {
        for (q in listOf("кто тебя создал", "кто твой разработчик?", "а кто тебя сделал", "расскажи о kirdev", "как тебя создали")) {
            val r = reply(q)
            assertNotNull(q, r)
            assertTrue("$q -> $r", r!!.contains("Kirdev") || r.contains("Kotlin"))
        }
    }

    @Test fun everyTopicPhrasingFindsItsOwnTopic() {
        // The bare phrasing of each entry must resolve to an entry with the same reply (its topic).
        val misses = base.asSequence().filter { e -> base.count { it.reply == e.reply } > 0 }
            .filterIndexed { i, _ -> i % 50 == 0 }  // a 600-entry sample keeps the test fast
            .count { e -> reply(e.question) != e.reply }
        assertEquals(0, misses)
    }

    @Test fun autoPersonaNeverSwears() {
        val selector = ResponseSelector(Random(1))
        val samples = base.distinctBy { it.reply }
        repeat(20) {
            samples.forEach { e -> assertNotEquals(ReplyStyle.CHEEKY, selector.select(e, Persona.AUTO, null)!!.style) }
        }
        // ...but the cheeky character does get its own lines.
        val hi = base.first { it.question == "привет" }
        assertEquals(ReplyStyle.CHEEKY, selector.select(hi, Persona.CHEEKY, null)!!.style)
    }
}
