package com.kirdev.kiromindbot.feature.settings.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.*
import com.kirdev.kiromindbot.core.ui.*
import com.kirdev.kiromindbot.feature.settings.domain.AppPreferences
import com.kirdev.kiromindbot.feature.telegram.api.ConnectionStatus
import com.kirdev.kiromindbot.feature.telegram.data.TelegramController
import com.kirdev.kiromindbot.feature.telegram.ui.TelegramSettings

@Composable
fun SettingsDetailScreen(
    section: String,
    settings: AppPreferences,
    onChange: (AppPreferences)->Unit,
    telegram: TelegramController,
    telegramStatus: ConnectionStatus,
    hasToken: Boolean,
    blockedCount: Int,
    onBlacklist: () -> Unit,
    onUpdates: () -> Unit,
    onBackup: () -> Unit,
    onBack: ()->Unit
) {
    val heading = categories.find { it.route == section }?.title ?: "Настройки"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
            Text(heading, style = MaterialTheme.typography.titleLarge)
        }
        when(section) {
            "telegram" -> TelegramSettings(telegram, telegramStatus, hasToken, settings, onChange)
            "dialogs" -> {
                Panel {
                    ToggleRow("Контекст диалога", "Помнить последние реплики собеседника", settings.contextEnabled) { onChange(settings.copy(contextEnabled=it)) }
                }
                Label("Характер ответов")
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.kirdev.kiromindbot.feature.engine.domain.Persona.entries.forEach { p ->
                        FilterChip(selected = settings.persona == p, onClick = { onChange(settings.copy(persona = p)) }, label = { Text(personaLabel(p)) })
                    }
                }
                Note("Характер выбирает стиль среди вариантов ответа. «Авто» подстраивается под тон собеседника.")
            }
            "groups" -> {
                Panel {
                    ToggleRow("Работа в группах", "Отвечать в группах при обращении, упоминании или ответе боту", settings.groupsEnabled) { onChange(settings.copy(groupsEnabled=it)) }
                    DividerLine()
                    ToggleRow("Ответы на упоминания", "@бот или ответ на сообщение бота считается обращением", settings.mentionsEnabled) { onChange(settings.copy(mentionsEnabled=it)) }
                }
                OutlinedTextField(value = settings.groupPrefix, onValueChange = { onChange(settings.copy(groupPrefix=it)) }, modifier = Modifier.fillMaxWidth(), label = { Text("Обращение к боту") }, singleLine = true)
            }
            "security" -> {
                Panel {
                    ToggleRow("Антиспам", "Не больше 6 сообщений за 15 с и 3 одинаковых за минуту", settings.antiSpam) { onChange(settings.copy(antiSpam=it)) }
                    DividerLine()
                    ToggleRow("Автоблокировка", "После 3 нарушений за 10 минут — блок на час", settings.autoBan) { onChange(settings.copy(autoBan=it)) }
                    DividerLine()
                    SettingRow("Чёрный список", plural(blockedCount, "пользователь", "пользователя", "пользователей"), onBlacklist) {
                        Icon(Icons.Outlined.ChevronRight, null, tint = AppMuted)
                    }
                }
                Note("Владельцев (ID в настройках Telegram) антиспам и чёрный список не касаются. Сообщения заблокированных бот молча пропускает.")
            }
            "learning" -> {
                Panel {
                    ToggleRow("Неизвестные вопросы", "Будет сохранять новые вопросы", settings.logUnknown) { onChange(settings.copy(logUnknown=it)) }
                    DividerLine()
                    ToggleRow("Диагностика MindCore", "Причины пропуска и промаха в терминале — без текста сообщений", settings.diagnosticLogs) { onChange(settings.copy(diagnosticLogs=it)) }
                }
                Note("Вопросы без ответа попадают на экран «Без ответа», откуда их можно сразу научить.")
            }
            "storage" -> {
                Panel {
                    SettingRow("База ответов", "Room на устройстве · сохраняется между запусками")
                    DividerLine()
                    SettingRow("Импорт и экспорт", "Файл .kmbackup.json", onBackup) { Icon(Icons.Outlined.ChevronRight, null, tint = AppMuted) }
                    DividerLine()
                    SettingRow("Резервные копии", "Авто раз в сутки и перед импортом · последние 7", onBackup) { Icon(Icons.Outlined.ChevronRight, null, tint = AppMuted) }
                }
                Note("Ответы и «Без ответа» хранятся на устройстве. В резервные копии и экспорт входит база ответов; токены и ключи — никогда.")
            }
            "app" -> {
                Panel {
                    ToggleRow("Уведомления", "Сообщить, если бот потерял связь с Telegram", settings.notifications) { onChange(settings.copy(notifications=it)) }
                    DividerLine()
                    SettingRow("Оформление", "Fusion Minimal · AMOLED Black")
                    DividerLine()
                    SettingRow("Версия", com.kirdev.kiromindbot.BuildConfig.VERSION_NAME)
                    DividerLine()
                    SettingRow("Обновления", "Через релизы GitHub", onUpdates) { Icon(Icons.Outlined.ChevronRight, null, tint = AppMuted) }
                }
            }
            else -> Note("Неизвестный раздел")
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, value: Boolean, onValueChanged: (Boolean)->Unit) {
    SwitchRow(title, subtitle, value, onCheckedChange = onValueChanged)
}

@Composable
private fun Note(message: String) = Text(message, color = AppMuted, style = MaterialTheme.typography.bodySmall)

private fun personaLabel(p: com.kirdev.kiromindbot.feature.engine.domain.Persona) = when (p.name) {
    "NEUTRAL" -> "Нейтральный"; "FRIENDLY" -> "Дружелюбный"; "POLITE" -> "Вежливый"; "CHEEKY" -> "Дерзкий"; else -> "Авто"
}
