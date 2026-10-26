package com.kirdev.kiromindbot.feature.terminal.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.*
import com.kirdev.kiromindbot.core.state.DemoSession
import com.kirdev.kiromindbot.core.ui.*
import com.kirdev.kiromindbot.feature.terminal.domain.EventKind

@Composable
fun TerminalScreen(session: DemoSession) {
    var filter by remember { mutableStateOf("Все") }
    var query by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    val filtered = session.events.filter {
        (filter == "Все" || (filter == "Диалоги" && it.kind == EventKind.MESSAGE) || (filter == "Ошибки" && it.kind in listOf(EventKind.WARNING, EventKind.ERROR))) && it.message.contains(query, ignoreCase = true)
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TitleBlock("Терминал", "События бота и MindCore")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(session.running)
                Text(if (session.running) "Бот работает" else "Бот остановлен", style = MaterialTheme.typography.bodySmall)
            }
            Text("${session.events.size} событий", color = AppMuted, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Все", "Диалоги", "Ошибки").forEach { item ->
                FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f), placeholder = { Text("Найти событие") }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) })
            val context = androidx.compose.ui.platform.LocalContext.current
            FilledTonalIconButton(onClick = { shareJournal(context, filtered) }, enabled = filtered.isNotEmpty()) {
                Icon(Icons.Outlined.Share, contentDescription = "Экспорт журнала")
            }
            FilledTonalIconButton(onClick = { paused = !paused }) {
                Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, contentDescription = "Пауза просмотра")
            }
        }
        Panel(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("MINDCORE / SESSION", color = AppMuted, style = MaterialTheme.typography.labelSmall)
                Text("1.0", color = AppMuted, style = MaterialTheme.typography.labelSmall)
            }
            DividerLine()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(filtered, key = { it.id }) { event ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Text(event.timestamp, color = AppMuted, style = MaterialTheme.typography.labelSmall)
                        val dotColor = when (event.kind) {
                            EventKind.ERROR -> Color(0xFFE57979)
                            EventKind.WARNING -> Color(0xFFDBAE76)
                            EventKind.SUCCESS -> AppGreen
                            else -> AppMuted
                        }
                        Box(Modifier.padding(top = 4.dp).size(6.dp).background(dotColor, CircleShape))
                        Text(event.message, modifier = Modifier.weight(1f), color = Color(0xFFD2D2D7), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (filtered.isEmpty()) item { Text("Подходящих событий нет", color = AppMuted) }
            }
        }
        Text(if (paused) "Просмотр приостановлен" else "Без текста сообщений, токенов и ID · последние 500 событий", color = AppMuted, style = MaterialTheme.typography.labelSmall)
    }
}

/** Shares the shown events as plain text; the journal never holds message text, tokens or IDs. */
private fun shareJournal(context: android.content.Context, events: List<com.kirdev.kiromindbot.feature.terminal.domain.LogEvent>) {
    val text = "KiroMind Bot ${com.kirdev.kiromindbot.BuildConfig.VERSION_NAME} — журнал\n" +
        events.joinToString("\n") { "${it.timestamp} ${it.kind.name.padEnd(7)} ${it.message}" }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Журнал KiroMind Bot")
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    context.startActivity(android.content.Intent.createChooser(send, "Экспорт журнала"))
}
