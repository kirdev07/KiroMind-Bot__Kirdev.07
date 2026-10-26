/*
 * Copyright 2026 KiroMind Bot contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.kirdev.kiromindbot.feature.backup.domain

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle
import com.kirdev.kiromindbot.feature.answers.domain.ReplyVariant
import com.kirdev.kiromindbot.feature.engine.domain.TextNormalizer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * KiroMind's own backup format (JSON). It holds the answer base only — never tokens, API keys
 * or chat data. Legacy bot archives are deliberately not supported.
 */
object BackupFormat {
    const val FORMAT = "kiromind-backup"
    const val VERSION = 1
    /** Hard limits so a hostile or broken file cannot exhaust memory. */
    const val MAX_ENTRIES = 100_000
    private const val MAX_TEXT = 4_096
    private const val MAX_VARIANTS = 50
    private const val MAX_MEDIA = 20
    private const val MAX_CATEGORY = 40
    private const val MAX_EXCLUSIONS = 50

    fun encode(entries: List<AnswerEntry>, appVersion: String, exportedAtMillis: Long): String {
        val answers = JSONArray()
        entries.forEach { e ->
            answers.put(JSONObject()
                .put("id", e.id)
                .put("question", e.question)
                .put("reply", e.reply)
                .put("favorite", e.favorite)
                .apply { e.afterEntryId?.let { put("afterEntryId", it) } }
                .apply { if (e.category.isNotBlank()) put("category", e.category) }
                .apply { if (e.exclusions.isNotEmpty()) put("exclusions", JSONArray(e.exclusions)) }
                .put("media", mediaJson(e.media))
                .put("variants", JSONArray().apply {
                    e.alternatives.forEach { v ->
                        put(JSONObject().put("id", v.id).put("text", v.text).put("style", v.style.name)
                            .put("weight", v.weight).put("media", mediaJson(v.media)))
                    }
                }))
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("appVersion", appVersion)
            .put("exportedAt", exportedAtMillis)
            .put("answers", answers)
            .toString(1)
    }

    private fun mediaJson(media: List<AnswerMedia>) = JSONArray().apply {
        media.forEach { m ->
            put(JSONObject().put("id", m.id).put("kind", m.kind.name).put("name", m.displayName)
                .put("source", m.source).apply { m.mimeType?.let { put("mime", it) } })
        }
    }

    sealed interface Decoded {
        /** [skipped] damaged or empty records; [localFiles] media pointing at files of another phone. */
        data class Ok(val entries: List<AnswerEntry>, val skipped: Int, val localFiles: Int, val exportedAt: Long, val appVersion: String) : Decoded
        data class Error(val reason: String) : Decoded
    }

    fun decode(text: String): Decoded {
        val root = try { JSONObject(text) } catch (e: JSONException) { return Decoded.Error("это не файл резервной копии KiroMind") }
        if (root.optString("format") != FORMAT) return Decoded.Error("это не файл резервной копии KiroMind")
        val version = root.optInt("version", -1)
        if (version < 1) return Decoded.Error("повреждённый файл: нет версии формата")
        if (version > VERSION) return Decoded.Error("файл создан более новой версией приложения — обновите KiroMind Bot")
        val array = root.optJSONArray("answers") ?: return Decoded.Error("в файле нет ответов")
        if (array.length() > MAX_ENTRIES) return Decoded.Error("слишком много записей (${array.length()}), максимум $MAX_ENTRIES")

        var skipped = 0
        var localFiles = 0
        val entries = ArrayList<AnswerEntry>(array.length())
        val seenIds = HashSet<Long>()
        for (i in 0 until array.length()) {
            val e = runCatching { entry(array.getJSONObject(i)) }.getOrNull()
            if (e == null || !e.isValid || !seenIds.add(e.id)) { skipped++; continue }
            localFiles += (e.media + e.alternatives.flatMap { it.media }).count { it.source.startsWith("content://") }
            entries += e
        }
        return Decoded.Ok(entries, skipped, localFiles, root.optLong("exportedAt"), root.optString("appVersion"))
    }

    private fun entry(o: JSONObject): AnswerEntry {
        val id = o.getLong("id")
        require(id > 0)
        return AnswerEntry(
            id = id,
            question = o.getString("question").take(MAX_TEXT),
            reply = o.optString("reply").take(MAX_TEXT),
            media = media(o.optJSONArray("media")),
            favorite = o.optBoolean("favorite"),
            alternatives = (0 until minOf(o.optJSONArray("variants")?.length() ?: 0, MAX_VARIANTS)).mapNotNull { j ->
                val v = o.getJSONArray("variants").optJSONObject(j) ?: return@mapNotNull null
                ReplyVariant(
                    id = v.optString("id").ifEmpty { "$id:i$j" },
                    text = v.optString("text").take(MAX_TEXT),
                    media = media(v.optJSONArray("media")),
                    style = ReplyStyle.entries.firstOrNull { it.name == v.optString("style") } ?: ReplyStyle.NEUTRAL,
                    weight = v.optInt("weight", 1).coerceIn(1, 10)
                ).takeIf { it.isUsable }
            },
            afterEntryId = if (o.has("afterEntryId")) o.getLong("afterEntryId") else null,
            category = o.optString("category").trim().take(MAX_CATEGORY),
            exclusions = o.optJSONArray("exclusions")?.let { arr ->
                (0 until minOf(arr.length(), MAX_EXCLUSIONS)).mapNotNull { arr.optString(it).trim().take(MAX_TEXT).ifEmpty { null } }
            }.orEmpty()
        )
    }

    private fun media(a: JSONArray?): List<AnswerMedia> = (0 until minOf(a?.length() ?: 0, MAX_MEDIA)).mapNotNull { i ->
        val m = a!!.optJSONObject(i) ?: return@mapNotNull null
        val kind = MediaKind.entries.firstOrNull { it.name == m.optString("kind") } ?: return@mapNotNull null
        val source = m.optString("source").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        AnswerMedia(m.optString("id").ifEmpty { "m$i-${source.hashCode()}" }, kind, m.optString("name").take(200), source,
            m.optString("mime").takeIf { it.isNotEmpty() }, order = i)
    }
}

enum class ImportMode { MERGE, REPLACE }

/** Result of applying a backup to the current base, computed before anything is written. */
data class ImportPlan(val result: List<AnswerEntry>, val added: Int, val updated: Int, val unchanged: Int, val removed: Int)

object ImportPlanner {
    /**
     * Variant and media ids are primary keys across the whole base. Imported entries often get a
     * new answer id, so their child ids are rebuilt from it; otherwise "1:1" from the file could
     * overwrite variant "1:1" of an unrelated existing answer.
     */
    internal fun withOwnIds(e: AnswerEntry): AnswerEntry {
        var m = 0
        fun media(list: List<com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia>) =
            list.mapIndexed { i, x -> x.copy(id = "${e.id}:m${m++}", order = i) }
        return e.copy(
            media = media(e.media),
            alternatives = e.alternatives.mapIndexed { j, v -> v.copy(id = "${e.id}:${j + 1}", media = media(v.media)) }
        )
    }

