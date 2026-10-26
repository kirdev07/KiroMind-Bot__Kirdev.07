package com.kirdev.kiromindbot.feature.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.AppMuted
import com.kirdev.kiromindbot.core.ui.DividerLine

/** Keep in sync with THIRD_PARTY_NOTICES.md (checked from the release runtime classpath). */
private val libraries = listOf(
    "AndroidX (Compose, Room, DataStore и др.)" to "Apache 2.0",
    "Kotlin, kotlinx.coroutines" to "Apache 2.0",
    "Anthropic Java SDK" to "MIT",
    "OkHttp, Okio" to "Apache 2.0",
    "Jackson" to "Apache 2.0",
    "Guava, Error Prone annotations" to "Apache 2.0",
    "victools jsonschema-generator" to "Apache 2.0",
    "Swagger annotations" to "Apache 2.0",
    "Apache HttpComponents 5" to "Apache 2.0",
    "JSpecify" to "Apache 2.0",
    "Standard Webhooks" to "MIT",
    "SLF4J API" to "MIT"
)

private const val MIT = "Permission is hereby granted, free of charge, to any person obtaining a copy of this software " +
    "and associated documentation files (the \"Software\"), to deal in the Software without restriction, including " +
    "without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies " +
    "of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following " +
    "conditions:\n\nThe above copyright notice and this permission notice shall be included in all copies or " +
    "substantial portions of the Software.\n\nTHE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, " +
    "EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR " +
    "PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, " +
    "DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN " +
    "CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Лицензии") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("KiroMind Bot — Apache License 2.0. Используемые библиотеки:", style = MaterialTheme.typography.bodySmall)
                libraries.forEach { (name, license) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(license, color = AppMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                DividerLine()
                Text("MIT License (Anthropic Java SDK, Standard Webhooks, SLF4J). Copyright © Anthropic, PBC; Standard Webhooks; QOS.ch.",
                    style = MaterialTheme.typography.labelSmall)
                Text(MIT, color = AppMuted, style = MaterialTheme.typography.labelSmall)
                Text("Apache License 2.0: apache.org/licenses/LICENSE-2.0", color = AppMuted, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}
