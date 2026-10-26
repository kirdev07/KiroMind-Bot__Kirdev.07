package com.kirdev.kiromindbot.feature.learning.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.AppMuted
import com.kirdev.kiromindbot.core.ui.DividerLine
import com.kirdev.kiromindbot.core.ui.plural
import com.kirdev.kiromindbot.feature.learning.domain.UnknownQuestion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Без ответа": questions MindCore could not answer. The owner answers or hides them. */
@Composable
fun UnknownQuestionsScreen(
    questions: List<UnknownQuestion>,
    onAnswer: (UnknownQuestion) -> Unit,
    onDismiss: (UnknownQuestion) -> Unit,
    onBack: () -> Unit
) {
    val time = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
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
        }
        LazyColumn(Modifier.weight(1f)) {
            items(questions, key = { it.key }) { q ->
                Column {
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
}