    /**
     * MERGE: an incoming answer with the same normalized question as an existing one replaces its
     * content but keeps the existing id; everything else is added with a fresh id.
     * REPLACE: the base becomes exactly the incoming answers.
     * In both modes follow-up links (afterEntryId) are remapped to the final ids; links that point
     * nowhere are dropped so no answer becomes unreachable by accident.
     */
    fun plan(existing: List<AnswerEntry>, incoming: List<AnswerEntry>, mode: ImportMode): ImportPlan {
        if (mode == ImportMode.REPLACE) {
            val ids = incoming.map { it.id }.toSet()
            val result = incoming.map { e -> withOwnIds(e.copy(afterEntryId = e.afterEntryId?.takeIf { it in ids })) }
            return ImportPlan(result, added = result.size, updated = 0, unchanged = 0, removed = existing.size)
        }
        val byQuestion = existing.associateBy { TextNormalizer.normalize(it.question) to it.afterEntryId }
        var nextId = (existing.maxOfOrNull { it.id } ?: 0L) + 1
        val idMap = HashMap<Long, Long>()
        val matched = HashMap<Long, AnswerEntry>()   // existing id -> incoming
        val fresh = ArrayList<AnswerEntry>()
        // First pass: decide final ids (needed before remapping follow-ups).
        incoming.forEach { e ->
            val target = byQuestion[TextNormalizer.normalize(e.question) to e.afterEntryId]
                ?: byQuestion[TextNormalizer.normalize(e.question) to null].takeIf { e.afterEntryId == null }
            if (target != null && target.id !in matched) {
                idMap[e.id] = target.id; matched[target.id] = e
            } else {
                idMap[e.id] = nextId++; fresh += e
            }
        }
        // Incoming ids come from another base: a link to an id that is not in the file is dropped.
        fun remap(e: AnswerEntry, id: Long) = withOwnIds(e.copy(id = id, afterEntryId = e.afterEntryId?.let { idMap[it] }))
        var updated = 0
        var unchanged = 0
        val merged = existing.map { old ->
            val inc = matched[old.id] ?: return@map old
            val candidate = remap(inc, old.id).copy(favorite = old.favorite || inc.favorite)
            // Compare content only: child ids are rebuilt on import and would always differ.
            if (candidate == withOwnIds(old)) { unchanged++; old } else { updated++; candidate }
        }
        val added = fresh.map { remap(it, idMap.getValue(it.id)) }
        return ImportPlan(added + merged, added = added.size, updated = updated, unchanged = unchanged, removed = 0)
    }
}
