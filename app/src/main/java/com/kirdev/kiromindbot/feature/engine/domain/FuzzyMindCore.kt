/*
 * Copyright 2026 KiroMind Bot contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Original KiroMind matcher (clean-room).
 *
 * Pipeline: normalize -> exact phrase -> token similarity over indexed candidates.
 * Every incoming word is paired with its best question word; words are compared
 * by a light stem and a bounded edit distance, so typos and word order do not matter.
 * The score is the symmetric average of both directions, penalizing extra or missing words.
 *
 * Follow-up entries ([AnswerEntry.afterEntryId]) are only eligible in their context and
 * win ties against general entries. When two different questions score almost equally
 * the result is NotFound: a wrong answer is worse than none.
 */
class FuzzyMindCore(
    private val threshold: Double = 0.72,
    private val ambiguityMargin: Double = 0.05,
    /** At or above this score a match is trusted even if a rival is close. */
    private val confident: Double = 0.95,
    private val candidateLimit: Int = 300
) : MindCore {

    @Volatile private var cache: Pair<List<AnswerEntry>, AnswerIndex>? = null

    /** Reuses the index while the caller passes the same list instance. */
    fun indexFor(entries: List<AnswerEntry>): AnswerIndex {
        cache?.let { (list, index) -> if (list === entries) return index }
        return AnswerIndex.build(entries).also { cache = entries to it }
    }

    override fun resolve(incoming: String, entries: List<AnswerEntry>, context: MatchContext): MatchResult {
        val phrase = TextNormalizer.normalize(incoming)
        if (phrase.isEmpty()) return MatchResult.NotFound("пустая фраза")
        val index = indexFor(entries)
        val padded = " $phrase "
        fun excluded(e: AnswerEntry) = e.exclusions.any { x ->
            val n = TextNormalizer.normalize(x)
            n.isNotEmpty() && padded.contains(" $n ")
        }
        fun eligible(e: AnswerEntry) = (e.afterEntryId == null || e.afterEntryId == context.previousEntryId) && !excluded(e)
        fun strategy(e: AnswerEntry, base: String) = if (e.afterEntryId != null) "context-$base" else base

        val exact = index.exact(phrase).filter { eligible(it.entry) }
        (exact.firstOrNull { it.entry.afterEntryId != null } ?: exact.firstOrNull())?.let {
            return MatchResult.Found(it.entry, strategy(it.entry, "exact"), 1.0, "фраза совпала полностью")
        }

        val words = TextNormalizer.stems(phrase)
        if (words.isEmpty()) return MatchResult.NotFound("нет слов")
        val ranked = index.candidates(words, candidateLimit)
            .filter { eligible(it.entry) }
            .map { Scored(it, similarity(words, it.stems)) }
            .sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.item.entry.afterEntryId != null })
        val best = ranked.firstOrNull()
            ?: return MatchResult.NotFound("нет похожих вопросов")
        if (best.score < threshold) {
            return MatchResult.NotFound("лучшее совпадение ${percent(best.score)} ниже порога ${percent(threshold)}")
        }
        // Paraphrases of one topic share the reply, so they are not rivals of each other.
        val rival = ranked.drop(1).firstOrNull { it.item.phrase != best.item.phrase && it.item.entry.reply != best.item.entry.reply }
        val contextualWin = best.item.entry.afterEntryId != null && rival?.item?.entry?.afterEntryId == null
        if (rival != null && !contextualWin && best.score < confident && best.score - rival.score < ambiguityMargin) {
            return MatchResult.NotFound("неоднозначно: #${best.item.entry.id} ${percent(best.score)} и #${rival.item.entry.id} ${percent(rival.score)}")
        }
        val e = best.item.entry
        return MatchResult.Found(e, strategy(e, "fuzzy"), best.score, "похоже на «${e.question.take(40)}»")
    }

    private class Scored(val item: AnswerIndex.Item, val score: Double)

    private fun percent(v: Double) = "${(v * 100).toInt()}%"

    internal fun similarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return (coverage(a, b) + coverage(b, a)) / 2.0
    }

    private fun coverage(from: List<String>, to: List<String>): Double =
        from.sumOf { w -> to.maxOf { wordScore(w, it) } } / from.size

    private fun wordScore(x: String, y: String): Double {
        if (x == y) return 1.0
        val longest = max(x.length, y.length)
        // Short words must match exactly; otherwise "да" and "до" would collide.
        if (min(x.length, y.length) < 3) return 0.0
        val allowed = if (longest <= 5) 1 else 2
        val d = boundedDistance(x, y, allowed) ?: return 0.0
        return 1.0 - d.toDouble() / longest
    }

    /** Damerau (adjacent swap) Levenshtein distance, or null once it exceeds [limit]. */
    private fun boundedDistance(x: String, y: String, limit: Int): Int? {
        if (abs(x.length - y.length) > limit) return null
        var prev2 = IntArray(y.length + 1)
        var prev = IntArray(y.length + 1) { it }
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                // "привте" vs "привет": a swapped pair costs one edit, not two.
                if (i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1]) v = min(v, prev2[j - 2] + 1)
                cur[j] = v
                rowMin = min(rowMin, v)
            }
            if (rowMin > limit) return null
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[y.length].takeIf { it <= limit }
    }
}
