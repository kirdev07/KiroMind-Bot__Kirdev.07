package com.kirdev.kiromindbot.feature.telegram.domain

import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import com.kirdev.kiromindbot.feature.engine.domain.BotReply
import com.kirdev.kiromindbot.feature.engine.domain.ChatKind
import com.kirdev.kiromindbot.feature.engine.domain.IncomingMessage
import com.kirdev.kiromindbot.feature.telegram.api.BotIdentity
import com.kirdev.kiromindbot.feature.telegram.api.MediaFile
import com.kirdev.kiromindbot.feature.telegram.api.OutgoingAction
import com.kirdev.kiromindbot.feature.telegram.api.TgMediaMethod
import com.kirdev.kiromindbot.feature.telegram.api.TgMessage

/** Telegram message -> transport-neutral MindCore input. Channels and textless messages are dropped. */
fun TgMessage.toIncoming(bot: BotIdentity): IncomingMessage? {
    val kind = when (chatType) {
        "private" -> ChatKind.PRIVATE
        "group", "supergroup" -> ChatKind.GROUP
        else -> return null
    }
    val body = text?.takeIf { it.isNotBlank() } ?: return null
    val sender = fromId ?: return null
    return IncomingMessage(
        chatId = chatId,
        chatKind = kind,
        senderId = sender,
        text = body,
        senderIsBot = fromIsBot,
        fromSelf = sender == bot.id,
        mentionsBot = bot.username.isNotEmpty() && mentions.any { it.equals(bot.username, ignoreCase = true) },
        replyToBot = replyToFromId == bot.id,
        replyToUserId = replyToFromId
    )
}

/** What will be sent and how many demo placeholders were skipped. */
data class ReplyPlan(val actions: List<OutgoingAction>, val skippedMedia: Int)

private const val CAPTION_LIMIT = 1024
private const val TEXT_LIMIT = 4096

/**
 * Turns a MindCore reply into Bot API calls. Media go in their send order;
 * text becomes the first item's caption when it fits, otherwise a separate message.
 * Sends Telegram file ids ("tg-file:<id>"), https URLs and user-picked files (content://);
 * demo placeholders are skipped.
 */
fun planReply(chatId: Long, replyTo: Long?, reply: BotReply): ReplyPlan {
    var text = reply.text.trim()
    val links = reply.media.filter { it.kind == MediaKind.LINK && it.source.startsWith("https://") }
    if (links.isNotEmpty()) text = (listOf(text) + links.map { it.source }).filter { it.isNotEmpty() }.joinToString("\n")
    if (reply.unverified && text.isNotEmpty()) text = "$text\n\n(ответ внешнего ИИ, не проверен владельцем)"

    var skipped = reply.media.count { it.kind == MediaKind.LINK && !it.source.startsWith("https://") }
    val media = reply.media.filter { it.kind != MediaKind.LINK }.mapNotNull { m ->
        val file = when {
            m.source.startsWith("tg-file:") -> MediaFile.Remote(m.source.removePrefix("tg-file:"))
            m.source.startsWith("https://") -> MediaFile.Remote(m.source)
            m.source.startsWith("content://") -> MediaFile.Local(m.source, m.displayName, m.mimeType)
            else -> null
        }
        if (file == null) { skipped++; null } else methodFor(m.kind) to file
    }

    val actions = mutableListOf<OutgoingAction>()
    val captionFits = media.isNotEmpty() && text.isNotEmpty() && text.length <= CAPTION_LIMIT
    if (text.isNotEmpty() && !captionFits) {
        text.chunked(TEXT_LIMIT).forEachIndexed { i, part -> actions += OutgoingAction.Text(chatId, part, replyTo.takeIf { i == 0 }) }
    }
    media.forEachIndexed { i, (method, file) ->
        val first = i == 0
        actions += OutgoingAction.Media(chatId, method, file, text.takeIf { first && captionFits },
            replyTo.takeIf { first && actions.isEmpty() })
    }
    return ReplyPlan(actions, skipped)
}

private fun methodFor(kind: MediaKind) = when (kind) {
    MediaKind.PHOTO -> TgMediaMethod.PHOTO
    MediaKind.GIF -> TgMediaMethod.ANIMATION
    MediaKind.VIDEO -> TgMediaMethod.VIDEO
    MediaKind.AUDIO -> TgMediaMethod.AUDIO
    MediaKind.VOICE -> TgMediaMethod.VOICE
    MediaKind.DOCUMENT, MediaKind.LINK -> TgMediaMethod.DOCUMENT
}
