package com.kirdev.kiromindbot.feature.engine.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry

/** Independent core contract; no Telegram, Compose, Android Context, or inherited bot engine. */
interface MindCore {
    fun resolve(incoming: String, entries: List<AnswerEntry>, context: MatchContext = MatchContext.NONE): MatchResult
}

/** What the matcher may know about the conversation. */
data class MatchContext(val previousEntryId: Long? = null) {
    companion object { val NONE = MatchContext() }
}

sealed interface MatchResult {
    /** [reason] is a short human explanation for diagnostics; it never contains user data beyond the matched question. */
    data class Found(val entry: AnswerEntry, val strategy: String, val score: Double = 1.0, val reason: String = "") : MatchResult
    data class NotFound(val reason: String = "") : MatchResult
}

/** Alpha 1 deterministic exact matching, kept as a reference implementation. */
class ExactMindCore : MindCore {
    override fun resolve(incoming: String, entries: List<AnswerEntry>, context: MatchContext): MatchResult {
        val prepared = TextNormalizer.normalize(incoming)
        if (prepared.isEmpty()) return MatchResult.NotFound("пустая фраза")
        return entries.firstOrNull { it.isValid && it.afterEntryId == null && TextNormalizer.normalize(it.question) == prepared }
            ?.let { MatchResult.Found(it, "exact", 1.0, "фраза совпала полностью") } ?: MatchResult.NotFound("нет точного совпадения")
    }
}
