package com.kirdev.kiromindbot.feature.security

/**
 * Secrets such as the bot token. Implementations are fail-closed: if a value cannot be
 * encrypted or decrypted it is reported as missing/failed, never stored or returned in plaintext.
 */
interface SecretStore {
    suspend fun save(name: String, secret: String): Boolean
    suspend fun read(name: String): SecretRead
    suspend fun delete(name: String)
}

sealed interface SecretRead {
    data class Value(val secret: String) : SecretRead
    data object Missing : SecretRead
    /** Stored but unreadable (key invalidated, data damaged). The user has to enter it again. */
    data object Unreadable : SecretRead
}

object SecretNames {
    const val TELEGRAM_TOKEN = "telegram_bot_token"
    const val ANTHROPIC_KEY = "anthropic_api_key"
}
