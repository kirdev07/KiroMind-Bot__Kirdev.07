package com.kirdev.kiromindbot.feature.backup.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.kirdev.kiromindbot.feature.answers.data.AnswerRepository
import com.kirdev.kiromindbot.feature.backup.domain.BackupFormat
import com.kirdev.kiromindbot.feature.backup.domain.ImportMode
import com.kirdev.kiromindbot.feature.backup.domain.ImportPlan
import com.kirdev.kiromindbot.feature.backup.domain.ImportPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export/import of the answer base and automatic local backups in app-private storage
 * (files/backups, excluded from Android backup like the rest of the app data).
 */
class BackupManager(
    private val context: Context,
    private val answers: AnswerRepository,
    private val appVersion: String,
    /** App-lifetime scope: an export must finish even if the screen is recreated meanwhile. */
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val keep: Int = 7,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val dir get() = File(context.filesDir, "backups").apply { mkdirs() }

    data class LocalBackup(val file: File, val createdAt: Long, val sizeBytes: Long)

    data class UiState(
        val busy: Boolean = false,
        val message: String? = null,
        val pending: BackupFormat.Decoded.Ok? = null,
        val local: List<LocalBackup> = emptyList()
    )

    private val uiFlow = kotlinx.coroutines.flow.MutableStateFlow(UiState())
    val ui: kotlinx.coroutines.flow.StateFlow<UiState> = uiFlow

    fun setPending(d: BackupFormat.Decoded.Ok?) = uiFlow.update { it.copy(pending = d) }
    fun clearMessage() = uiFlow.update { it.copy(message = null) }
    suspend fun refresh() { val l = list(); uiFlow.update { it.copy(local = l) } }

    /** Runs one operation at a time; the result text is shown by whichever screen is visible. */
    fun run(block: suspend () -> String) {
        if (uiFlow.value.busy) return
        uiFlow.update { it.copy(busy = true, message = null) }
        scope.launch {
            val text = try {
                // A stuck provider must not leave the screen spinning forever.
                kotlinx.coroutines.withTimeout(30_000) { block() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                "Операция не завершилась за 30 с — попробуйте другую папку"
            } catch (e: Exception) {
                "Ошибка: ${e.message ?: e::class.simpleName}"
            }
            uiFlow.update { it.copy(busy = false, message = text) }
            refresh()
        }
    }

    fun suggestedFileName(): String =
        "kiromind-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(clock())) + ".kmbackup.json"

    /** Writes the whole base to a user-chosen document. Returns the number of answers written. */
    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val list = answers.entries.value
        Log.d(TAG, "export: open")
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("нет доступа к файлу")
        Log.d(TAG, "export: write")
        out.use { it.write(BackupFormat.encode(list, appVersion, clock()).toByteArray(Charsets.UTF_8)) }
        Log.d(TAG, "export: closed, ${list.size} entries")
        list.size
    }

    /** Reads and validates a file; nothing is changed yet. */
    suspend fun read(uri: Uri): BackupFormat.Decoded = withContext(Dispatchers.IO) {
        val text = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(MAX_FILE_BYTES + 1)
                if (bytes.size > MAX_FILE_BYTES) return@withContext BackupFormat.Decoded.Error("файл больше ${MAX_FILE_BYTES shr 20} МБ")
                String(bytes, Charsets.UTF_8)
            } ?: return@withContext BackupFormat.Decoded.Error("нет доступа к файлу")
        } catch (e: IOException) {
            return@withContext BackupFormat.Decoded.Error("не удалось прочитать файл")
        } catch (e: SecurityException) {
            return@withContext BackupFormat.Decoded.Error("нет доступа к файлу")
        }
        BackupFormat.decode(text)
    }

    fun plan(decoded: BackupFormat.Decoded.Ok, mode: ImportMode): ImportPlan =
        ImportPlanner.plan(answers.entries.value, decoded.entries, mode)

    /** Applies a plan. A safety copy of the current base is written first, always. */
    suspend fun apply(plan: ImportPlan) {
        backupNow("перед импортом")
        answers.replaceAll(plan.result)
    }

    /** Daily automatic copy; skipped when the base is empty or a copy from today exists. */
    suspend fun autoBackupIfDue() {
        if (answers.entries.value.isEmpty()) return
        val latest = list().firstOrNull()
        if (latest != null && clock() - latest.createdAt < DAY) return
        backupNow("авто")
    }

    suspend fun backupNow(@Suppress("UNUSED_PARAMETER") reason: String): File = withContext(Dispatchers.IO) {
        val name = "backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock())) + ".kmbackup.json"
        val file = File(dir, name)
        // Write to a temp file first so a crash never leaves a half-written backup.
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(BackupFormat.encode(answers.entries.value, appVersion, clock()), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("не удалось сохранить копию") }
        list().drop(keep).forEach { it.file.delete() }
        file
    }

    suspend fun list(): List<LocalBackup> = withContext(Dispatchers.IO) {
        Log.d(TAG, "list")
        dir.listFiles { f -> f.name.endsWith(".kmbackup.json") }.orEmpty()
            .map { LocalBackup(it, it.lastModified(), it.length()) }
            .sortedByDescending { it.createdAt }
    }

    suspend fun readLocal(backup: LocalBackup): BackupFormat.Decoded = withContext(Dispatchers.IO) {
        if (backup.file.parentFile?.canonicalPath != dir.canonicalPath) return@withContext BackupFormat.Decoded.Error("не та папка")
        BackupFormat.decode(backup.file.readText(Charsets.UTF_8))
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_FILE_BYTES = 50 shl 20
        private const val TAG = "KiroBackup"
        private const val DAY = 24 * 3600_000L
    }
}
