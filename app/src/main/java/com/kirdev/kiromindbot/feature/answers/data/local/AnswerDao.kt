package com.kirdev.kiromindbot.feature.answers.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface AnswerDao {
    @Query("SELECT * FROM answers") suspend fun answers(): List<AnswerEntity>
    @Query("SELECT * FROM reply_variants") suspend fun variants(): List<VariantEntity>
    @Query("SELECT * FROM answer_media") suspend fun media(): List<MediaEntity>
    @Query("SELECT COUNT(*) FROM answers") suspend fun count(): Int
    @Query("SELECT COALESCE(MIN(position), 0) FROM answers") suspend fun minPosition(): Int
    @Query("SELECT position FROM answers WHERE id = :id") suspend fun positionOf(id: Long): Int?

    @Upsert suspend fun upsertAnswer(answer: AnswerEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVariants(rows: List<VariantEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMedia(rows: List<MediaEntity>)
    @Query("DELETE FROM reply_variants WHERE answer_id = :id") suspend fun deleteVariants(id: Long)
    @Query("DELETE FROM answer_media WHERE answer_id = :id") suspend fun deleteMedia(id: Long)
    @Query("DELETE FROM answers WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE answers SET favorite = NOT favorite WHERE id = :id") suspend fun toggleFavorite(id: Long)

    /** Replaces an answer with all its variants and media atomically. */
    @Transaction
    suspend fun replace(rows: AnswerRows) {
        // Upsert, not REPLACE: REPLACE would delete the parent row and cascade.
        upsertAnswer(rows.answer)
        deleteVariants(rows.answer.id)
        deleteMedia(rows.answer.id)
        insertVariants(rows.variants)
        insertMedia(rows.media)
    }

    /** Children are removed explicitly too, so deletion never depends on the foreign_keys pragma. */
    @Transaction
    suspend fun deleteWithChildren(id: Long) {
        deleteVariants(id)
        deleteMedia(id)
        delete(id)
    }

    @Transaction
    suspend fun replaceAll(all: List<AnswerRows>) = all.forEach { replace(it) }
}
