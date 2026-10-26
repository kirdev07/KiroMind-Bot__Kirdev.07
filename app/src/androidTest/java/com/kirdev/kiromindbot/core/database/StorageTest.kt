package com.kirdev.kiromindbot.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kirdev.kiromindbot.feature.answers.data.DemoAnswers
import com.kirdev.kiromindbot.feature.answers.data.RoomAnswerRepository
import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle
import com.kirdev.kiromindbot.feature.answers.domain.ReplyVariant
import com.kirdev.kiromindbot.feature.learning.data.RoomUnknownQuestionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device: real SQLite, a fresh temporary file per test. */
@RunWith(AndroidJUnit4::class)
class StorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "storage-test.db"
    private lateinit var scope: CoroutineScope
    private val opened = mutableListOf<KiroMindDatabase>()

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), KiroMindDatabase::class.java)

    private fun open(): KiroMindDatabase =
        Room.databaseBuilder(context, KiroMindDatabase::class.java, dbName)
            .addMigrations(*KiroMindDatabase.MIGRATIONS).build().also { opened += it }

    @Before fun setUp() {
        context.deleteDatabase(dbName)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun tearDown() {
        // Background writers must finish before their database is closed.
        runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
        opened.forEach { it.close() }
        context.deleteDatabase(dbName)
    }

    /** Opens a repository and waits for its first read; [after] runs once writes are flushed. */
    private fun repository(db: KiroMindDatabase, seeded: Boolean) = RoomAnswerRepository(
        db.answers(), scope, DemoAnswers.seed, isSeeded = { seeded }
    ).also { r -> runBlocking { withTimeout(5_000) { r.loaded.first { it } } } }

    /** The writer is serial, so a read queued through the DAO after the writes sees them. */
    private fun flush(db: KiroMindDatabase) = runBlocking { db.answers().count() }

    @Test fun seedsOnceIntoEmptyDatabase() {
        val db = open()
        val repo = repository(db, seeded = false)
        assertEquals(DemoAnswers.seed, repo.entries.value)
        // Seeding is skipped once the flag is set, even if the base was emptied.
        DemoAnswers.seed.forEach { repo.remove(it.id) }
        Thread.sleep(300); flush(db)
        assertTrue(repository(db, seeded = true).entries.value.isEmpty())
    }

    @Test fun answersVariantsAndMediaSurviveReopen() {
        val entry = AnswerEntry(100, "кот", "Мяу", listOf(AnswerMedia("p", MediaKind.PHOTO, "кот.jpg", "tg-file:1")),
            alternatives = listOf(ReplyVariant("100:a", "", listOf(AnswerMedia("v", MediaKind.VOICE, "мяу.ogg", "tg-file:2")), ReplyStyle.CHEEKY, 3)),
            afterEntryId = 6)
        val db = open()
        val repo = repository(db, seeded = false)
        repo.upsert(entry)
        repo.toggleFavorite(1)
        repo.upsert(entry.copy(reply = "Мяу-мяу", alternatives = emptyList()))
        repo.remove(2)
        Thread.sleep(300)
        db.close()

        val again = repository(open(), seeded = true).entries.value
        assertEquals(entry.copy(reply = "Мяу-мяу", alternatives = emptyList()), again.first())
        assertFalse(again.first { it.id == 1L }.favorite)
        assertNull(again.firstOrNull { it.id == 2L })
        assertEquals(DemoAnswers.seed.size, again.size)
        // Removing the variant also removed its media rows.
        assertTrue(runBlocking { open().answers().media() }.none { it.id == "v" })
    }

    @Test fun unknownQuestionsSurviveReopenAndMerge() {
        val db = open()
        val store = RoomUnknownQuestionStore(db.unknownQuestions(), scope, capacity = 2)
        store.record("один", 1)
        store.record("Где слон?", 2)
        store.record("где СЛОН", 3)
        store.record("два", 4) // capacity 2 evicts "один"
        store.dismiss("два")
        Thread.sleep(300)
        db.close()

        val reopened = RoomUnknownQuestionStore(open().unknownQuestions(), scope, capacity = 2)
        Thread.sleep(300)
        assertEquals(listOf("где слон"), reopened.all().map { it.key })
        assertEquals(2, reopened.all().single().count)
        assertEquals(2L, reopened.all().single().firstSeenMillis)
    }

    @Test fun migration2To3AddsBlacklistAndKeepsData() {
        migrations.createDatabase(dbName, 2).apply {
            execSQL("INSERT INTO answers (id, question, reply, favorite, after_entry_id, position) VALUES (5, 'кот', 'Мяу', 0, NULL, 0)")
            execSQL("INSERT INTO telegram_file_cache (source, method, file_id, cached_at) VALUES ('content://x', 'PHOTO', 'fid', 1)")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 3, true, KiroMindDatabase.MIGRATION_2_3).close()
        val db = open()
        assertEquals("кот", runBlocking { db.answers().answers() }.single().question)
        assertEquals("fid", runBlocking { db.telegramFiles().get("content://x", "PHOTO") })
        assertTrue(runBlocking { db.blockedUsers().all() }.isEmpty())
    }

    @Test fun blacklistSurvivesReopen() {
        val db = open()
        val list = com.kirdev.kiromindbot.feature.protection.data.RoomBlacklist(db.blockedUsers(), scope)
        list.block(7, "флуд", now = 1)
        list.block(8, "антиспам", now = 2, untilMillis = 1_000)
        list.block(9, "x", now = 3)
        list.unblock(9)
        // Wait for the rows on disk instead of sleeping.
        runBlocking { withTimeout(5_000) { while (db.blockedUsers().all().map { it.userId }.toSet() != setOf(7L, 8L)) kotlinx.coroutines.delay(20) } }

        val again = com.kirdev.kiromindbot.feature.protection.data.RoomBlacklist(open().blockedUsers(), scope)
        runBlocking { withTimeout(5_000) { again.items.first { it.size == 2 } } }
        assertTrue(again.isBlocked(7, 10_000))
        assertFalse(again.isBlocked(9, 10))
        assertTrue(again.isBlocked(8, 500))
        assertFalse(again.isBlocked(8, 1_000)) // the temporary block has expired
    }

    @Test fun migration1To2KeepsDataAndMatchesSchema() {
        // A v1 database exactly as app/schemas/…/1.json describes it, with real rows.
        migrations.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO answers (id, question, reply, favorite, after_entry_id, position) VALUES (5, 'кот', 'Мяу', 1, NULL, 0)")
            execSQL("INSERT INTO reply_variants (id, answer_id, text, style, weight, position) VALUES ('5:a', 5, 'мур', 'FRIENDLY', 2, 0)")
            execSQL("INSERT INTO answer_media (id, answer_id, variant_id, kind, display_name, source, mime_type, send_order) VALUES ('m', 5, NULL, 'PHOTO', 'кот.jpg', 'content://x', 'image/jpeg', 0)")
            execSQL("INSERT INTO unknown_questions (`key`, sample, count, first_seen, last_seen) VALUES ('слон', 'Слон?', 3, 1, 2)")
            close()
        }
        // Validates the migrated schema against 2.json and the current entities.
        migrations.runMigrationsAndValidate(dbName, 2, true, KiroMindDatabase.MIGRATION_1_2).close()

        val db = open()
        val answers = runBlocking { db.answers().answers() }
        assertEquals(listOf("кот"), answers.map { it.question })
        assertEquals("FRIENDLY", runBlocking { db.answers().variants() }.single().style)
        assertEquals("content://x", runBlocking { db.answers().media() }.single().source)
        assertEquals(3, runBlocking { db.unknownQuestions().all() }.single().count)
        // The new table works.
        runBlocking {
            db.telegramFiles().put(com.kirdev.kiromindbot.feature.telegram.data.local.FileIdEntity("content://x", "PHOTO", "fid", 1))
            assertEquals("fid", db.telegramFiles().get("content://x", "PHOTO"))
        }
    }
}
