package com.kirdev.kiromindbot.feature.learning.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.AppMuted
import com.kirdev.kiromindbot.core.ui.DividerLine
import com.kirdev.kiromindbot.core.ui.plural
import com.kirdev.kiromindbot.feature.learning.domain.UnknownQuestion
import com.kirdev.kiromindbot.feature.learning.domain.UnknownQueueView
import com.kirdev.kiromindbot.feature.learning.domain.UnknownSort
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Без ответа": questions MindCore could not answer. The owner answers or hides them, one by one or in bulk. */
@Composable
fun UnknownQuestionsScreen(
    questions: List<UnknownQuestion>,
    onAnswer: (UnknownQuestion) -> Unit,
    onDismiss: (UnknownQuestion) -> Unit,
    onBack: () -> Unit
) {
    val time = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(UnknownSort.RECENT) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmBulk by remember { mutableStateOf(false) }
    val shown = remember(questions, query, sort) { UnknownQueueView.of(questions, query, sort) }
    // Drop selections of items that left the list (answered or hidden elsewhere).
    LaunchedEffect(questions) { selected = selected intersect questions.map { it.key }.toSet() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
            Column {
                Text("Без ответа", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(plural(questions.size, "вопрос", "вопроса", "вопросов") + " · сохраняются на устройстве", color = AppMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (questions.isEmpty()) {
            Text("Пока пусто. Вопросы, на которые MindCore не нашёл ответа, появятся здесь.",
                color = AppMuted, style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Найти вопрос") }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sort == UnknownSort.RECENT, onClick = { sort = UnknownSort.RECENT }, label = { Text("Новые") })
                FilterChip(selected = sort == UnknownSort.FREQUENT, onClick = { sort = UnknownSort.FREQUENT }, label = { Text("Частые") })
                Spacer(Modifier.weight(1f))
                if (selected.isEmpty()) {
                    TextButton(onClick = { selected = shown.map { it.key }.toSet() }, enabled = shown.isNotEmpty()) { Text("Выбрать все") }
                } else {
                    TextButton(onClick = { selected = emptySet() }) { Text("Снять") }
                }
            }
            if (selected.isNotEmpty()) {
                Button(onClick = { confirmBulk = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Скрыть выбранные: ${selected.size}")
                }
            }
            if (shown.isEmpty()) Text("Ничего не найдено", color = AppMuted, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.key }) { q ->
                Column {
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = q.key in selected, onCheckedChange = { on -> selected = if (on) selected + q.key else selected - q.key },
                            modifier = Modifier.semantics { contentDescription = "Выбрать: ${q.sample.take(60)}" })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(q.sample, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("×${q.count} · последний ${time.format(Date(q.lastSeenMillis))}",
                                color = AppMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { onAnswer(q) }) { Text("Ответить") }
                        IconButton(onClick = { onDismiss(q) }) { Icon(Icons.Outlined.Close, contentDescription = "Скрыть вопрос") }
                    }
                    DividerLine()
                }
            }
        }
    }

    if (confirmBulk) {
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text("Скрыть ${plural(selected.size, "вопрос", "вопроса", "вопросов")}?") },
            text = { Text("Они исчезнут из «Без ответа». Если их спросят снова, они вернутся.") },
            confirmButton = {
                TextButton(onClick = {
                    questions.filter { it.key in selected }.forEach(onDismiss)
                    selected = emptySet(); confirmBulk = false
                }) { Text("Скрыть") }
            },
            dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("Отмена") } }
        )
    }
}
