package com.kirdev.kiromindbot.feature.telegram.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * Telegram file_id for a local file already uploaded once, so it is not uploaded again.
 * Keyed by source URI and send method (a photo and a document of the same file get different ids).
 */
@Entity(tableName = "telegram_file_cache", primaryKeys = ["source", "method"])
data class FileIdEntity(
    val source: String,
    val method: String,
    @ColumnInfo(name = "file_id") val fileId: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Long
)

@Dao
interface FileIdDao {
    @Query("SELECT file_id FROM telegram_file_cache WHERE source = :source AND method = :method")
    suspend fun get(source: String, method: String): String?

    @Upsert suspend fun put(row: FileIdEntity)

    @Query("DELETE FROM telegram_file_cache WHERE source = :source AND method = :method")
    suspend fun remove(source: String, method: String)
}
