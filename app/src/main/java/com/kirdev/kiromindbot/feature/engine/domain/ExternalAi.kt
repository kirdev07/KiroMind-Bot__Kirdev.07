/*
 * Copyright 2026 KiroMind Bot contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.kirdev.kiromindbot.feature.engine.domain

/**
 * Optional external model (Claude, Gemini, YandexGPT, OpenAI-compatible...).
 * Implementations live outside the engine and own their keys; MindCore works without any.
 */
interface ExternalAiProvider {
    val name: String
    suspend fun complete(request: AiRequest): String
}

/** [history] is filled only when the owner allowed sharing dialogue context with the provider. */
data class AiRequest(val text: String, val history: List<String> = emptyList())

/** A provider failure with a short user-facing reason (no key, no message text). */
class AiFailure(val reason: String) : Exception(reason)

/** Counts calls per calendar day (device time zone). Thread-safe. */
class DailyQuota(private val clock: () -> Long = System::currentTimeMillis, private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
    private var day: java.time.LocalDate? = null
    private var used = 0

    @Synchronized
    fun tryAcquire(limit: Int): Boolean {
        val today = java.time.Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        if (today != day) { day = today; used = 0 }
        if (used >= limit) return false
        used++
        return true
    }

    @Synchronized fun usedToday(): Int {
        val today = java.time.Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        return if (today == day) used else 0
    }
}

/** Sliding one-minute window limiter. Thread-safe. */
class RateLimiter(private val perMinute: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private val stamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        while (stamps.isNotEmpty() && now - stamps.first() >= 60_000L) stamps.removeFirst()
        if (stamps.size >= perMinute) return false
        stamps.addLast(now)
        return true
    }
}
