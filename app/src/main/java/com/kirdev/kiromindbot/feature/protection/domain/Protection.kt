/*
 * Copyright 2026 KiroMind Bot contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.kirdev.kiromindbot.feature.protection.domain

import com.kirdev.kiromindbot.feature.engine.domain.TextNormalizer
import kotlinx.coroutines.flow.StateFlow

/** A Telegram user the bot ignores. [untilMillis] null means until unblocked by the owner. */
data class BlockedUser(val userId: Long, val reason: String, val blockedAtMillis: Long, val untilMillis: Long?) {
    fun isActive(now: Long) = untilMillis == null || now < untilMillis
}

interface Blacklist {
    /** Active blocks, newest first. */
    val items: StateFlow<List<BlockedUser>>
    fun isBlocked(userId: Long, now: Long): Boolean
    fun block(userId: Long, reason: String, now: Long, untilMillis: Long? = null)
    /** Returns false if the user was not blocked. */
    fun unblock(userId: Long): Boolean
}

sealed interface SpamVerdict {
    data object Allow : SpamVerdict
    /** Message dropped; [autoBlockMinutes] set when the sender should now be blocked temporarily. */
    data class Drop(val reason: String, val autoBlockMinutes: Int? = null) : SpamVerdict
}

/**
 * Per-user flood and repeat protection, in memory. Thread-safe.
 *
 * - more than [maxMessages] messages within [windowMillis] -> dropped;
 * - the same text more than [maxRepeats] times within [repeatWindowMillis] -> dropped;
 * - [violationsToBlock] drops within [violationWindowMillis] -> temporary block suggestion.
 */
class AntiSpam(
    private val maxMessages: Int = 6,
    private val windowMillis: Long = 15_000,
    private val maxRepeats: Int = 3,
    private val repeatWindowMillis: Long = 60_000,
    private val violationsToBlock: Int = 3,
    private val violationWindowMillis: Long = 10 * 60_000L,
    val autoBlockMinutes: Int = 60,
    private val maxTrackedUsers: Int = 5_000
) {
    private class Track {
        val times = ArrayDeque<Long>()
        val texts = ArrayDeque<Pair<Long, String>>()
        val violations = ArrayDeque<Long>()
    }

    private val users = object : LinkedHashMap<Long, Track>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Track>) = size > maxTrackedUsers
    }

    @Synchronized
    fun check(userId: Long, text: String, now: Long, autoBlock: Boolean): SpamVerdict {
        val t = users.getOrPut(userId) { Track() }
        t.times.dropOlderThan(now - windowMillis) { it }
        t.texts.dropOlderThan(now - repeatWindowMillis) { it.first }
        t.violations.dropOlderThan(now - violationWindowMillis) { it }

        val key = TextNormalizer.normalize(text)
        val reason = when {
            t.times.size >= maxMessages -> "слишком часто"
            key.isNotEmpty() && t.texts.count { it.second == key } >= maxRepeats -> "повтор одного и того же"
            else -> null
        }
        t.times.addLast(now)
        t.texts.addLast(now to key)
        if (reason == null) return SpamVerdict.Allow

        t.violations.addLast(now)
        val block = autoBlock && t.violations.size >= violationsToBlock
        if (block) users.remove(userId)
        return SpamVerdict.Drop(reason, autoBlockMinutes.takeIf { block })
    }

    @Synchronized fun forget(userId: Long) { users.remove(userId) }

    private inline fun <T> ArrayDeque<T>.dropOlderThan(limit: Long, time: (T) -> Long) {
        while (isNotEmpty() && time(first()) < limit) removeFirst()
    }
}

/** In-memory blacklist for tests and as the Room store's model. */
open class InMemoryBlacklist : Blacklist {
    protected val byId = LinkedHashMap<Long, BlockedUser>()
    private val state = kotlinx.coroutines.flow.MutableStateFlow<List<BlockedUser>>(emptyList())
    override val items: StateFlow<List<BlockedUser>> = state

    protected fun publish() { state.value = byId.values.sortedByDescending { it.blockedAtMillis } }

    @Synchronized
    override fun isBlocked(userId: Long, now: Long): Boolean {
        val b = byId[userId] ?: return false
        if (b.isActive(now)) return true
        byId.remove(userId); publish(); onExpired(userId)
        return false
    }

    @Synchronized
    override fun block(userId: Long, reason: String, now: Long, untilMillis: Long?) {
        val b = BlockedUser(userId, reason.take(200), now, untilMillis)
        byId[userId] = b; publish(); onBlocked(b)
    }

    @Synchronized
    override fun unblock(userId: Long): Boolean {
        if (byId.remove(userId) == null) return false
        publish(); onUnblocked(userId)
        return true
    }

    protected open fun onBlocked(user: BlockedUser) {}
    protected open fun onUnblocked(userId: Long) {}
    protected open fun onExpired(userId: Long) = onUnblocked(userId)
}
