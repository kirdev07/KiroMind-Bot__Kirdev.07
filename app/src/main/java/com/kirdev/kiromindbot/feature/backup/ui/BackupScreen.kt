package com.kirdev.kiromindbot.feature.backup.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.AppMuted
import com.kirdev.kiromindbot.core.ui.DividerLine
import com.kirdev.kiromindbot.core.ui.Label
import com.kirdev.kiromindbot.core.ui.Panel
import com.kirdev.kiromindbot.core.ui.SettingRow
import com.kirdev.kiromindbot.core.ui.plural
import com.kirdev.kiromindbot.feature.backup.data.BackupManager
import com.kirdev.kiromindbot.feature.backup.domain.BackupFormat
import com.kirdev.kiromindbot.feature.backup.domain.ImportMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export, verified import with preview, and local backups. Destructive steps ask for confirmation. */
@Composable
fun BackupScreen(backups: BackupManager, answerCount: Int, onBack: () -> Unit) {
    // Operation state lives in BackupManager, not in this composable: returning from the system
    // file picker can hand the result to a different screen instance than the one on display.
    val ui by backups.ui.collectAsState()
    val busy = ui.busy
    val message = ui.message
    val pending = ui.pending
    val local = ui.local
    val time = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    var confirmReplace by remember { mutableStateOf<BackupFormat.Decoded.Ok?>(null) }
    LaunchedEffect(Unit) { backups.refresh() }

    fun work(block: suspend () -> String) = backups.run(block)

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) work { "Сохранено: " + plural(backups.exportTo(uri), "ответ", "ответа", "ответов") }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            when (val d = backups.read(uri)) {
                is BackupFormat.Decoded.Ok -> { backups.setPending(d); "Файл проверен — выберите, как импортировать" }
                is BackupFormat.Decoded.Error -> d.reason
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
            Column {
                Text("Данные и копии", style = MaterialTheme.typography.titleLarge)
                Text("В базе " + plural(answerCount, "ответ", "ответа", "ответов"), color = AppMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { exporter.launch(backups.suggestedFileName()) }, enabled = !busy) { Text("Экспорт") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, enabled = !busy) { Text("Импорт") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        pending?.let { d ->
            val merge = remember(d) { backups.plan(d, ImportMode.MERGE) }
            Panel {
                Label("Файл от " + (if (d.exportedAt > 0) time.format(Date(d.exportedAt)) else "неизвестной даты"))
                Text("Ответов: ${d.entries.size}" + (if (d.skipped > 0) " · пропущено повреждённых: ${d.skipped}" else ""), style = MaterialTheme.typography.bodySmall)
                if (d.localFiles > 0) Text("Вложений с другого телефона: ${d.localFiles}. Если этих файлов здесь нет, бот их пропустит.", color = AppMuted, style = MaterialTheme.typography.bodySmall)
                DividerLine()
                SettingRow("Объединить", "Добавится ${merge.added}, обновится ${merge.updated}, без изменений ${merge.unchanged}. Остальное останется.")
                Button(onClick = { work { backups.apply(merge); backups.setPending(null); "Импорт выполнен: +${merge.added}, обновлено ${merge.updated}" } }, enabled = !busy) { Text("Объединить") }
                DividerLine()
                SettingRow("Заменить всё", "База станет точной копией файла. Текущие $answerCount ответов будут удалены (копия сохранится).")
                OutlinedButton(onClick = { confirmReplace = d }, enabled = !busy) { Text("Заменить всё…") }
                TextButton(onClick = { backups.setPending(null); backups.clearMessage() }) { Text("Отмена") }
            }
        }

        Label("Резервные копии на телефоне")
        Text("Создаются автоматически раз в сутки и перед каждым импортом. Хранятся последние 7. Токены и ключи в копии не входят.",
            color = AppMuted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { work { backups.backupNow("вручную"); "Копия создана" } }, enabled = !busy && answerCount > 0) { Text("Создать копию сейчас") }
        if (local.isEmpty()) Text("Копий пока нет", color = AppMuted, style = MaterialTheme.typography.bodySmall)
        Panel {
            local.forEachIndexed { i, b ->
                if (i > 0) DividerLine()
                SettingRow(time.format(Date(b.createdAt)), "${b.sizeBytes / 1024} КБ") {
                    TextButton(onClick = {
                        work {
                            when (val d = backups.readLocal(b)) {
                                is BackupFormat.Decoded.Ok -> { confirmReplace = d; "Подтвердите восстановление" }
                                is BackupFormat.Decoded.Error -> d.reason
                            }
                        }
                    }, enabled = !busy) { Text("Восстановить") }
                }
            }
        }
    }

    confirmReplace?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmReplace = null },
            title = { Text("Заменить всю базу?") },
            text = { Text("Текущие $answerCount ответов будут заменены на ${d.entries.size} из копии. Перед этим текущая база сохранится в резервную копию.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = null
                    work { backups.apply(backups.plan(d, ImportMode.REPLACE)); backups.setPending(null); "База заменена: ${d.entries.size} ответов" }
                }) { Text("Заменить") }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = null }) { Text("Отмена") } }
        )
    }
}
