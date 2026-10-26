package com.kirdev.kiromindbot.feature.telegram.api

/** The subset of the Telegram Bot API KiroMind uses. Implementations never log the token or message text. */
interface TelegramBotApi {
    suspend fun getMe(): BotIdentity
    /** Long polling; [timeoutSeconds] 0 returns immediately. */
    suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate>
    /** Returns the file_id Telegram assigned when [action] sent media, otherwise null. */
    suspend fun send(action: OutgoingAction): String?
}

/** Where a media item comes from. */
sealed interface MediaFile {
    /** A Telegram file_id or an https URL; Telegram fetches it, nothing is uploaded. */
    data class Remote(val ref: String) : MediaFile
    /** A user-picked file (content:// URI) uploaded from the phone. */
    data class Local(val uri: String, val fileName: String, val mimeType: String?) : MediaFile
}

/** Opens user-picked files; implemented with ContentResolver so no storage permission is needed. */
interface LocalFiles {
    /** Null when the file is gone or the permission was revoked. */
    fun open(uri: String): LocalFile?
}

class LocalFile(val size: Long?, val stream: java.io.InputStream)

data class BotIdentity(val id: Long, val username: String)

data class TgUpdate(val updateId: Long, val message: TgMessage?)

data class TgMessage(
    val messageId: Long,
    val chatId: Long,
    /** "private", "group", "supergroup" or "channel". */
    val chatType: String,
    val fromId: Long?,
    val fromIsBot: Boolean,
    val text: String?,
    /** Usernames from "mention" entities, without '@'. */
    val mentions: List<String>,
    val replyToFromId: Long?
)

/** [maxUploadBytes]: Bot API limits for multipart uploads. */
enum class TgMediaMethod(val method: String, val field: String, val maxUploadBytes: Long) {
    PHOTO("sendPhoto", "photo", 10L shl 20),
    ANIMATION("sendAnimation", "animation", 50L shl 20),
    VIDEO("sendVideo", "video", 50L shl 20),
    AUDIO("sendAudio", "audio", 50L shl 20),
    VOICE("sendVoice", "voice", 50L shl 20),
    DOCUMENT("sendDocument", "document", 50L shl 20)
}

sealed interface OutgoingAction {
    val chatId: Long
    data class Text(override val chatId: Long, val text: String, val replyTo: Long?) : OutgoingAction
    data class Media(override val chatId: Long, val method: TgMediaMethod, val file: MediaFile, val caption: String?, val replyTo: Long?) : OutgoingAction
}

/** Error reported by Telegram itself. [description] comes from the API and never contains the token. */
class TelegramApiException(val code: Int, val description: String, val retryAfterSeconds: Int?) :
    Exception("Telegram $code: $description")

/** A local file that cannot be sent: missing, permission revoked, or over the Bot API size limit. */
class MediaUnavailableException(val reason: String) : Exception(reason)

/** Network failure. Deliberately carries no message: URLs would contain the token. */
class TelegramNetworkException(val kind: String) : Exception(kind)

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object NoToken : ConnectionStatus
    data object Connecting : ConnectionStatus
    data class Online(val username: String) : ConnectionStatus
    data class Reconnecting(val reason: String) : ConnectionStatus
    data class Error(val summary: String) : ConnectionStatus
}

/** Remembers file_ids of uploaded local files. */
interface FileIdStore {
    suspend fun get(source: String, method: TgMediaMethod): String?
    suspend fun put(source: String, method: TgMediaMethod, fileId: String)
    suspend fun remove(source: String, method: TgMediaMethod)
}
