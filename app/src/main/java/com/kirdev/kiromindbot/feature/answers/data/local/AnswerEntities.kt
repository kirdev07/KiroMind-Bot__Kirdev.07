package com.kirdev.kiromindbot.feature.answers.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One question. Its primary reply text lives here; extra replies are rows in [VariantEntity]. */
@Entity(tableName = "answers")
data class AnswerEntity(
    @PrimaryKey val id: Long,
    val question: String,
    val reply: String,
    val favorite: Boolean,
    /** Follow-up link; not a foreign key so deleting the parent keeps the child editable. */
    @ColumnInfo(name = "after_entry_id") val afterEntryId: Long?,
    /** Display order in the base list (0 = top). */
    val position: Int,
    @ColumnInfo(defaultValue = "") val category: String = "",
    /** Exclusion phrases, one per line. */
    @ColumnInfo(defaultValue = "") val exclusions: String = ""
)

@Entity(
    tableName = "reply_variants",
    foreignKeys = [ForeignKey(AnswerEntity::class, ["id"], ["answer_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("answer_id")]
)
data class VariantEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "answer_id") val answerId: Long,
    val text: String,
    /** [com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle] name; unknown values read as NEUTRAL. */
    val style: String,
    val weight: Int,
    val position: Int
)

/** Attachment metadata. [variantId] null means the primary reply. Files themselves are never stored here. */
@Entity(
    tableName = "answer_media",
    foreignKeys = [ForeignKey(AnswerEntity::class, ["id"], ["answer_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("answer_id")]
)
data class MediaEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "answer_id") val answerId: Long,
    @ColumnInfo(name = "variant_id") val variantId: String?,
    val kind: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val source: String,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "send_order") val sendOrder: Int
)
