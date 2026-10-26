package com.kirdev.kiromindbot.core.di

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.kirdev.kiromindbot.core.database.KiroMindDatabase
import com.kirdev.kiromindbot.BuildConfig
import com.kirdev.kiromindbot.feature.update.data.UpdateManager
import com.kirdev.kiromindbot.feature.update.domain.SemVer
import com.kirdev.kiromindbot.feature.update.domain.UpdateCheck
import com.kirdev.kiromindbot.core.state.BotSession
import com.kirdev.kiromindbot.feature.answers.data.DemoAnswers
import com.kirdev.kiromindbot.feature.answers.data.RoomAnswerRepository
import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.engine.domain.BehaviorSettings
import com.kirdev.kiromindbot.feature.engine.domain.ConversationMemory
import com.kirdev.kiromindbot.feature.engine.domain.DiagnosticEvent
import com.kirdev.kiromindbot.feature.engine.domain.DiagnosticSink
import com.kirdev.kiromindbot.feature.engine.domain.FuzzyMindCore
import com.kirdev.kiromindbot.feature.engine.domain.MessagePipeline
import com.kirdev.kiromindbot.feature.engine.domain.ResponseSelector
import com.kirdev.kiromindbot.feature.learning.data.RoomUnknownQuestionStore
import com.kirdev.kiromindbot.feature.security.KeystoreSecretStore
import com.kirdev.kiromindbot.feature.security.SecretNames
import com.kirdev.kiromindbot.feature.security.SecretRead
import com.kirdev.kiromindbot.feature.ai.data.AiKind
import com.kirdev.kiromindbot.feature.ai.data.AiRouter
import com.kirdev.kiromindbot.feature.engine.domain.DailyQuota
import com.kirdev.kiromindbot.feature.engine.domain.RateLimiter
import kotlinx.coroutines.flow.MutableStateFlow
import com.kirdev.kiromindbot.feature.settings.domain.AppPreferences
import com.kirdev.kiromindbot.feature.telegram.data.TelegramController
import com.kirdev.kiromindbot.feature.protection.data.RoomBlacklist
import com.kirdev.kiromindbot.feature.protection.domain.AntiSpam
import com.kirdev.kiromindbot.feature.telegram.data.AndroidLocalFiles
import com.kirdev.kiromindbot.feature.telegram.data.HttpTelegramBotApi
import com.kirdev.kiromindbot.feature.telegram.data.RoomFileIdStore
import com.kirdev.kiromindbot.feature.terminal.domain.EventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.preferencesDataStore
import com.kirdev.kiromindbot.core.service.BotService
import com.kirdev.kiromindbot.feature.settings.data.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

/** Settings DataStore; owner ids from the earlier SharedPreferences file are carried over once. */
private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { ctx -> listOf(SharedPreferencesMigration(ctx, "kiromind_settings")) }
)

