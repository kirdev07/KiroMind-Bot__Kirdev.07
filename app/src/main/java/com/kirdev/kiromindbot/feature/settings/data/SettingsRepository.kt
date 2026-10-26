package com.kirdev.kiromindbot.feature.settings.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.kirdev.kiromindbot.feature.engine.domain.Persona
import com.kirdev.kiromindbot.feature.settings.domain.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import androidx.datastore.preferences.core.emptyPreferences

/**
 * All user settings plus the "bot switched on" flag, in one Preferences DataStore.
 * The UI gets the new value at once; the write follows in the background.
 * No secrets here: the bot token stays in the Keystore-backed SecretStore.
 */
class SettingsRepository(private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    private val state = MutableStateFlow(AppPreferences())
    val preferences: StateFlow<AppPreferences> = state
    private val loadedFlow = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = loadedFlow

    init {
        scope.launch {
            val stored = try { store.data.first() } catch (e: IOException) { emptyPreferences() }
            // Changes made in the UI before the first read win over the stored copy.
            if (!loadedFlow.value) state.value = SettingsKeys.read(stored)
            loadedFlow.value = true
        }
    }

    fun update(value: AppPreferences) {
        state.value = value
        loadedFlow.value = true
        // Writes the latest state, not the captured one, so out-of-order launches cannot lose an edit.
        save { SettingsKeys.write(it, state.value) }
    }

    suspend fun isBotEnabled(): Boolean = try { store.data.first()[SettingsKeys.BOT_ENABLED] ?: false } catch (e: IOException) { false }

    fun setBotEnabled(enabled: Boolean) {
        save { it[SettingsKeys.BOT_ENABLED] = enabled }
    }

    /** A failed disk write must not crash the app: the in-memory value stays and the next edit retries. */
    private fun save(block: (MutablePreferences) -> Unit) {
        scope.launch {
            try { store.edit { block(it) } } catch (e: IOException) { /* keep running */ }
        }
    }
}

/** Key names are stable storage format; renaming one needs a migration. */
object SettingsKeys {
    val BOT_ENABLED = booleanPreferencesKey("bot_enabled")
    /** Same name as the old SharedPreferences key, so SharedPreferencesMigration carries it over. */
    val OWNER_IDS = stringPreferencesKey("telegram_owner_ids")
    private val CONTEXT = booleanPreferencesKey("context_enabled")
    private val AUTO_MOOD = booleanPreferencesKey("auto_mood")
    private val PERSONA = stringPreferencesKey("persona")
    private val GROUPS = booleanPreferencesKey("groups_enabled")
    private val MENTIONS = booleanPreferencesKey("mentions_enabled")
    private val ANTI_SPAM = booleanPreferencesKey("anti_spam")
    private val AUTO_BAN = booleanPreferencesKey("auto_ban")
    private val LOG_UNKNOWN = booleanPreferencesKey("log_unknown")
    private val DIAGNOSTIC_LOGS = booleanPreferencesKey("diagnostic_logs")
    private val NOTIFICATIONS = booleanPreferencesKey("notifications")
    private val GROUP_PREFIX = stringPreferencesKey("group_prefix")
    private val LOG_RETENTION = stringPreferencesKey("log_retention_days")
    private val AI_ENABLED = booleanPreferencesKey("external_ai_enabled")
    private val AI_CONTEXT = booleanPreferencesKey("share_context_with_ai")
    private val AI_DAILY = intPreferencesKey("ai_daily_limit")
    private val AI_PROVIDER = stringPreferencesKey("ai_provider")
    private val GEMINI_MODEL = stringPreferencesKey("gemini_model")
    private val YANDEX_FOLDER = stringPreferencesKey("yandex_folder_id")
    private val YANDEX_MODEL = stringPreferencesKey("yandex_model")
    private val OPENAI_URL = stringPreferencesKey("openai_base_url")
    private val OPENAI_MODEL = stringPreferencesKey("openai_model")

    /** Missing or unknown values fall back to defaults. */
    fun read(p: Preferences): AppPreferences {
        val d = AppPreferences()
        return AppPreferences(
            contextEnabled = p[CONTEXT] ?: d.contextEnabled,
            autoMood = p[AUTO_MOOD] ?: d.autoMood,
            persona = Persona.entries.firstOrNull { it.name == p[PERSONA] } ?: d.persona,
            groupsEnabled = p[GROUPS] ?: d.groupsEnabled,
            mentionsEnabled = p[MENTIONS] ?: d.mentionsEnabled,
            antiSpam = p[ANTI_SPAM] ?: d.antiSpam,
            autoBan = p[AUTO_BAN] ?: d.autoBan,
            logUnknown = p[LOG_UNKNOWN] ?: d.logUnknown,
            diagnosticLogs = p[DIAGNOSTIC_LOGS] ?: d.diagnosticLogs,
            notifications = p[NOTIFICATIONS] ?: d.notifications,
            groupPrefix = p[GROUP_PREFIX] ?: d.groupPrefix,
            ownerIds = p[OWNER_IDS] ?: d.ownerIds,
            logRetentionDays = p[LOG_RETENTION] ?: d.logRetentionDays,
            externalAiEnabled = p[AI_ENABLED] ?: d.externalAiEnabled,
            shareContextWithAi = p[AI_CONTEXT] ?: d.shareContextWithAi,
            aiDailyLimit = (p[AI_DAILY] ?: d.aiDailyLimit).coerceIn(1, 1000),
            aiProvider = p[AI_PROVIDER] ?: d.aiProvider,
            geminiModel = p[GEMINI_MODEL] ?: d.geminiModel,
            yandexFolderId = p[YANDEX_FOLDER] ?: d.yandexFolderId,
            yandexModel = p[YANDEX_MODEL] ?: d.yandexModel,
            openAiBaseUrl = p[OPENAI_URL] ?: d.openAiBaseUrl,
            openAiModel = p[OPENAI_MODEL] ?: d.openAiModel
        )
    }

    fun write(p: MutablePreferences, v: AppPreferences) {
        p[CONTEXT] = v.contextEnabled
        p[AUTO_MOOD] = v.autoMood
        p[PERSONA] = v.persona.name
        p[GROUPS] = v.groupsEnabled
        p[MENTIONS] = v.mentionsEnabled
        p[ANTI_SPAM] = v.antiSpam
        p[AUTO_BAN] = v.autoBan
        p[LOG_UNKNOWN] = v.logUnknown
        p[DIAGNOSTIC_LOGS] = v.diagnosticLogs
        p[NOTIFICATIONS] = v.notifications
        p[GROUP_PREFIX] = v.groupPrefix
        p[OWNER_IDS] = v.ownerIds
        p[LOG_RETENTION] = v.logRetentionDays
        p[AI_ENABLED] = v.externalAiEnabled
        p[AI_CONTEXT] = v.shareContextWithAi
        p[AI_DAILY] = v.aiDailyLimit
        p[AI_PROVIDER] = v.aiProvider
        p[GEMINI_MODEL] = v.geminiModel
        p[YANDEX_FOLDER] = v.yandexFolderId
        p[YANDEX_MODEL] = v.yandexModel
        p[OPENAI_URL] = v.openAiBaseUrl
        p[OPENAI_MODEL] = v.openAiModel
    }
}
