package com.kirdev.kiromindbot.feature.telegram

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import com.kirdev.kiromindbot.feature.engine.domain.*
import com.kirdev.kiromindbot.feature.learning.domain.InMemoryUnknownQuestionStore
import com.kirdev.kiromindbot.feature.security.SecretRead
import com.kirdev.kiromindbot.feature.security.SecretStore
import com.kirdev.kiromindbot.feature.telegram.api.*
import com.kirdev.kiromindbot.feature.telegram.data.TelegramController
import com.kirdev.kiromindbot.feature.telegram.data.TelegramJson
import com.kirdev.kiromindbot.feature.telegram.data.TokenCheck
import com.kirdev.kiromindbot.feature.telegram.domain.TelegramBotRunner
import com.kirdev.kiromindbot.feature.telegram.domain.planReply
import com.kirdev.kiromindbot.feature.telegram.domain.toIncoming
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TelegramJsonTest {
    @Test fun parsesMessagesAndSkipsJunk() {
        val body = """{"ok":true,"result":[
          {"update_id":10,"message":{"message_id":5,"chat":{"id":-100,"type":"supergroup"},
            "from":{"id":7,"is_bot":false},"text":"@KiroBot привет","entities":[{"type":"mention","offset":0,"length":8}],
            "reply_to_message":{"message_id":4,"from":{"id":42,"is_bot":true}}}},
          {"update_id":11,"edited_message":{}},
          {"update_id":12,"message":{"broken":true}},
          {"nope":1}
        ]}"""
        val updates = TelegramJson.updates(TelegramJson.result(body))
        assertEquals(listOf(10L, 11L, 12L), updates.map { it.updateId })
        val m = updates[0].message!!
        assertEquals(listOf("KiroBot"), m.mentions)
        assertEquals(42L, m.replyToFromId)
        assertNull(updates[1].message)
        assertNull(updates[2].message)
    }

    @Test fun errorCarriesRetryAfter() {
        val e = assertThrows(TelegramApiException::class.java) {
            TelegramJson.result("""{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":7}}""")
        }
        assertEquals(429, e.code); assertEquals(7, e.retryAfterSeconds)
    }

    @Test fun buildsRequests() {
        val (m, body) = TelegramJson.request(OutgoingAction.Media(1, TgMediaMethod.VOICE, MediaFile.Remote("abc"), "hi", 9))
        assertEquals("sendVoice", m)
        assertEquals("abc", body.getString("voice"))
        assertEquals(9, body.getJSONObject("reply_parameters").getInt("message_id"))
    }

    @Test fun readsFileIdOfSentMedia() {
        val photo = TelegramJson.result("""{"ok":true,"result":{"message_id":1,"photo":[{"file_id":"small"},{"file_id":"big"}]}}""")
        assertEquals("big", TelegramJson.sentFileId(photo, TgMediaMethod.PHOTO))
        val gifAsDoc = TelegramJson.result("""{"ok":true,"result":{"message_id":1,"document":{"file_id":"D"}}}""")
        assertEquals("D", TelegramJson.sentFileId(gifAsDoc, TgMediaMethod.ANIMATION))
        assertNull(TelegramJson.sentFileId(TelegramJson.result("""{"ok":true,"result":{"message_id":1}}"""), TgMediaMethod.VOICE))
    }
}

class TelegramMappingTest {
    private val bot = BotIdentity(42, "KiroBot")
    private fun msg(type: String = "private", from: Long? = 7, text: String? = "привет", mentions: List<String> = emptyList(), isBot: Boolean = false, replyTo: Long? = null) =
        TgMessage(1, 100, type, from, isBot, text, mentions, replyTo)

    @Test fun mapsChatKindsAndAddressing() {
        assertEquals(ChatKind.PRIVATE, msg().toIncoming(bot)!!.chatKind)
        val g = msg("supergroup", mentions = listOf("kirobot"), replyTo = 42).toIncoming(bot)!!
        assertEquals(ChatKind.GROUP, g.chatKind); assertTrue(g.mentionsBot); assertTrue(g.replyToBot)
        assertNull(msg("channel").toIncoming(bot))
        assertNull(msg(text = null).toIncoming(bot))
        assertNull(msg(from = null).toIncoming(bot))
        assertTrue(msg(from = 42).toIncoming(bot)!!.fromSelf)
        assertTrue(msg(isBot = true).toIncoming(bot)!!.senderIsBot)
    }

