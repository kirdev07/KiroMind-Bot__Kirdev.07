package com.kirdev.kiromindbot.feature.backup

import com.kirdev.kiromindbot.feature.answers.data.DemoAnswers
import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle
import com.kirdev.kiromindbot.feature.answers.domain.ReplyVariant
import com.kirdev.kiromindbot.feature.backup.domain.BackupFormat
import com.kirdev.kiromindbot.feature.backup.domain.ImportMode
import com.kirdev.kiromindbot.feature.backup.domain.ImportPlanner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupFormatTest {
    private fun roundTrip(list: List<AnswerEntry>) =
        (BackupFormat.decode(BackupFormat.encode(list, "1.0.0", 123)) as BackupFormat.Decoded.Ok)

    @Test fun demoBaseRoundTrips() {
        val back = roundTrip(DemoAnswers.seed)
        assertEquals(DemoAnswers.seed.map { it.copy(media = it.media.mapIndexed { i, m -> m.copy(order = i) }) }, back.entries)
        assertEquals(0, back.skipped)
        assertEquals(123L, back.exportedAt)
    }

    @Test fun noSecretsInFile() {
        val text = BackupFormat.encode(DemoAnswers.seed, "1.0.0", 0)
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("api_key", ignoreCase = true))
    }

    @Test fun rejectsForeignAndFutureFiles() {
        assertTrue(BackupFormat.decode("not json") is BackupFormat.Decoded.Error)
        assertTrue(BackupFormat.decode("""{"answers":[]}""") is BackupFormat.Decoded.Error)
        val future = BackupFormat.decode("""{"format":"kiromind-backup","version":99,"answers":[]}""") as BackupFormat.Decoded.Error
        assertTrue(future.reason.contains("новой версией"))
    }

    @Test fun damagedRecordsAreSkippedAndCounted() {
        val text = """{"format":"kiromind-backup","version":1,"answers":[
          {"id":1,"question":"привет","reply":"Привет!"},
          {"id":2,"question":"","reply":"пустой вопрос"},
          {"id":3,"question":"нет ответа","reply":""},
          {"question":"нет id","reply":"x"},
          {"id":1,"question":"дубль id","reply":"x"},
          {"id":4,"question":"фото","reply":"","media":[{"kind":"PHOTO","name":"a.jpg","source":"content://x/1"}]},
          {"id":5,"question":"странный стиль","reply":"ok","variants":[{"text":"v","style":"ALIEN","weight":999}]}
        ]}"""
        val d = BackupFormat.decode(text) as BackupFormat.Decoded.Ok
        assertEquals(listOf(1L, 4L, 5L), d.entries.map { it.id })
        assertEquals(4, d.skipped)
        assertEquals(1, d.localFiles)
        val v = d.entries.last().alternatives.single()
        assertEquals(ReplyStyle.NEUTRAL, v.style); assertEquals(10, v.weight)
    }

    @Test fun tooManyEntriesRefused() {
        val arr = org.json.JSONArray(); repeat(BackupFormat.MAX_ENTRIES + 1) { arr.put(JSONObject()) }
        val text = JSONObject().put("format", "kiromind-backup").put("version", 1).put("answers", arr).toString()
        assertTrue(BackupFormat.decode(text) is BackupFormat.Decoded.Error)
    }
}

class ImportPlannerTest {
    private val existing = listOf(
        AnswerEntry(1, "Привет", "Привет!", alternatives = listOf(ReplyVariant("1:1", "Здравствуй"))),
        AnswerEntry(6, "Как дела?", "Отлично!"),
        AnswerEntry(7, "Тоже хорошо", "Рад!", afterEntryId = 6)
    )

    @Test fun mergeMatchesByQuestionKeepsIdsAndAddsRest() {
        val incoming = listOf(
            AnswerEntry(1, "привет!!", "Хай", alternatives = listOf(ReplyVariant("1:1", "Йо"))),   // updates #1
            AnswerEntry(2, "Как дела?", "Отлично!"),                                                // same as #6
            AnswerEntry(3, "Кто ты", "Бот"),                                                        // new
            AnswerEntry(4, "Плохо", "Жаль", afterEntryId = 2)                                       // new, follows "как дела"
        )
        val p = ImportPlanner.plan(existing, incoming, ImportMode.MERGE)
        assertEquals(2, p.added); assertEquals(1, p.updated); assertEquals(1, p.unchanged); assertEquals(0, p.removed)
        val byQ = p.result.associateBy { it.question }
        assertEquals(1L, byQ.getValue("привет!!").id)
        assertEquals("Хай", byQ.getValue("привет!!").reply)
        val newIds = setOf(byQ.getValue("Кто ты").id, byQ.getValue("Плохо").id)
        assertTrue(newIds.all { it > 7 })
        // The follow-up now points at the existing "Как дела?" (#6), not at the file's #2.
        assertEquals(6L, byQ.getValue("Плохо").afterEntryId)
        assertEquals(6L, byQ.getValue("Тоже хорошо").afterEntryId)
    }

    @Test fun childIdsNeverCollideAcrossAnswers() {
        val photo = AnswerMedia("m1", MediaKind.PHOTO, "a.jpg", "tg-file:a")
        val incoming = listOf(AnswerEntry(1, "Новый вопрос", "x", listOf(photo), alternatives = listOf(ReplyVariant("1:1", "y"))))
        val p = ImportPlanner.plan(existing, incoming, ImportMode.MERGE)
        val variantIds = p.result.flatMap { e -> e.alternatives.map { it.id } }
        assertEquals(variantIds.size, variantIds.toSet().size)
        val added = p.result.first { it.question == "Новый вопрос" }
        assertTrue(added.alternatives.single().id.startsWith("${added.id}:"))
        assertTrue(added.media.single().id.startsWith("${added.id}:"))
    }

    @Test fun replaceDropsDanglingLinks() {
        val incoming = listOf(AnswerEntry(10, "a", "b"), AnswerEntry(11, "c", "d", afterEntryId = 99))
        val p = ImportPlanner.plan(existing, incoming, ImportMode.REPLACE)
        assertEquals(listOf(10L, 11L), p.result.map { it.id })
        assertNull(p.result[1].afterEntryId)
        assertEquals(3, p.removed)
    }

    @Test fun reimportingSameBaseChangesNothing() {
        val p = ImportPlanner.plan(existing, existing, ImportMode.MERGE)
        assertEquals(0, p.added); assertEquals(0, p.updated); assertEquals(3, p.unchanged)
        assertEquals(existing.toSet(), p.result.toSet())
    }
}
