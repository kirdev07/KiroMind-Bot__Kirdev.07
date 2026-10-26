package com.kirdev.kiromindbot.feature.answers.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.*
import com.kirdev.kiromindbot.core.ui.*
import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle
import com.kirdev.kiromindbot.feature.answers.domain.ReplyVariant
import java.util.UUID
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

/** Editable copy of one extra reply variant. */
private class VariantDraft(val id: String, text: String, style: ReplyStyle, weight: Int, media: List<AnswerMedia>) {
    var text by mutableStateOf(text)
    var style by mutableStateOf(style)
    var weight by mutableIntStateOf(weight.coerceIn(1, 10))
    val media = mutableStateListOf<AnswerMedia>().apply { addAll(media) }
    fun toVariant() = ReplyVariant(id, text.trim(), media.toList(), style, weight)
}

val ReplyStyle.title: String
    get() = when (this) {
        ReplyStyle.NEUTRAL -> "Обычный"
        ReplyStyle.FRIENDLY -> "Дружелюбный"
        ReplyStyle.POLITE -> "Вежливый"
        ReplyStyle.CHEEKY -> "Дерзкий"
    }

/**
 * Answer editor: question, main reply, extra variants with style and weight,
 * and an optional "follow-up" link that makes the entry fire only after another answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnswerEditorScreen(
    existing: AnswerEntry?,
    nextId: Long,
    allAnswers: List<AnswerEntry>,
    presetQuestion: String? = null,
    onSave: (AnswerEntry) -> Unit,
    onBack: () -> Unit
) {
    val entryId = existing?.id ?: nextId
    var question by remember(existing?.id) { mutableStateOf(existing?.question ?: presetQuestion.orEmpty()) }
    var reply by remember(existing?.id) { mutableStateOf(existing?.reply.orEmpty()) }
    val media = remember(existing?.id) { mutableStateListOf<AnswerMedia>().apply { addAll(existing?.media.orEmpty()) } }
    val variants = remember(existing?.id) {
        mutableStateListOf<VariantDraft>().apply {
            existing?.alternatives.orEmpty().forEach { add(VariantDraft(it.id, it.text, it.style, it.weight, it.media)) }
        }
    }
    var afterId by remember(existing?.id) { mutableStateOf(existing?.afterEntryId) }
    var category by remember(existing?.id) { mutableStateOf(existing?.category.orEmpty()) }
    var exclusions by remember(existing?.id) { mutableStateOf(existing?.exclusions.orEmpty().joinToString("\n")) }
    var showPreview by remember { mutableStateOf(false) }
    val valid = question.isNotBlank() && (reply.isNotBlank() || media.isNotEmpty() || variants.any { it.toVariant().isUsable })

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
            Column(Modifier.weight(1f)) {
                Text(if (existing == null) "Новый ответ" else "Редактировать ответ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Варианты, стили и продолжение диалога", color = AppMuted, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { showPreview = !showPreview }) { Icon(Icons.Outlined.Visibility, contentDescription = "Предпросмотр") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(value = question, onValueChange = { question = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Вопрос или фраза") }, minLines = 2, maxLines = 4)

            FollowUpPicker(entryId, afterId, allAnswers) { afterId = it }

            OutlinedTextField(value = category, onValueChange = { category = it.take(40) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Категория") }, singleLine = true, supportingText = { Text("Для группировки в базе, на подбор не влияет") })
            val known = allAnswers.map { it.category }.filter { it.isNotBlank() && it != category }.distinct().take(8)
            if (known.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                known.forEach { c -> AssistChip(onClick = { category = c }, label = { Text(c) }) }
            }
            OutlinedTextField(value = exclusions, onValueChange = { exclusions = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Исключения") }, minLines = 2, maxLines = 5,
                supportingText = { Text("По одной фразе в строке. Если сообщение содержит её целыми словами, этот ответ не сработает") })

            Label("Основной ответ · обычный стиль")
            OutlinedTextField(value = reply, onValueChange = { reply = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ответ бота") }, minLines = 3, maxLines = 6, supportingText = { Text("Может быть пустым, если есть вложения или другие варианты") })
            MediaEditor(media)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Label("Другие варианты (${variants.size})")
                OutlinedButton(onClick = { variants.add(VariantDraft("$entryId:${UUID.randomUUID().toString().take(8)}", "", ReplyStyle.NEUTRAL, 1, emptyList())) }) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(4.dp)); Text("Вариант")
                }
            }
            if (variants.isEmpty()) {
                Text("Добавьте варианты — бот будет выбирать случайно и не повторять один и тот же подряд. Стиль задаёт, при каком характере бота вариант используется.",
                    color = AppMuted, style = MaterialTheme.typography.bodySmall)
            }
            variants.forEachIndexed { index, draft ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Вариант ${index + 2}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { variants.remove(draft) }) { Icon(Icons.Outlined.Delete, contentDescription = "Удалить вариант ${index + 2}") }
                    }
                    OutlinedTextField(value = draft.text, onValueChange = { draft.text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Текст варианта") }, minLines = 2, maxLines = 5)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReplyStyle.entries.forEach { style ->
                            FilterChip(selected = draft.style == style, onClick = { draft.style = style }, label = { Text(style.title) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Вес ${draft.weight}", modifier = Modifier.weight(1f))
                        IconButton(onClick = { draft.weight-- }, enabled = draft.weight > 1) { Icon(Icons.Outlined.Remove, contentDescription = "Уменьшить вес") }
                        IconButton(onClick = { draft.weight++ }, enabled = draft.weight < 10) { Icon(Icons.Outlined.Add, contentDescription = "Увеличить вес") }
                    }
                    Text("Чем больше вес, тем чаще выбирается вариант.", color = AppMuted, style = MaterialTheme.typography.labelSmall)
                    MediaEditor(draft.media)
                }
            }

            if (showPreview) {
                Panel {
                    Text("Предпросмотр", color = AppMuted, style = MaterialTheme.typography.labelSmall)
                    Text("1. " + reply.ifBlank { "только вложения" } + mediaSuffix(media))
                    variants.forEachIndexed { i, d ->
                        Text("${i + 2}. [${d.style.title}, вес ${d.weight}] " + d.text.ifBlank { "только вложения" } + mediaSuffix(d.media),
                            color = AppMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Файлы выбираются через системное окно и загружаются в Telegram при первой отправке (фото до 10 МБ, остальное до 50 МБ). Если удалить файл с телефона, бот его пропустит.", color = AppMuted, style = MaterialTheme.typography.labelSmall)
        }
        Button(
            onClick = {
                val base = existing ?: AnswerEntry(entryId, "", "")
                onSave(base.copy(
                    question = question.trim(),
                    reply = reply.trim(),
                    media = media.toList(),
                    alternatives = variants.map { it.toVariant() }.filter { it.isUsable },
                    afterEntryId = afterId,
                    category = category.trim(),
                    exclusions = exclusions.lines().map { it.trim() }.filter { it.isNotEmpty() }
                ))
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить ответ") }
    }
}

private fun mediaSuffix(media: List<AnswerMedia>) = if (media.isEmpty()) "" else " + ${media.size} влож."

@Composable
private fun FollowUpPicker(selfId: Long, afterId: Long?, all: List<AnswerEntry>, onChange: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // An entry cannot follow itself or one of its own follow-ups.
    val options = all.filter { it.id != selfId && it.afterEntryId != selfId }
    val subtitle = afterId?.let { id ->
        all.firstOrNull { it.id == id }?.let { "Только после ответа на «${it.question}»" } ?: "После записи #$id (удалена)"
    } ?: "Нет — отвечает в любой момент"
    Panel {
        Box {
            SettingRow("Продолжение диалога", subtitle, onClick = { open = true }) {
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = AppMuted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Нет") }, onClick = { onChange(null); open = false })
                options.forEach { e ->
                    DropdownMenuItem(
                        text = { Text("#${e.id} · ${e.question}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { onChange(e.id); open = false }
                    )
                }
            }
        }
        Text("Например, «Тоже хорошо» после «Как дела?». Работает, когда включён контекст диалога.",
            color = AppMuted, style = MaterialTheme.typography.labelSmall)
    }
}

/** Attachment list with demo picker; shared by the main reply and every variant. */
@Composable
private fun MediaEditor(media: SnapshotStateList<AnswerMedia>) {
    var open by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var pendingKind by remember { mutableStateOf(MediaKind.DOCUMENT) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    fun add(item: AnswerMedia) { media.add(item.copy(order = media.size)); open = false }
    // System picker: the app sees only the chosen file, so no storage permission is requested.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = readPicked(context, uri)
        if (picked == null) {
            error = "Не удалось получить доступ к файлу"
        } else {
            error = null
            add(AnswerMedia(UUID.randomUUID().toString(), pendingKind, picked.first, uri.toString(), picked.second))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Вложения (${media.size})", color = AppMuted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open = !open }) { Icon(Icons.Outlined.AttachFile, null); Spacer(Modifier.width(4.dp)); Text("Добавить") }
        }
        media.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(iconFor(item.kind), null, tint = AppMuted)
                Column(Modifier.weight(1f)) {
                    Text(item.displayName, maxLines = 1)
                    Text("${item.kind.name} · ${sourceLabel(item.source)}", color = AppMuted, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { media.remove(item) }) { Icon(Icons.Outlined.Close, contentDescription = "Удалить вложение ${item.displayName}") }
            }
        }
        if (open) {
            Panel {
                Text("Тип медиа", fontWeight = FontWeight.Medium)
                listOf(MediaKind.PHOTO to "Фото", MediaKind.GIF to "GIF", MediaKind.VIDEO to "Видео", MediaKind.AUDIO to "Аудио",
                    MediaKind.VOICE to "Голосовое", MediaKind.DOCUMENT to "Файл").forEach { (kind, label) ->
                    OutlinedButton(onClick = {
                        pendingKind = kind
                        picker.launch(mimeTypesFor(kind))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(iconFor(kind), null); Spacer(Modifier.width(8.dp)); Text("Выбрать: $label")
                    }
                }
                OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("https://...") }, label = { Text("Внешняя ссылка") })
                TextButton(onClick = {
                    add(AnswerMedia(UUID.randomUUID().toString(), MediaKind.LINK, url.trim(), url.trim()))
                    url = ""
                }, enabled = url.trim().startsWith("https://") && url.length > 9) { Text("Добавить ссылку") }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun mimeTypesFor(kind: MediaKind): Array<String> = when (kind) {
    MediaKind.PHOTO -> arrayOf("image/jpeg", "image/png", "image/webp")
    MediaKind.GIF -> arrayOf("image/gif", "video/mp4")
    MediaKind.VIDEO -> arrayOf("video/*")
    MediaKind.AUDIO -> arrayOf("audio/*")
    // Telegram plays voice messages only as OGG/Opus, MP3 or M4A.
    MediaKind.VOICE -> arrayOf("audio/ogg", "audio/opus", "audio/mpeg", "audio/mp4")
    MediaKind.DOCUMENT, MediaKind.LINK -> arrayOf("*/*")
}

private fun sourceLabel(source: String) = when {
    source.startsWith("content://") -> "файл на телефоне"
    source.startsWith("https://") -> "ссылка"
    source.startsWith("tg-file:") -> "файл Telegram"
    else -> "демо, не отправляется"
}

/**
 * Keeps read access across restarts and returns (display name, mime type).
 * Null when the provider refuses access.
 */
private fun readPicked(context: Context, uri: Uri): Pair<String, String?>? = try {
    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "file"
    name to context.contentResolver.getType(uri)
} catch (e: SecurityException) {
    null
}

private fun iconFor(kind: MediaKind) = when (kind) {
    MediaKind.PHOTO -> Icons.Outlined.Image
    MediaKind.GIF -> Icons.Outlined.Gif
    MediaKind.VIDEO -> Icons.Outlined.VideoLibrary
    MediaKind.AUDIO -> Icons.Outlined.AudioFile
    MediaKind.VOICE -> Icons.Outlined.Mic
    MediaKind.DOCUMENT -> Icons.AutoMirrored.Outlined.InsertDriveFile
    MediaKind.LINK -> Icons.Outlined.Link
}
