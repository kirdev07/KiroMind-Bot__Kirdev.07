package com.kirdev.kiromindbot.feature.telegram.data

import com.kirdev.kiromindbot.feature.telegram.api.BotIdentity
import com.kirdev.kiromindbot.feature.telegram.api.MediaFile
import com.kirdev.kiromindbot.feature.telegram.api.TgMediaMethod
import com.kirdev.kiromindbot.feature.telegram.api.OutgoingAction
import com.kirdev.kiromindbot.feature.telegram.api.TelegramApiException
import com.kirdev.kiromindbot.feature.telegram.api.TgMessage
import com.kirdev.kiromindbot.feature.telegram.api.TgUpdate
import org.json.JSONObject

/** Bot API JSON encoding/decoding, kept free of I/O so it can be unit-tested. */
object TelegramJson {

    /** Returns `result` of a successful response or throws [TelegramApiException]. */
    fun result(body: String): Any {
        val root = JSONObject(body)
        if (!root.optBoolean("ok")) {
            throw TelegramApiException(
                root.optInt("error_code", 0),
                root.optString("description", "unknown error").take(200),
                root.optJSONObject("parameters")?.optInt("retry_after")?.takeIf { it > 0 }
            )
        }
        return root.get("result")
    }

    fun identity(result: Any): BotIdentity {
        val o = result as JSONObject
        return BotIdentity(o.getLong("id"), o.optString("username"))
    }

    /** Skips anything that is not a plain new message; a malformed item never breaks the batch. */
    fun updates(result: Any): List<TgUpdate> {
        val array = result as org.json.JSONArray
        return (0 until array.length()).mapNotNull { i ->
            val u = array.optJSONObject(i) ?: return@mapNotNull null
            val id = u.optLong("update_id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            TgUpdate(id, u.optJSONObject("message")?.let { runCatching { message(it) }.getOrNull() })
        }
    }

    private fun message(m: JSONObject): TgMessage {
        val chat = m.getJSONObject("chat")
        val from = m.optJSONObject("from")
        val text = m.optString("text").takeIf { m.has("text") } ?: m.optString("caption").takeIf { m.has("caption") }
        val entities = m.optJSONArray("entities") ?: m.optJSONArray("caption_entities")
        val mentions = buildList {
            if (entities != null && text != null) for (i in 0 until entities.length()) {
                val e = entities.optJSONObject(i) ?: continue
                if (e.optString("type") != "mention") continue
                val start = e.optInt("offset"); val end = start + e.optInt("length")
                // Offsets are UTF-16 code units, which is what Kotlin strings use.
                if (start in 0..end && end <= text.length) add(text.substring(start, end).removePrefix("@"))
            }
        }
        return TgMessage(
            messageId = m.getLong("message_id"),
            chatId = chat.getLong("id"),
            chatType = chat.optString("type"),
            fromId = from?.optLong("id"),
            fromIsBot = from?.optBoolean("is_bot") ?: false,
            text = text,
            mentions = mentions,
            replyToFromId = m.optJSONObject("reply_to_message")?.optJSONObject("from")?.optLong("id")
        )
    }

    /**
     * file_id of the media in a sent message. Photos come as several sizes: the largest is last.
     * Telegram may answer an animation as "document" too, so that is checked as a fallback.
     */
    fun sentFileId(result: Any, method: TgMediaMethod): String? {
        val m = result as? JSONObject ?: return null
        if (method == TgMediaMethod.PHOTO) {
            val sizes = m.optJSONArray("photo") ?: return null
            return sizes.optJSONObject(sizes.length() - 1)?.optString("file_id")?.takeIf { it.isNotEmpty() }
        }
        return (m.optJSONObject(method.field) ?: m.optJSONObject("document"))?.optString("file_id")?.takeIf { it.isNotEmpty() }
    }

    /** Method name and JSON body for an outgoing action. */
    fun request(action: OutgoingAction): Pair<String, JSONObject> {
        val body = JSONObject().put("chat_id", action.chatId)
        return when (action) {
            is OutgoingAction.Text -> {
                body.put("text", action.text)
                action.replyTo?.let { body.put("reply_parameters", JSONObject().put("message_id", it).put("allow_sending_without_reply", true)) }
                "sendMessage" to body
            }
            is OutgoingAction.Media -> {
                val file = action.file
                require(file is MediaFile.Remote) { "local files go through multipart upload" }
                body.put(action.method.field, file.ref)
                action.caption?.let { body.put("caption", it) }
                action.replyTo?.let { body.put("reply_parameters", JSONObject().put("message_id", it).put("allow_sending_without_reply", true)) }
                action.method.method to body
            }
        }
    }
}