    private fun reply(text: String, vararg media: AnswerMedia) = BotReply(text, media.toList(), 1, "1:0", "exact", 1.0, "")

    @Test fun captionWhenTextFits() {
        val plan = planReply(5, 3, reply("Кот", AnswerMedia("a", MediaKind.PHOTO, "кот.jpg", "tg-file:XYZ")))
        val only = plan.actions.single() as OutgoingAction.Media
        assertEquals(MediaFile.Remote("XYZ"), only.file); assertEquals("Кот", only.caption); assertEquals(3L, only.replyTo)
    }

    @Test fun pickedFilesBecomeUploads() {
        val plan = planReply(5, null, reply("", AnswerMedia("v", MediaKind.VOICE, "мяу.ogg", "content://media/1", "audio/ogg")))
        assertEquals(0, plan.skippedMedia)
        assertEquals(MediaFile.Local("content://media/1", "мяу.ogg", "audio/ogg"), (plan.actions.single() as OutgoingAction.Media).file)
    }

    @Test fun demoMediaSkippedAndLinksAppended() {
        val plan = planReply(5, null, reply("Смотри",
            AnswerMedia("d", MediaKind.VIDEO, "demo.mp4", "demo://video"),
            AnswerMedia("l", MediaKind.LINK, "site", "https://example.org"),
            AnswerMedia("g", MediaKind.GIF, "x.gif", "https://example.org/x.gif", order = 2)))
        assertEquals(1, plan.skippedMedia)
        val media = plan.actions.single() as OutgoingAction.Media
        assertEquals(TgMediaMethod.ANIMATION, media.method)
        assertEquals("Смотри\nhttps://example.org", media.caption)
    }

    @Test fun longTextSplitAndSentBeforeMedia() {
        val plan = planReply(5, 3, reply("x".repeat(5000), AnswerMedia("a", MediaKind.DOCUMENT, "f", "tg-file:F")))
        assertEquals(3, plan.actions.size)
        assertEquals(3L, (plan.actions[0] as OutgoingAction.Text).replyTo)
        assertNull((plan.actions[1] as OutgoingAction.Text).replyTo)
        assertNull((plan.actions[2] as OutgoingAction.Media).caption)
    }

    @Test fun unverifiedAiIsLabelled() {
        val plan = planReply(5, null, BotReply("Ответ", emptyList(), null, null, "external-ai", 0.0, "AI", unverified = true))
        assertTrue((plan.actions.single() as OutgoingAction.Text).text.contains("не проверен"))
    }
}

