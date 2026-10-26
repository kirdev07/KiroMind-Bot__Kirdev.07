package com.kirdev.kiromindbot.feature.settings.domain

import com.kirdev.kiromindbot.feature.engine.domain.Persona

/** User settings; persisted by SettingsRepository (DataStore). */
data class AppPreferences(
    val contextEnabled: Boolean = true,
    val persona: Persona = Persona.NEUTRAL,
    val groupsEnabled: Boolean = false,
    val mentionsEnabled: Boolean = true,
    val antiSpam: Boolean = true,
    val autoBan: Boolean = false,
    val logUnknown: Boolean = true,
    val diagnosticLogs: Boolean = false,
    val notifications: Boolean = true,
    val groupPrefix: String = "Бот,",
    /** Telegram user ids allowed to use /teach, comma separated. */
    val ownerIds: String = "",
    /** Claude answers questions MindCore could not. Off until the owner turns it on. */
    val externalAiEnabled: Boolean = false,
    /** Send the last few user messages as context; off by default for privacy. */
    val shareContextWithAi: Boolean = false,
    val aiDailyLimit: Int = 50,
    /** [com.kirdev.kiromindbot.feature.ai.data.AiKind] name; Gemini has a free tier. */
    val aiProvider: String = "GEMINI",
    val geminiModel: String = "gemini-flash-latest",
    val yandexFolderId: String = "",
    val yandexModel: String = "yandexgpt-lite/latest",
    val openAiBaseUrl: String = "https://api.groq.com/openai/v1",
    val openAiModel: String = "openai/gpt-oss-20b"
)
