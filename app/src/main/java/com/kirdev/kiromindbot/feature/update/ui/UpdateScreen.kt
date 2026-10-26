package com.kirdev.kiromindbot.feature.update.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.BuildConfig
import com.kirdev.kiromindbot.core.designsystem.AppMuted
import com.kirdev.kiromindbot.core.ui.DividerLine
import com.kirdev.kiromindbot.core.ui.Panel
import com.kirdev.kiromindbot.core.ui.SettingRow
import com.kirdev.kiromindbot.feature.update.data.UpdateManager
import com.kirdev.kiromindbot.feature.update.domain.UpdateCheck
import kotlinx.coroutines.launch

/** Check -> download & verify -> hand over to Android's installer. */
@Composable
fun UpdateScreen(updates: UpdateManager, onBack: () -> Unit) {
    val state by updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
            Column {
                Text("Обновления", style = MaterialTheme.typography.titleLarge)
                Text("GitHub · ${UpdateManager.REPO}", color = AppMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Panel {
            SettingRow("Установлена версия", BuildConfig.VERSION_NAME)
            DividerLine()
            SettingRow("Состояние", describe(state))
        }

        when (val s = state) {
            is UpdateManager.State.Result -> (s.check as? UpdateCheck.Available)?.let { a ->
                if (a.release.notes.isNotBlank()) Panel { Text(a.release.notes.take(800), style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { scope.launch { updates.download(a.release) } }) {
                    Text("Скачать ${a.release.tag} (${a.release.apkSize / (1 shl 20)} МБ)")
                }
            }
            is UpdateManager.State.Downloading -> LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
            is UpdateManager.State.ReadyToInstall -> {
                if (updates.canInstall()) {
                    Button(onClick = { updates.install(s.file) }) { Text("Установить ${s.release.tag}") }
                } else {
                    Text("Разрешите KiroMind Bot устанавливать обновления, затем вернитесь сюда.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { context.startActivity(updates.installPermissionIntent()) }) { Text("Открыть разрешение") }
                    Button(onClick = { updates.install(s.file) }, enabled = updates.canInstall()) { Text("Установить ${s.release.tag}") }
                }
            }
            else -> Unit
        }

        if (state !is UpdateManager.State.Checking && state !is UpdateManager.State.Downloading) {
            OutlinedButton(onClick = { scope.launch { updates.check() } }) { Text("Проверить обновления") }
        }
        Text(
            "Приложение само проверяет релизы раз в сутки. APK скачивается только с GitHub по HTTPS и устанавливается, " +
                "только если совпадают контрольная сумма SHA-256, пакет, номер версии и ключ подписи. Установку всегда подтверждаете вы.",
            color = AppMuted, style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun describe(state: UpdateManager.State) = when (state) {
    UpdateManager.State.Idle -> "Ещё не проверялось"
    UpdateManager.State.Checking -> "Проверяю GitHub…"
    is UpdateManager.State.Result -> when (val c = state.check) {
        is UpdateCheck.Available -> "Доступна версия ${c.release.tag}"
        UpdateCheck.UpToDate -> "Установлена последняя версия"
        is UpdateCheck.Failed -> c.reason
    }
    is UpdateManager.State.Downloading -> "Загрузка ${state.release.tag}: ${state.percent}%"
    is UpdateManager.State.ReadyToInstall -> "Файл проверен, можно устанавливать"
    is UpdateManager.State.Failed -> state.reason
}
