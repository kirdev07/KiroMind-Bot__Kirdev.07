package com.kirdev.kiromindbot.feature.engine.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.*
import com.kirdev.kiromindbot.feature.engine.domain.*
import kotlinx.coroutines.launch

private const val NL = "\n"

private data class ChatLine(val fromUser: Boolean, val text: String, val diagnostics: String? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestMindCoreScreen(
    pipeline: MessagePipeline,
    settings: BehaviorSettings,
    unknownCount: () -> Int,
    persona: Persona,
    onPersona: (Persona) -> Unit,
    onBack: () -> Unit
) {
    val lines = remember { mutableStateListOf(ChatLine(false, "Привет! Проверим MindCore. Попробуй «привет», затем «как дела?» и «тоже хорошо».")) }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun send() {
        val message = input.trim()
        if (message.isEmpty()) return
        lines.add(ChatLine(true, message))
        input = ""
        scope.launch {
            // Demo private chat: chat 0, user 1. Nothing leaves the device.
            val outcome = pipeline.process(IncomingMessage(0, ChatKind.PRIVATE, 1, message), settings)
            lines.add(when (outcome) {
                is Outcome.Reply -> outcome.reply.let { r ->
                    val media = r.media.joinToString(NL) { "[демо-вложение: ${it.kind.name.lowercase()} · ${it.displayName}]" }
                    ChatLine(false, listOf(r.text, media).filter { it.isNotBlank() }.joinToString(NL),
                        if (r.unverified) "внешний ИИ · не проверен владельцем · ${r.reason}" else "${r.strategy} · ${(r.score * 100).toInt()}% · #${r.entryId} · ${r.reason}")
                }
                is Outcome.NoAnswer -> ChatLine(false, "В базе ответа нет.",
                    outcome.reason + if (outcome.recorded) " · в «Без ответа»: ${unknownCount()}" else "")
                is Outcome.Ignored -> ChatLine(false, "Сообщение пропущено.", outcome.reason)
                is Outcome.Teach -> ChatLine(false, "Обучение: «${outcome.question}»")
                Outcome.Stopped -> ChatLine(false, "Бот остановлен — сообщения не обрабатываются.")
            })
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
            Column {
                Text("Тест MindCore", style = MaterialTheme.typography.titleLarge)
                Text("Без Telegram · внешний ИИ, если включён", color = AppMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Persona.entries.forEach { p ->
                FilterChip(selected = p == persona, onClick = { onPersona(p) }, label = { Text(p.title) })
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(lines) { _, item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (item.fromUser) Arrangement.End else Arrangement.Start) {
                    Box(Modifier.fillMaxWidth(0.86f).background(if (item.fromUser) AppSurfaceAlt else AppSurface, RoundedCornerShape(16.dp)).padding(14.dp)) {
                        Column {
                            Text(item.text, style = MaterialTheme.typography.bodyMedium)
                            item.diagnostics?.let { Text(it, color = AppMuted, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Напиши фразу...") }, maxLines = 2)
            IconButton(onClick = ::send, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Отправить") }
        }
    }
}
