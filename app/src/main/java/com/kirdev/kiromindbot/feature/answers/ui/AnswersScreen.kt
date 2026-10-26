package com.kirdev.kiromindbot.feature.answers.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.*
import com.kirdev.kiromindbot.core.ui.*
import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry

@Composable
fun AnswersScreen(answers: List<AnswerEntry>, unknownCount: Int, onEdit: (Long)->Unit, onAdd: ()->Unit, onFavorite: (Long)->Unit, onUnknown: ()->Unit) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Все") }
    val filtered = answers.filter { entry ->
        (filter == "Все" || filter == "С медиа" && entry.media.isNotEmpty() || filter == "Избранное" && entry.favorite || entry.category.isNotBlank() && filter == entry.category) &&
            (entry.question.contains(search, ignoreCase = true) || entry.reply.contains(search, ignoreCase = true) || entry.media.any { it.displayName.contains(search, ignoreCase = true) })
    }
    val categories = remember(answers) { answers.map { it.category }.filter { it.isNotBlank() }.distinct().sorted() }
    // A category that no longer exists falls back to "Все".
    LaunchedEffect(categories) { if (filter !in listOf("Все", "С медиа", "Избранное") && filter !in categories) filter = "Все" }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TitleBlock("База ответов", "${answers.size} записей · сохраняются на устройстве", action = {
            FilledIconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, contentDescription = "Создать ответ") }
        })
        Panel {
            SettingRow("Без ответа", if (unknownCount == 0) "Неизвестных вопросов нет" else plural(unknownCount, "вопрос ждёт", "вопроса ждут", "вопросов ждут") + " ответа", onClick = onUnknown) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = AppMuted)
            }
        }
        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Поиск по базе и медиа") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("Все", "С медиа", "Избранное") + categories).forEach { item ->
                FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item) })
            }
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(filtered, key = { it.id }) { entry ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).clickable { onEdit(entry.id) }.padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(entry.question, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            if (entry.reply.isNotBlank()) Text(entry.reply, color = AppMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val extras = listOfNotNull(
                                entry.alternatives.size.takeIf { it > 0 }?.let { "+$it вар." },
                                entry.afterEntryId?.let { "↳ после #$it" },
                                entry.category.takeIf { it.isNotBlank() },
                                entry.exclusions.size.takeIf { it > 0 }?.let { "искл.: $it" }
                            )
                            if (extras.isNotEmpty()) Text(extras.joinToString(" · "), color = AppMuted, style = MaterialTheme.typography.labelSmall)
                            if (entry.media.isNotEmpty()) {
                                Text(entry.media.joinToString(" · ") { it.kind.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, color = AppMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                        IconButton(onClick = { onFavorite(entry.id) }) {
                            Icon(if (entry.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = "Избранное", tint = if (entry.favorite) AppWhite else AppMuted)
                        }
                    }
                    DividerLine()
                }
            }
            if (filtered.isEmpty()) item {
                Text("Подходящих ответов нет", color = AppMuted, modifier = Modifier.padding(vertical = 20.dp))
            }
        }
    }
}
