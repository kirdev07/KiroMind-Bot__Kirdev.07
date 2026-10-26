package com.kirdev.kiromindbot.feature.home.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.*
import com.kirdev.kiromindbot.core.state.DemoSession
import com.kirdev.kiromindbot.feature.telegram.api.ConnectionStatus
import com.kirdev.kiromindbot.core.ui.*

@Composable
fun HomeScreen(session: DemoSession, answerCount: Int, telegram: ConnectionStatus, hasToken: Boolean, onToggle: () -> Unit, onTelegram: () -> Unit, onTerminal: () -> Unit, onAnswers: () -> Unit, onEngine: () -> Unit, onSecurity: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TitleBlock("KiroMind Bot", "1.0 · Fusion Minimal")
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (session.running) "Бот включён" else "Бот выключен", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        StatusDot(telegram is ConnectionStatus.Online)
                        Text(telegramText(telegram, hasToken), color = AppMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.size(56.dp).background(if (session.running) AppSurfaceAlt else AppWhite, CircleShape).clickable(onClick = onToggle),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.PowerSettingsNew, contentDescription = if (session.running) "Остановить бота" else "Запустить бота", tint = if (session.running) AppWhite else AppBlack, modifier = Modifier.size(28.dp))
                    }
                    Text(if (session.running) "Остановить" else "Запустить", color = AppMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
            DividerLine()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("${session.events.size}", "Событий")
                Stat("$answerCount", "Ответов")
                Stat("1.0", "Версия")
            }
        }
        Label("Состояние системы")
        Panel {
            SettingRow("База ответов", plural(answerCount, "запись", "записи", "записей"), onAnswers) { Text("Открыть", color = AppMuted, style = MaterialTheme.typography.labelSmall) }
            DividerLine()
            SettingRow("MindCore", "Поиск по базе с учётом опечаток", onEngine) { Text("Готов", color = AppGreen, style = MaterialTheme.typography.labelSmall) }
            DividerLine()
            SettingRow("Безопасность", "Параметры доступа", onSecurity) { Icon(Icons.Outlined.ChevronRight, null, tint = AppMuted) }
            DividerLine()
            SettingRow("Telegram", telegramText(telegram, hasToken), onTelegram) { Icon(Icons.Outlined.ChevronRight, null, tint = AppMuted) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Мини-консоль")
            TextButton(onClick = onTerminal) { Text("Все события") }
        }
        Panel(Modifier.clickable(onClick = onTerminal)) {
            session.events.takeLast(4).forEach { event ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(event.timestamp, color = AppMuted, style = MaterialTheme.typography.labelSmall)
                    Text(event.message, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBCBD0))
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, color = AppMuted, style = MaterialTheme.typography.labelSmall)
    }
}

fun telegramText(status: ConnectionStatus, hasToken: Boolean): String = when (status) {
    is ConnectionStatus.Online -> "Telegram: онлайн @${status.username}"
    ConnectionStatus.Connecting -> "Telegram: подключение…"
    is ConnectionStatus.Reconnecting -> "Telegram: ${status.reason}, переподключение"
    is ConnectionStatus.Error -> "Telegram: ${status.summary}"
    ConnectionStatus.NoToken -> "Telegram: токен не задан"
    ConnectionStatus.Disconnected -> if (hasToken) "Telegram: выключен" else "Telegram: токен не задан"
}