/** Scripted Bot API: each getUpdates call takes the next batch or throws. */
private class FakeApi(var me: () -> BotIdentity = { BotIdentity(42, "KiroBot") }) : TelegramBotApi {
    val batches = ArrayDeque<() -> List<TgUpdate>>()
    val sent = mutableListOf<OutgoingAction>()
    val offsets = mutableListOf<Long?>()
    var sendError: TelegramApiException? = null
    override suspend fun getMe() = me()
    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate> {
        offsets += offset
        if (offset == -1L) return listOf(TgUpdate(99, null)) // backlog marker
        val next = batches.removeFirstOrNull() ?: run { awaitCancellation() }
        return next()
    }
    /** Each upload of a local file gets a new id, so a test can tell uploads from cache hits. */
    var uploads = 0
    var rejectRemote: String? = null
    var unavailable = false
    override suspend fun send(action: OutgoingAction): String? {
        sendError?.let { sendError = null; throw it }
        val file = (action as? OutgoingAction.Media)?.file
        if (file is MediaFile.Remote && file.ref == rejectRemote) throw TelegramApiException(400, "Bad Request: wrong file identifier", null)
        if (file is MediaFile.Local && unavailable) throw MediaUnavailableException("файл недоступен")
        sent += action
        return when (file) {
            is MediaFile.Local -> "fid-${++uploads}"
            is MediaFile.Remote -> file.ref
            null -> null
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramBotRunnerTest {
    private val events = mutableListOf<DiagnosticEvent>()
    private var base = listOf(AnswerEntry(1, "привет", "Привет!"))
    private val taught = mutableListOf<Pair<String, String>>()

    private fun TestScope.runner(api: FakeApi, owners: Set<Long> = emptySet()) = TelegramBotRunner(
        api,
        MessagePipeline(FuzzyMindCore(), { base }, ResponseSelector(Random(1)), ConversationMemory(), InMemoryUnknownQuestionStore(),
            { events += it }, { true }, dispatcher = StandardTestDispatcher(testScheduler)),
        settings = { BehaviorSettings(ownerIds = owners) },
        onTeach = { q, a -> taught += q to a; AnswerEntry(2, q, a) },
        diagnostics = { events += it },
        fileIds = cache,
        clock = { testScheduler.currentTime }
    )

    private val cache = object : FileIdStore {
        val map = mutableMapOf<Pair<String, TgMediaMethod>, String>()
        override suspend fun get(source: String, method: TgMediaMethod) = map[source to method]
        override suspend fun put(source: String, method: TgMediaMethod, fileId: String) { map[source to method] = fileId }
        override suspend fun remove(source: String, method: TgMediaMethod) { map.remove(source to method) }
    }

    private fun text(id: Long, t: String, from: Long = 7, chat: Long = 100) =
        TgUpdate(id, TgMessage(id, chat, "private", from, false, t, emptyList(), null))

    @Test fun skipsBacklogAndAnswers() = runTest {
        val api = FakeApi()
        api.batches += { listOf(text(100, "привет", chat = 555123), text(101, "неизвестно", chat = 555123)) }
        val r = runner(api)
        val job = launch { r.run() }
        advanceUntilIdle()
        assertEquals(listOf(-1L, 100L, 102L), api.offsets)
        assertEquals(ConnectionStatus.Online("KiroBot"), r.status.value)
        val sent = api.sent.single() as OutgoingAction.Text
        assertEquals("Привет!", sent.text); assertEquals(100L, sent.replyTo)
        assertTrue(events.none { it.message.contains("привет") || it.message.contains("неизвестно") || it.message.contains("555123") })
        job.cancel()
    }

    @Test fun perChatSpacing() = runTest {
        val api = FakeApi()
        api.batches += { listOf(text(100, "привет"), text(101, "привет")) }
        val job = launch { runner(api).run() }
        advanceTimeBy(500); runCurrent()
        assertEquals(1, api.sent.size)
        advanceTimeBy(600); runCurrent()
        assertEquals(2, api.sent.size)
        job.cancel()
    }

    @Test fun teachOnlyFromOwner() = runTest {
        val api = FakeApi()
        api.batches += { listOf(text(100, "/teach слон = Большой", from = 7), text(101, "/teach кит = Синий", from = 99)) }
        val job = launch { runner(api, owners = setOf(99)).run() }
        advanceUntilIdle()
        assertEquals(listOf("кит" to "Синий"), taught)
        assertTrue((api.sent.single() as OutgoingAction.Text).text.startsWith("Запомнил"))
        job.cancel()
    }

    private val catBase = listOf(AnswerEntry(3, "кот", "Мяу", listOf(AnswerMedia("p", MediaKind.PHOTO, "кот.jpg", "content://pics/1", "image/jpeg"))))

    @Test fun localFileUploadedOnceThenCached() = runTest {
        base = catBase
        val api = FakeApi()
        api.batches += { listOf(text(100, "кот")) }
        api.batches += { listOf(text(101, "кот")) }
        val job = launch { runner(api).run() }
        advanceUntilIdle()
        val files = api.sent.map { (it as OutgoingAction.Media).file }
        assertEquals(listOf(MediaFile.Local("content://pics/1", "кот.jpg", "image/jpeg"), MediaFile.Remote("fid-1")), files)
        assertEquals(1, api.uploads)
        job.cancel()
    }

    @Test fun rejectedCachedIdIsReuploaded() = runTest {
        base = catBase
        cache.put("content://pics/1", TgMediaMethod.PHOTO, "stale")
        val api = FakeApi().apply { rejectRemote = "stale" }
        api.batches += { listOf(text(100, "кот")) }
        val job = launch { runner(api).run() }
        advanceUntilIdle()
        assertEquals(1, api.uploads)
        assertEquals("fid-1", cache.get("content://pics/1", TgMediaMethod.PHOTO))
        job.cancel()
    }

    @Test fun missingFileIsSkippedAndBotContinues() = runTest {
        base = catBase + AnswerEntry(1, "привет", "Привет!")
        val api = FakeApi().apply { unavailable = true }
        api.batches += { listOf(text(100, "кот"), text(101, "привет", chat = 200)) }
        val job = launch { runner(api).run() }
        advanceUntilIdle()
        assertEquals("Привет!", (api.sent.single() as OutgoingAction.Text).text)
        assertTrue(events.any { it.message.contains("Вложение не отправлено") })
        job.cancel()
    }

    @Test fun badTokenStops() = runTest {
        val api = FakeApi(me = { throw TelegramApiException(401, "Unauthorized", null) })
        val r = runner(api)
        r.run() // returns instead of looping
        assertTrue(r.status.value is ConnectionStatus.Error)
    }

    @Test fun networkErrorsBackOffThenRecover() = runTest {
        val api = FakeApi()
        api.batches += { throw TelegramNetworkException("UnknownHostException") }
        api.batches += { throw TelegramNetworkException("UnknownHostException") }
        api.batches += { listOf(text(100, "привет")) }
        val r = runner(api)
        val job = launch { r.run() }
        runCurrent()
        assertTrue(r.status.value is ConnectionStatus.Reconnecting)
        advanceTimeBy(2_000); runCurrent()   // first wait 2s
        assertTrue(api.sent.isEmpty())
        advanceTimeBy(4_000); runCurrent()   // then 4s
        advanceUntilIdle()
        assertEquals(1, api.sent.size)
        assertEquals(ConnectionStatus.Online("KiroBot"), r.status.value)
        job.cancel()
    }

    @Test fun rejectedSendDoesNotStopBot() = runTest {
        val api = FakeApi()
        api.sendError = TelegramApiException(403, "Forbidden: bot was blocked by the user", null)
        api.batches += { listOf(text(100, "привет"), text(101, "привет", chat = 200)) }
        val job = launch { runner(api).run() }
        advanceUntilIdle()
        assertEquals(200L, api.sent.single().chatId)
        job.cancel()
    }
}

private class MemorySecrets : SecretStore {
    val map = mutableMapOf<String, String>()
    var unreadable = false
    var failSave = false
    override suspend fun save(name: String, secret: String) = !failSave && map.put(name, secret).let { true }
    override suspend fun read(name: String) = when {
        unreadable -> SecretRead.Unreadable
        else -> map[name]?.let { SecretRead.Value(it) } ?: SecretRead.Missing
    }
    override suspend fun delete(name: String) { map.remove(name) }
}

class TelegramControllerTest {
    private val goodToken = "123456789:AAH" + "x".repeat(32)
    private val secrets = MemorySecrets()
    private var accepted = true

    private fun controller(scope: CoroutineScope) = TelegramController(
        secrets, scope,
        MessagePipeline(FuzzyMindCore(), { emptyList() }, ResponseSelector(), ConversationMemory(), InMemoryUnknownQuestionStore(), {}, { true }),
        settings = { BehaviorSettings() }, onTeach = { _, _ -> null }, diagnostics = {},
        apiFactory = { FakeApi(me = { if (accepted) BotIdentity(42, "KiroBot") else throw TelegramApiException(401, "Unauthorized", null) }) }
    )

    @Test fun savesOnlyAfterTelegramAccepts() = runTest {
        val c = controller(this)
        assertTrue(c.saveToken("not a token") is TokenCheck.Invalid)
        accepted = false
        assertTrue(c.saveToken(goodToken) is TokenCheck.Invalid)
        assertTrue(secrets.map.isEmpty())
        accepted = true
        assertEquals(TokenCheck.Ok("KiroBot"), c.saveToken(" $goodToken "))
        assertEquals(goodToken, secrets.map.values.single())
        assertTrue(c.hasToken.value)
    }

    @Test fun encryptionFailureIsNotSaved() = runTest {
        secrets.failSave = true
        val r = controller(this).saveToken(goodToken)
        assertTrue(r is TokenCheck.Invalid && r.reason.contains("не сохранён"))
    }

    @Test fun unreadableTokenFailsClosed() = runTest {
        secrets.map["telegram_bot_token"] = goodToken
        secrets.unreadable = true
        val c = controller(this)
        assertTrue(c.checkSaved() is TokenCheck.Invalid)
        c.start(); advanceUntilIdle()
        assertEquals(ConnectionStatus.NoToken, c.status.value)
    }

    @Test fun deleteStopsAndForgets() = runTest {
        secrets.map["telegram_bot_token"] = goodToken
        val c = controller(this)
        c.deleteToken()
        assertTrue(secrets.map.isEmpty())
        assertFalse(c.hasToken.value)
        advanceUntilIdle()
    }
}
