/*
 * Copyright 2026 KiroMind Bot contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle
import com.kirdev.kiromindbot.feature.answers.domain.ReplyVariant
import kotlin.random.Random

/** Bot character. It only filters reply variants and never affects matching. */
enum class Persona(val title: String, val style: ReplyStyle?) {
    NEUTRAL("Обычный", ReplyStyle.NEUTRAL),
    FRIENDLY("Дружелюбный", ReplyStyle.FRIENDLY),
    POLITE("Вежливый", ReplyStyle.POLITE),
    CHEEKY("Дерзкий", ReplyStyle.CHEEKY),
    /** Mixes neutral, friendly and polite variants. Never cheeky: that style may contain swearing. */
    AUTO("Автоматический", null)
}

/** Picks one reply variant: persona style first, then neutral; avoids repeating the previous one. */
class ResponseSelector(private val random: Random = Random.Default) {

    fun select(entry: AnswerEntry, persona: Persona, previousVariantId: String?): ReplyVariant? {
        val usable = entry.variants.filter { it.isUsable }
        if (usable.isEmpty()) return null
        val pool = persona.style
            ?.let { style -> usable.filter { it.style == style }.ifEmpty { usable.filter { it.style == ReplyStyle.NEUTRAL } } }
            ?.ifEmpty { usable }
            ?: usable.filter { it.style != ReplyStyle.CHEEKY }.ifEmpty { usable }
        val fresh = pool.filter { it.id != previousVariantId }.ifEmpty { pool }
        return weighted(fresh)
    }

    private fun weighted(options: List<ReplyVariant>): ReplyVariant {
        if (options.size == 1) return options[0]
        val total = options.sumOf { it.weight.coerceAtLeast(1) }
        var roll = random.nextInt(total)
        for (option in options) {
            roll -= option.weight.coerceAtLeast(1)
            if (roll < 0) return option
        }
        return options.last()
    }
}
