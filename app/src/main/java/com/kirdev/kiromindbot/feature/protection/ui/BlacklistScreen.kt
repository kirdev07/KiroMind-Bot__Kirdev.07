package com.kirdev.kiromindbot.feature.protection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.AppMuted
import com.kirdev.kiromindbot.core.ui.DividerLine
import com.kirdev.kiromindbot.core.ui.plural
import com.kirdev.kiromindbot.feature.protection.domain.BlockedUser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Blocked Telegram users: add by id, unblock. Expired temporary blocks are hidden. */
@Composable
fun BlacklistScreen(
    blocked: List<BlockedUser>,
    owners: Set<Long>,
    onBlock: (Long, String) -> Unit,
    onUnblock: (Long) -> Unit,
    onBack: () -> Unit
) {
    val now = System.currentTimeMillis()
    val active = blocked.filter { it.isActive(now) }
    val time = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    var id by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val parsed = id.trim().toLongOrNull()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
            Column {
                Text("Чёрный список", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(plural(active.size, "пользователь", "пользователя", "пользователей"), color = AppMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(value = id, onValueChange = { id = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Telegram ID") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(value = reason, onValueChange = { reason = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Причина (необязательно)") }, singleLine = true)
        Button(
            onClick = { onBlock(parsed!!, reason.trim().ifEmpty { "владелец" }); id = ""; reason = "" },
            enabled = parsed != null && parsed !in owners
        ) { Text("Заблокировать") }
        if (parsed != null && parsed in owners) Text("Это ID владельца — его заблокировать нельзя", style = MaterialTheme.typography.bodySmall)
        Text("В Telegram владелец может написать /block ID или ответить /block на сообщение.", color = AppMuted, style = MaterialTheme.typography.bodySmall)

        LazyColumn(Modifier.weight(1f)) {
            items(active, key = { it.userId }) { b ->
                Column {
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${b.userId}", fontWeight = FontWeight.Medium)
                            Text(b.reason + " · " + (b.untilMillis?.let { "до ${time.format(Date(it))}" } ?: "бессрочно"),
                                color = AppMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { onUnblock(b.userId) }) { Text("Разблокировать") }
                    }
                    DividerLine()
                }
            }
        }
    }
}
