package com.kirdev.kiromindbot.feature.telegram.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.kirdev.kiromindbot.feature.telegram.api.FileIdStore
import com.kirdev.kiromindbot.feature.telegram.api.LocalFile
import com.kirdev.kiromindbot.feature.telegram.api.LocalFiles
import com.kirdev.kiromindbot.feature.telegram.api.TgMediaMethod
import com.kirdev.kiromindbot.feature.telegram.data.local.FileIdDao
import com.kirdev.kiromindbot.feature.telegram.data.local.FileIdEntity

/** Reads files the user picked through the system picker (persisted URI permission). */
class AndroidLocalFiles(private val resolver: ContentResolver) : LocalFiles {
    override fun open(uri: String): LocalFile? = try {
        val parsed = Uri.parse(uri)
        val size = resolver.query(parsed, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
        resolver.openInputStream(parsed)?.let { LocalFile(size, it) }
    } catch (e: Exception) {
        // SecurityException (permission revoked) or FileNotFoundException (file deleted).
        null
    }
}

class RoomFileIdStore(private val dao: FileIdDao, private val clock: () -> Long = System::currentTimeMillis) : FileIdStore {
    override suspend fun get(source: String, method: TgMediaMethod) = dao.get(source, method.name)
    override suspend fun put(source: String, method: TgMediaMethod, fileId: String) =
        dao.put(FileIdEntity(source, method.name, fileId, clock()))
    override suspend fun remove(source: String, method: TgMediaMethod) = dao.remove(source, method.name)
}
