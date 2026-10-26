package com.kirdev.kiromindbot.feature.answers.data.local

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle
import com.kirdev.kiromindbot.feature.answers.domain.ReplyVariant

/** Rows of one answer, ready to be written in a single transaction. */
data class AnswerRows(val answer: AnswerEntity, val variants: List<VariantEntity>, val media: List<MediaEntity>)

fun AnswerEntry.toRows(position: Int): AnswerRows {
    fun mediaRows(items: List<AnswerMedia>, variantId: String?) = items.mapIndexed { i, m ->
        MediaEntity(m.id, id, variantId, m.kind.name, m.displayName, m.source, m.mimeType, i)
    }
    return AnswerRows(
        AnswerEntity(id, question, reply, favorite, afterEntryId, position),
        alternatives.mapIndexed { i, v -> VariantEntity(v.id, id, v.text, v.style.name, v.weight, i) },
        mediaRows(media, null) + alternatives.flatMap { mediaRows(it.media, it.id) }
    )
}

/**
 * Rebuilds domain entries. Unknown enum names (a newer or damaged row) degrade
 * to safe defaults instead of failing the whole load.
 */
fun assemble(answers: List<AnswerEntity>, variants: List<VariantEntity>, media: List<MediaEntity>): List<AnswerEntry> {
    val variantsByAnswer = variants.groupBy { it.answerId }
    val mediaByOwner = media.groupBy { it.answerId to it.variantId }
    fun mediaOf(answerId: Long, variantId: String?) = mediaByOwner[answerId to variantId].orEmpty()
        .sortedBy { it.sendOrder }
        .mapNotNull { row ->
            val kind = MediaKind.entries.firstOrNull { it.name == row.kind } ?: return@mapNotNull null
            AnswerMedia(row.id, kind, row.displayName, row.source, row.mimeType, row.sendOrder)
        }
    return answers.sortedBy { it.position }.map { a ->
        AnswerEntry(
            id = a.id,
            question = a.question,
            reply = a.reply,
            media = mediaOf(a.id, null),
            favorite = a.favorite,
            alternatives = variantsByAnswer[a.id].orEmpty().sortedBy { it.position }.map { v ->
                ReplyVariant(v.id, v.text, mediaOf(a.id, v.id),
                    ReplyStyle.entries.firstOrNull { it.name == v.style } ?: ReplyStyle.NEUTRAL, v.weight)
            },
            afterEntryId = a.afterEntryId
        )
    }
}
