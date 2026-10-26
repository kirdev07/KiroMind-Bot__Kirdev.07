package com.kirdev.kiromindbot.feature.engine.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.*
import com.kirdev.kiromindbot.feature.settings.domain.AppPreferences
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import com.kirdev.kiromindbot.feature.ai.data.AiKind
import com.kirdev.kiromindbot.feature.ai.data.ClaudeProvider
import com.kirdev.kiromindbot.feature.ai.data.OpenAiCompatibleProvider
import androidx.compose.runtime.*
import com.kirdev.kiromindbot.core.ui.*

@Composable
fun EngineScreen(count: Int, contextEnabled: Boolean, aiStatus: String, onContextChanged: (Boolean)->Unit, onTest: ()->Unit, onProvider: ()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TitleBlock("MindCore", "Собственный движок KiroMind Bot 1.0")
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Psychology, null)
                Text("Локальный движок", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                StatusDot(true)
                Text("Работает", color = AppMuted, style = MaterialTheme.typography.labelSmall)
            }
            DividerLine()
            SettingRow("База ответов", plural(count, "запись", "записи", "записей"))
            SettingRow("Алгоритм", "Совпадение фразы и похожих слов")
        }
        Panel {
            SettingRow("Тестировать ответы", "Без Telegram · внешний ИИ, если включён", onClick = onTest) { Icon(Icons.Outlined.ChevronRight, null, tint = AppMuted) }
        }
        Label("Поведение")
        Panel {
            SettingRow("Контекст диалога", "Помнит 10 последних реплик, 30 минут") {
                Switch(checked = contextEnabled, onCheckedChange = onContextChanged)
            }
            DividerLine()
            SettingRow("Характер", "Выбирается в тестовом чате: обычный, дружелюбный, вежливый, дерзкий, авто")
        }
        Label("Внешний ИИ")
        Panel {
            SettingRow("Провайдер", aiStatus, onClick = onProvider) { Icon(Icons.Outlined.ChevronRight, null, tint = AppMuted) }
        }
        Text("MindCore работает локально: поиск с опечатками, контекст и характер. Внешний ИИ подключается по желанию — только для вопросов без ответа.", color = AppMuted, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * External AI settings: provider choice, per-provider fields, key (stored encrypted, never shown again),
 * on/off, context sharing and a daily cap. The privacy note is always visible.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderScreen(
    keys: Set<AiKind>,
    settings: AppPreferences,
    usedToday: Int,
    onChange: (AppPreferences) -> Unit,
    onSaveKey: suspend (AiKind, String) -> String?,
    onDeleteKey: suspend (AiKind) -> Unit,
    onListModels: suspend (AiKind) -> Result<List<String>>,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val kind = AiKind.of(settings.aiProvider)
    val hasKey = kind in keys
    var key by remember(kind) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember(kind) { mutableStateOf<String?>(null) }
    var limit by remember(settings.aiDailyLimit) { mutableStateOf(settings.aiDailyLimit.toString()) }
    var models by remember(kind, settings.openAiBaseUrl) { mutableStateOf<List<String>?>(null) }
    var modelsError by remember(kind) { mutableStateOf<String?>(null) }
    fun pickModel(id: String) = onChange(
        if (kind == AiKind.GEMINI) settings.copy(geminiModel = id) else settings.copy(openAiModel = id)
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
            Column {
                Text("Внешний ИИ", style = MaterialTheme.typography.titleLarge)
                Text("Только для вопросов без ответа в базе", color = AppMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Label("Провайдер")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiKind.entries.forEach { k ->
                FilterChip(selected = k == kind, onClick = { onChange(settings.copy(aiProvider = k.name)) },
                    label = { Text(k.title + if (k in keys) " ✓" else "") })
            }
        }
        Text(providerHint(kind), color = AppMuted, style = MaterialTheme.typography.bodySmall)

        when (kind) {
            AiKind.GEMINI -> TextSetting("Модель", settings.geminiModel) { onChange(settings.copy(geminiModel = it)) }
            AiKind.YANDEX -> {
                TextSetting("ID каталога Yandex Cloud", settings.yandexFolderId) { onChange(settings.copy(yandexFolderId = it)) }
                TextSetting("Модель", settings.yandexModel) { onChange(settings.copy(yandexModel = it)) }
            }
            AiKind.OPENAI -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OpenAiCompatibleProvider.PRESETS.forEach { preset ->
                        AssistChip(onClick = { onChange(settings.copy(openAiBaseUrl = preset.baseUrl, openAiModel = preset.model)) },
                            label = { Text(preset.title) })
                    }
                }
                TextSetting("Адрес API (https)", settings.openAiBaseUrl) { onChange(settings.copy(openAiBaseUrl = it)) }
                TextSetting("Модель", settings.openAiModel) { onChange(settings.copy(openAiModel = it)) }
            }
            AiKind.CLAUDE -> Text("Модель ${ClaudeProvider.MODEL} через официальный SDK Anthropic.", color = AppMuted, style = MaterialTheme.typography.bodySmall)
        }

        if ((kind == AiKind.GEMINI || kind == AiKind.OPENAI) && hasKey) {
            OutlinedButton(onClick = {
                modelsError = null
                scope.launch {
                    onListModels(kind).onSuccess { models = it; if (it.isEmpty()) modelsError = "Провайдер не вернул ни одной модели" }
                        .onFailure { modelsError = (it.message ?: "ошибка") }
                }
            }) { Text("Показать доступные модели") }
            modelsError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            models?.takeIf { it.isNotEmpty() }?.let { list ->
                val current = if (kind == AiKind.GEMINI) settings.geminiModel else settings.openAiModel
                Panel {
                    Text("Нажмите, чтобы выбрать (${list.size})", color = AppMuted, style = MaterialTheme.typography.labelSmall)
                    list.take(60).forEach { id ->
                        SettingRow(id, if (id == current) "выбрана" else null, onClick = { pickModel(id) })
                    }
                }
            }
        }

        Panel {
            SettingRow("Ключ ${kind.title}", if (hasKey) "Сохранён в зашифрованном виде" else "Не задан")
            DividerLine()
            SettingRow("Отвечать через ${kind.title}", if (hasKey) "Когда MindCore не нашёл ответа" else "Сначала сохраните ключ") {
                Switch(checked = settings.externalAiEnabled && hasKey, enabled = hasKey, onCheckedChange = { onChange(settings.copy(externalAiEnabled = it)) })
            }
            DividerLine()
            SettingRow("Передавать контекст", "Последние 5 реплик собеседника вместе с вопросом") {
                Switch(checked = settings.shareContextWithAi, onCheckedChange = { onChange(settings.copy(shareContextWithAi = it)) })
            }
            DividerLine()
            SettingRow("Сегодня", "Запросов: $usedToday из ${settings.aiDailyLimit} · не больше 20 в минуту")
        }
        OutlinedTextField(
            value = limit,
            onValueChange = { v ->
                limit = v.filter(Char::isDigit).take(4)
                limit.toIntOrNull()?.takeIf { it in 1..1000 }?.let { onChange(settings.copy(aiDailyLimit = it)) }
            },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Дневной лимит запросов (1–1000)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it.trim() }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (hasKey) "Новый ключ (заменит сохранённый)" else kind.keyHint) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                busy = true; result = null
                scope.launch {
                    val error = onSaveKey(kind, key)
                    result = error ?: "Готово: ключ проверен и сохранён"
                    if (error == null) key = ""
                    busy = false
                }
            }, enabled = !busy && key.isNotBlank()) { Text("Проверить и сохранить") }
            if (hasKey) TextButton(onClick = { scope.launch { onDeleteKey(kind); result = "Ключ удалён" } }, enabled = !busy) { Text("Удалить ключ") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            "Когда включено, текст вопроса без ответа отправляется выбранному провайдеру (${kind.title}). " +
                "Контекст передаётся, только если включён переключатель выше. Ответ помечается «не проверен владельцем», " +
                "в базу сам не сохраняется, а вопрос всё равно попадает в «Без ответа». Если провайдер недоступен, бот просто молчит.",
            color = AppMuted, style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun providerHint(kind: AiKind) = when (kind) {
    AiKind.GEMINI -> "Бесплатный тариф: ключ в aistudio.google.com → Get API key. На бесплатном тарифе Google может использовать запросы для улучшения своих моделей."
    AiKind.CLAUDE -> "Платный: ключ в console.anthropic.com. Проверка ключа бесплатна."
    AiKind.YANDEX -> "Yandex Cloud: API-ключ сервисного аккаунта с ролью ai.languageModels.user и ID каталога. Проверка ключа тратит ~5 токенов."
    AiKind.OPENAI -> "Любой сервис с OpenAI-совместимым API. У Groq и OpenRouter есть бесплатные модели."
}

@Composable
private fun TextSetting(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(value = text, onValueChange = { text = it; onChange(it.trim()) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, label = { Text(label) })
}
