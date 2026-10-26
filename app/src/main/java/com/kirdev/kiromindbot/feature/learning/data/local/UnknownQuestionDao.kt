package com.kirdev.kiromindbot.feature.learning.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "unknown_questions", indices = [Index("last_seen")])
data class UnknownQuestionEntity(
    /** Normalized text; merges repeats. */
    @PrimaryKey val key: String,
    val sample: String,
    val count: Int,
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    @ColumnInfo(name = "last_seen") val lastSeen: Long
)

@Dao
interface UnknownQuestionDao {
    @Query("SELECT * FROM unknown_questions ORDER BY last_seen DESC") suspend fun all(): List<UnknownQuestionEntity>
    @Upsert suspend fun upsert(row: UnknownQuestionEntity)
    @Query("DELETE FROM unknown_questions WHERE `key` = :key") suspend fun delete(key: String)

    /** Keeps only the [capacity] most recently seen questions. */
    @Query("DELETE FROM unknown_questions WHERE `key` NOT IN (SELECT `key` FROM unknown_questions ORDER BY last_seen DESC LIMIT :capacity)")
    suspend fun trim(capacity: Int)
}
