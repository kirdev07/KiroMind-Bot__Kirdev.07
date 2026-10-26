package com.kirdev.kiromindbot.feature.telegram.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.AppMuted
import com.kirdev.kiromindbot.core.ui.DividerLine
import com.kirdev.kiromindbot.core.ui.Panel
import com.kirdev.kiromindbot.core.ui.SettingRow
import com.kirdev.kiromindbot.feature.home.ui.telegramText
import com.kirdev.kiromindbot.feature.settings.domain.AppPreferences
import com.kirdev.kiromindbot.feature.telegram.api.ConnectionStatus
import com.kirdev.kiromindbot.feature.telegram.data.TelegramController
import com.kirdev.kiromindbot.feature.telegram.data.TokenCheck
import kotlinx.coroutines.launch

/** Token entry, connection check and owner ids. The saved token is never shown again. */
@Composable
fun TelegramSettings(
    controller: TelegramController,
    status: ConnectionStatus,
    hasToken: Boolean,
    settings: AppPreferences,
    onChange: (AppPreferences) -> Unit
) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> TokenCheck) {
        busy = true; result = null
        scope.launch {
            result = when (val r = block()) {
                is TokenCheck.Ok -> "Готово: бот @${r.username}"
                is TokenCheck.Invalid -> r.reason
            }
            busy = false
        }
    }

    Panel {
        SettingRow("Состояние", telegramText(status, hasToken))
        DividerLine()
        SettingRow("Токен бота", if (hasToken) "Сохранён в зашифрованном виде" else "Не задан")
    }

    OutlinedTextField(
        value = token,
        onValueChange = { token = it.trim() },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (hasToken) "Новый токен (заменит сохранённый)" else "Токен от @BotFather") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Скрыть" else "Показать") } }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { run { controller.saveToken(token).also { if (it is TokenCheck.Ok) token = "" } } }, enabled = !busy && token.isNotBlank()) {
            Text("Проверить и сохранить")
        }
        OutlinedButton(onClick = { run { controller.checkSaved() } }, enabled = !busy && hasToken) { Text("Проверить") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (hasToken) {
        TextButton(onClick = { scope.launch { controller.deleteToken(); result = "Токен удалён" } }, enabled = !busy) { Text("Удалить токен") }
    }

    OutlinedTextField(
        value = settings.ownerIds,
        onValueChange = { onChange(settings.copy(ownerIds = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("ID владельцев через запятую") },
        supportingText = { Text("Только им доступна команда /teach вопрос = ответ") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
    Text(
        "Токен проверяется через Telegram и только потом шифруется ключом Android Keystore. " +
            "В журнал не попадают ни токен, ни текст сообщений. Бот начинает отвечать после кнопки запуска на главном экране; " +
            "старые сообщения, пришедшие пока бот был выключен, пропускаются. Вложения из демо-базы не отправляются: " +
            "для реальной отправки нужен file_id Telegram или https-ссылка.",
        color = AppMuted, style = MaterialTheme.typography.bodySmall
    )
}
