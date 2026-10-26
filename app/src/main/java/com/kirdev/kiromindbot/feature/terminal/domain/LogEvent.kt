package com.kirdev.kiromindbot.feature.terminal.domain

enum class EventKind { SYSTEM, MESSAGE, SUCCESS, WARNING, ERROR }

data class LogEvent(val id: Long, val timestamp: String, val kind: EventKind, val message: String)
