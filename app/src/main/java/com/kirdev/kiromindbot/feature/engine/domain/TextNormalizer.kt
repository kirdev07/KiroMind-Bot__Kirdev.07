/*
 * Copyright 2026 KiroMind Bot contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.kirdev.kiromindbot.feature.engine.domain

/** Text preparation shared by matchers. Pure Kotlin, no Android imports. */
object TextNormalizer {
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    // "привееет" -> "привет"; doubles such as "класс" are kept.
    private val stretched = Regex("(\\p{L})\\1{2,}")

    // Longest first so "ами" is stripped before "и".
    private val endings = listOf(
        "ться", "тись", "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими",
        "ая", "яя", "ое", "ее", "ые", "ие", "ой", "ей", "ий", "ый", "ом", "ем",
        "ах", "ях", "ов", "ев", "ть", "ся", "ing", "ed", "es",
        "а", "я", "о", "е", "ы", "и", "у", "ю", "ь", "s"
    )

    fun normalize(input: String): String =
        input.lowercase().replace('ё', 'е').replace(nonWord, " ").replace(stretched, "$1").trim()

    fun stems(normalized: String): List<String> =
        normalized.split(' ').filter { it.isNotEmpty() }.map(::stem)

    fun stem(word: String): String {
        if (word.length <= 3) return word
        val ending = endings.firstOrNull { word.endsWith(it) && word.length - it.length >= 3 } ?: return word
        return word.dropLast(ending.length)
    }
}
