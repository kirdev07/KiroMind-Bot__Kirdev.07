package com.kirdev.kiromindbot.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kirdev.kiromindbot.feature.terminal.domain.EventKind
import com.kirdev.kiromindbot.feature.terminal.domain.LogEvent

/** Bot on/off switch and the terminal event log. Telegram runs only after the user turns it on. */
class DemoSession {
    var running by mutableStateOf(false)
        private set
    var events by mutableStateOf(listOf(LogEvent(1, java.time.LocalTime.now().withNano(0).toString(), EventKind.SYSTEM, "Запуск MindCore 1.0")))
        private set
    /** Appends a safe diagnostic line (no message text, no ids). */
    fun log(kind: EventKind, message: String) {
        val time = java.time.LocalTime.now().withNano(0).toString()
        val nextId = (events.lastOrNull()?.id ?: 0L) + 1
        // Bounded so a busy bot cannot grow the log without limit.
        events = (events + LogEvent(nextId, time, kind, message)).takeLast(500)
    }

    fun setRunning(value: Boolean, reason: String? = null) {
        if (running == value) return
        running = value
        log(if (value) EventKind.SUCCESS else EventKind.WARNING, (if (value) "Бот запущен" else "Бот остановлен") + (reason?.let { " ($it)" } ?: ""))
    }
}
