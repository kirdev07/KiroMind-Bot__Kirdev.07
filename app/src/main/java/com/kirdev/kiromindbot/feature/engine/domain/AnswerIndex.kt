/*
 * Copyright 2026 KiroMind Bot contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry

/**
 * Immutable lookup structure over the answer base, built once per base snapshot.
 *
 * Exact phrases go through a hash map. For fuzzy search every question stem is
 * split into character trigrams; a query only scores entries that share trigrams
 * with it, so a 15k+ base is not fully scanned on every message.
 */
class AnswerIndex private constructor(
    val items: List<Item>,
    private val exact: Map<String, List<Int>>,
    private val grams: Map<String, IntArray>,
    val skipped: Int
) {
    class Item(val entry: AnswerEntry, val phrase: String, val stems: List<String>)

    fun exact(phrase: String): List<Item> = exact[phrase].orEmpty().map(items::get)

    /** Items sharing the most trigrams with [stems], best first. */
    fun candidates(stems: List<String>, limit: Int): List<Item> {
        val hits = HashMap<Int, Int>()
        for (gram in stems.flatMap(::gramsOf).toSet()) {
            grams[gram]?.forEach { hits[it] = (hits[it] ?: 0) + 1 }
        }
        return hits.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(limit).map { items[it.key] }
    }

    companion object {
        fun build(entries: List<AnswerEntry>): AnswerIndex {
            val items = ArrayList<Item>(entries.size)
            var skipped = 0
            for (entry in entries) {
                // A damaged record must not take the whole base down.
                val item = runCatching {
                    if (!entry.isValid) null
                    else TextNormalizer.normalize(entry.question).takeIf { it.isNotEmpty() }
                        ?.let { Item(entry, it, TextNormalizer.stems(it)) }
                }.getOrNull()
                if (item == null) skipped++ else items += item
            }
            val exact = HashMap<String, MutableList<Int>>()
            val grams = HashMap<String, MutableList<Int>>()
            items.forEachIndexed { i, item ->
                exact.getOrPut(item.phrase) { ArrayList(1) } += i
                item.stems.flatMap(::gramsOf).toSet().forEach { grams.getOrPut(it) { ArrayList(2) } += i }
            }
            return AnswerIndex(items, exact, grams.mapValues { it.value.toIntArray() }, skipped)
        }

        private fun gramsOf(stem: String): List<String> {
            // Short words are indexed whole: they must match exactly anyway.
            if (stem.length < 3) return listOf("#$stem")
            val padded = "^$stem$"
            return (0..padded.length - 3).map { padded.substring(it, it + 3) }
        }
    }
}
