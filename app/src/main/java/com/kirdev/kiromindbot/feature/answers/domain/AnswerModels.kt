package com.kirdev.kiromindbot.feature.answers.domain

/** A new model with typed media; no VK-era ID strings or pipe-delimited storage. */
enum class MediaKind { PHOTO, GIF, VIDEO, AUDIO, VOICE, DOCUMENT, LINK }

/**
 * Attachment metadata only. [source] is a local URI, a Telegram file id or a link;
 * the engine never opens it — sending is the Telegram module's job.
 */
data class AnswerMedia(
    val id: String,
    val kind: MediaKind,
    val displayName: String,
    val source: String,
    val mimeType: String? = null,
    val order: Int = 0
)

/** Tone of a single reply variant. The bot persona picks among these. */
enum class ReplyStyle { NEUTRAL, FRIENDLY, POLITE, CHEEKY }

data class ReplyVariant(
    val id: String,
    val text: String,
    val media: List<AnswerMedia> = emptyList(),
    val style: ReplyStyle = ReplyStyle.NEUTRAL,
    /** Relative chance among usable variants; values below 1 count as 1. */
    val weight: Int = 1
) {
    val isUsable: Boolean get() = text.isNotBlank() || media.isNotEmpty()
}

/**
 * One question with one or more replies.
 * [reply]/[media] form the primary neutral variant edited in the UI; [alternatives] add more.
 * [afterEntryId] makes the entry a follow-up: it is used only right after the bot answered that entry.
 */
data class AnswerEntry(
    val id: Long,
    val question: String,
    val reply: String,
    val media: List<AnswerMedia> = emptyList(),
    val favorite: Boolean = false,
    val alternatives: List<ReplyVariant> = emptyList(),
    val afterEntryId: Long? = null
) {
    val variants: List<ReplyVariant>
        get() = listOf(ReplyVariant("$id:0", reply, media)) + alternatives

    val isValid: Boolean get() = question.isNotBlank() && variants.any { it.isUsable }
}
