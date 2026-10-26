package com.kirdev.kiromindbot.feature.settings.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.*
import com.kirdev.kiromindbot.core.ui.*

internal data class SettingsCategory(val route: String, val title: String, val subtitle: String, val icon: ImageVector)
internal val categories = listOf(
    SettingsCategory("telegram", "Telegram", "Бот, токен и владельцы", Icons.AutoMirrored.Outlined.Send),
    SettingsCategory("dialogs", "Диалоги", "Ответы, контекст и характер", Icons.Outlined.Forum),
    SettingsCategory("groups", "Группы", "Упоминания и команды", Icons.Outlined.Group),
    SettingsCategory("security", "Защита", "Антиспам и блокировки", Icons.Outlined.Security),
    SettingsCategory("learning", "Обучение", "Вопросы и диагностика", Icons.Outlined.School),
    SettingsCategory("storage", "Данные и память", "База и резервные копии", Icons.Outlined.Storage),
    SettingsCategory("app", "Приложение", "Уведомления и информация", Icons.Outlined.Settings)
)

@Composable
fun SettingsScreen(onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TitleBlock("Настройки", "KiroMind Bot 1.0")
        Panel {
            categories.forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(item.route) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(item.icon, contentDescription = null, tint = AppWhite)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium)
                        Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = AppMuted)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = AppMuted)
                }
                if (index < categories.lastIndex) DividerLine()
            }
        }
        Text("Настройки пока работают в памяти и сбрасываются после закрытия приложения.", color = AppMuted, style = MaterialTheme.typography.labelSmall)
    }
}
