package com.kirdev.kiromindbot.feature.backup.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle
import com.kirdev.kiromindbot.feature.answers.domain.ReplyVariant
import com.kirdev.kiromindbot.feature.engine.domain.TextNormalizer
import org.json.JSONException
import org.json.JSONObject

/**
 * The answer base shipped inside the APK (assets/base, built by tools/basegen).
 * Stored compactly: each topic keeps its replies once and lists the phrasings that lead to it.
 */
object BuiltInPack {
    const val FORMAT = "kiromind-base"

    data class Pack(val version: Int, val entries: List<AnswerEntry>)

    /** Expands topics into answers; ids start at [firstId]. Returns null for a damaged or foreign file. */
    fun parse(text: String, firstId: Long): Pack? {
        val root = try { JSONObject(text) } catch (e: JSONException) { return null }
        if (root.optString("format") != FORMAT) return null
        val topics = root.optJSONArray("topics") ?: return null
        val entries = ArrayList<AnswerEntry>()
        var id = firstId
        for (t in 0 until topics.length()) {
            val topic = topics.optJSONObject(t) ?: continue
            val replies = topic.optJSONArray("replies") ?: continue
            val styled = (0 until replies.length()).mapNotNull { i ->
                val pair = replies.optJSONArray(i) ?: return@mapNotNull null
                val style = ReplyStyle.entries.firstOrNull { it.name == pair.optString(0) } ?: return@mapNotNull null
                pair.optString(1).takeIf { it.isNotBlank() }?.let { style to it }
            }
            val main = styled.firstOrNull { it.first == ReplyStyle.NEUTRAL }?.second ?: continue
            val rest = styled - (ReplyStyle.NEUTRAL to main)
            val category = topic.optString("category").take(40)
            val questions = topic.optJSONArray("questions") ?: continue
            for (q in 0 until questions.length()) {
                val question = questions.optString(q).trim()
                if (question.isEmpty()) continue
                val entryId = id++
                entries += AnswerEntry(
                    id = entryId, question = question, reply = main, category = category,
                    alternatives = rest.mapIndexed { k, (style, text) -> ReplyVariant("$entryId:${k + 1}", text, style = style) }
                )
            }
        }
        return Pack(root.optInt("version", 0), entries)
    }

    /**
     * Adds only questions the base does not have yet: the owner's own answers and edits always win,
     * and a repeated merge changes nothing. Ids are moved after the largest existing one.
     */
    fun mergeInto(existing: List<AnswerEntry>, pack: List<AnswerEntry>): List<AnswerEntry> {
        val known = existing.filter { it.afterEntryId == null }.mapTo(HashSet()) { TextNormalizer.normalize(it.question) }
        var next = (existing.maxOfOrNull { it.id } ?: 0L) + 1
        val added = pack.asSequence()
            .filter { known.add(TextNormalizer.normalize(it.question)) }
            .map { e ->
                val id = next++
                e.copy(id = id, alternatives = e.alternatives.mapIndexed { k, v -> v.copy(id = "$id:${k + 1}") })
            }
            .toList()
        return existing + added
    }
}