/** Process-wide singletons; shared by the UI and the bot service, survive Activity recreation. */
class AppGraph private constructor(context: Context) {
    /** A failed background job is logged instead of crashing the whole app (and the bot with it). */
    private val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e ->
        main.post { session.log(EventKind.ERROR, "Фоновая ошибка: ${e::class.simpleName}") }
    })
    private val database = KiroMindDatabase.create(context)
    private val storagePrefs = context.getSharedPreferences("kiromind_storage", Context.MODE_PRIVATE)
    private val appContext = context
    private val main = Handler(Looper.getMainLooper())

    val session = BotSession()

    val answers = RoomAnswerRepository(
        dao = database.answers(),
        scope = scope,
        seed = DemoAnswers.seed,
        isSeeded = { storagePrefs.getBoolean(KEY_SEEDED, false) },
        markSeeded = { storagePrefs.edit().putBoolean(KEY_SEEDED, true).apply() }
    )
    val unknownQuestions = RoomUnknownQuestionStore(database.unknownQuestions(), scope)

    private val settings = SettingsRepository(appContext.settingsStore, scope)
    val preferences: StateFlow<AppPreferences> = settings.preferences

    fun updatePreferences(value: AppPreferences) = settings.update(value)

    /** Turns the bot on or off from the UI and remembers the choice for restarts and updates. */
    fun setBotRunning(on: Boolean) {
        session.setRunning(on)
        settings.setBotEnabled(on)
        if (on) {
            scope.launch { if (telegram.hasStoredToken()) main.post { BotService.start(appContext) } }
        } else {
            BotService.stop(appContext)
        }
    }

    /**
     * Brings the bot back if the user left it on (app opened again, or the app was updated).
     * Does nothing without a stored token. Returns whether the bot was started.
     */
    suspend fun restoreBot(reason: String): Boolean {
        if (session.running) return false
        // Each skip is logged, so "why didn't the bot come back?" is answered by the journal.
        if (!settings.isBotEnabled()) {
            main.post { session.log(EventKind.SYSTEM, "Бот выключен владельцем — автозапуск не нужен") }
            return false
        }
        if (!telegram.hasStoredToken()) {
            main.post { session.log(EventKind.WARNING, "Бот был включён, но токен не найден — введите его в настройках") }
            return false
        }
        main.post {
            session.setRunning(true, reason)
            BotService.start(appContext)
        }
        return true
    }

    fun behavior(): BehaviorSettings = settings.preferences.value.let { p ->
        BehaviorSettings(
            contextEnabled = p.contextEnabled,
            persona = p.persona,
            groupsEnabled = p.groupsEnabled,
            groupPrefix = p.groupPrefix,
            logUnknown = p.logUnknown,
            antiSpam = p.antiSpam,
            autoBan = p.autoBan,
            externalAiEnabled = p.externalAiEnabled,
            shareContextWithAi = p.shareContextWithAi,
            aiDailyLimit = p.aiDailyLimit,
            ownerIds = parseOwners(p.ownerIds),
            mentionsEnabled = p.mentionsEnabled,
            verbose = p.diagnosticLogs
        )
    }

    private val diagnostics = DiagnosticSink { event ->
        val kind = when (event.level) {
            DiagnosticEvent.Level.INFO -> EventKind.SYSTEM
            DiagnosticEvent.Level.SUCCESS -> EventKind.SUCCESS
            DiagnosticEvent.Level.WARNING -> EventKind.WARNING
            DiagnosticEvent.Level.ERROR -> EventKind.ERROR
        }
        // Compose state must be touched on the main thread.
        main.post { session.log(kind, event.message) }
    }

    private val engine = FuzzyMindCore()

    // Rebuild the search index in the background whenever the base changes, so a message never
    // waits for it (~1.5 s for 15k answers on a mid-range phone). collectLatest drops stale builds.
    init {
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            answers.entries.collectLatest { engine.indexFor(it) }
        }
    }
    private val secrets = KeystoreSecretStore(context)
    /** Routes to the provider selected in settings (Gemini, Claude, YandexGPT or OpenAI-compatible). */
    val ai = AiRouter(secrets) { settings.preferences.value }
    /** One quota for the whole app, shared by the test chat and Telegram. */
    private val aiQuota = DailyQuota()
    private val aiLimiter = RateLimiter(perMinute = 20)

    val blacklist = RoomBlacklist(database.blockedUsers(), scope)
    private val antiSpam = AntiSpam()

    private fun pipeline(isRunning: () -> Boolean) = MessagePipeline(
        engine = engine,
        answers = { answers.entries.value },
        selector = ResponseSelector(),
        memory = ConversationMemory(),
        unknown = unknownQuestions,
        diagnostics = diagnostics,
        isRunning = isRunning,
        externalAi = ai,
        aiLimiter = aiLimiter,
        aiQuota = aiQuota,
        blacklist = blacklist,
        antiSpam = antiSpam,
        // Lets the bot deliver its confirmation before the service stops.
        onStopRequested = { main.postDelayed({ setBotRunning(false) }, 1_500) }
    )

    /** Local test chat: always on, independent of the Telegram switch. */
    val testPipeline = pipeline { true }

    val telegram = TelegramController(
        secrets = secrets,
        scope = scope,
        pipeline = pipeline { session.running },
        settings = ::behavior,
        onTeach = ::teach,
        diagnostics = diagnostics,
        apiFactory = { token -> HttpTelegramBotApi(token, AndroidLocalFiles(context.contentResolver)) },
        fileIds = RoomFileIdStore(database.telegramFiles()),
        // A new token restarts the poller if the bot is switched on.
        onTokenSaved = { if (session.running) main.post { BotService.start(appContext) } }
    )

    fun aiUsedToday() = aiQuota.usedToday()

    suspend fun saveAiKey(kind: AiKind, raw: String): String? = ai.saveKey(kind, raw)

    /** Model list or a short error text. */
    suspend fun listAiModels(kind: AiKind): Result<List<String>> = try {
        Result.success(ai.listModels(kind).orEmpty())
    } catch (e: com.kirdev.kiromindbot.feature.engine.domain.AiFailure) {
        Result.failure(e)
    }

    /** Deleting the active provider's key also switches the external AI off. */
    suspend fun deleteAiKey(kind: AiKind) {
        ai.deleteKey(kind)
        if (kind == ai.active) updatePreferences(preferences.value.copy(externalAiEnabled = false))
    }

    init { scope.launch { ai.refreshKeys() } }

    val backups = com.kirdev.kiromindbot.feature.backup.data.BackupManager(context, answers, BuildConfig.VERSION_NAME, scope)

    init {
        scope.launch {
            telegram.status.collect { s ->
                if (s is com.kirdev.kiromindbot.feature.telegram.api.ConnectionStatus.Error && settings.preferences.value.notifications)
                    BotService.alert(appContext, s.summary)
            }
        }
    }

    /** Daily local copy of the answer base, once it has been loaded from disk. */
    fun autoBackup() = scope.launch {
        answers.loaded.first { it }
        runCatching { backups.autoBackupIfDue() }
    }

    val updates = UpdateManager(context, SemVer.parse(BuildConfig.VERSION_NAME) ?: SemVer(1, 0, 0))

    /** Daily background check on app start; logs only the version found, never anything else. */
    fun checkUpdatesIfDue() = scope.launch {
        updates.checkIfDue()
        (updates.state.value as? UpdateManager.State.Result)?.check?.let { c ->
            if (c is UpdateCheck.Available) main.post { session.log(EventKind.SYSTEM, "Доступно обновление ${c.release.tag}") }
        }
    }

    /** Saves a pair taught by an authorized owner through /teach. */
    private fun teach(question: String, answer: String): AnswerEntry? {
        val entry = AnswerEntry((answers.entries.value.maxOfOrNull { it.id } ?: 0L) + 1, question, answer)
        return runCatching { answers.upsert(entry) }.map { entry }.getOrNull()
    }

    companion object {
        private const val KEY_SEEDED = "demo_seeded_v1"
        @Volatile private var instance: AppGraph? = null

        fun parseOwners(text: String): Set<Long> =
            text.split(',', ' ', ';', '\n').mapNotNull { it.trim().toLongOrNull() }.toSet()

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }
    }
}
