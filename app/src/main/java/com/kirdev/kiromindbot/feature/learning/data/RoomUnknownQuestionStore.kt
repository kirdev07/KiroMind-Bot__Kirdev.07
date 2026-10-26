package com.kirdev.kiromindbot.feature.learning.data

import com.kirdev.kiromindbot.feature.engine.domain.TextNormalizer
import com.kirdev.kiromindbot.feature.learning.data.local.UnknownQuestionDao
import com.kirdev.kiromindbot.feature.learning.data.local.UnknownQuestionEntity
import com.kirdev.kiromindbot.feature.learning.domain.UnknownQuestion
import com.kirdev.kiromindbot.feature.learning.domain.UnknownQuestionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Persistent "Без ответа" queue. Same merge rules as the in-memory store;
 * the snapshot updates at once and rows are written by a serial background writer.
 */
class RoomUnknownQuestionStore(
    private val dao: UnknownQuestionDao,
    scope: CoroutineScope,
    private val capacity: Int = 500,
    io: CoroutineDispatcher = Dispatchers.IO
) : UnknownQuestionStore {

    private val scope = CoroutineScope(scope.coroutineContext + io.limitedParallelism(1))
    private val writeLock = Mutex()

    /** Writes in call order; see RoomAnswerRepository for why a mutex is needed. */
    private fun write(block: suspend () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { writeLock.withLock { block() } }
    }
    private val byKey = LinkedHashMap<String, UnknownQuestion>()
    /** Keys dismissed before the initial load finished; their stored copies must not come back. */
    private val dismissedEarly = HashSet<String>()
    private var loaded = false
    private val state = MutableStateFlow<List<UnknownQuestion>>(emptyList())
    override val items: StateFlow<List<UnknownQuestion>> = state

    init {
        write {
            val stored = dao.all().map { UnknownQuestion(it.key, it.sample, it.count, it.firstSeen, it.lastSeen) }
            synchronized(this@RoomUnknownQuestionStore) {
                // Questions recorded while loading win over their stored copy.
                stored.filterNot { it.key in dismissedEarly }.forEach { q ->
                    val fresh = byKey[q.key]
                    byKey[q.key] = fresh?.copy(count = fresh.count + q.count, firstSeenMillis = q.firstSeenMillis) ?: q
                }
                loaded = true
                dismissedEarly.clear()
                publish()
            }
        }
    }

    private fun publish() { state.value = byKey.values.sortedByDescending { it.lastSeenMillis }.take(capacity) }

    @Synchronized
    override fun record(text: String, atMillis: Long): Boolean {
        val key = TextNormalizer.normalize(text)
        if (key.isEmpty()) return false
        val old = byKey[key]
        val item = old?.copy(count = old.count + 1, lastSeenMillis = atMillis)
            ?: UnknownQuestion(key, text.trim().take(300), 1, atMillis, atMillis)
        byKey[key] = item
        publish()
        byKey.keys.retainAll(state.value.map { it.key }.toSet())
        write {
            // Writes the latest merged copy: it may include counts loaded from disk meanwhile.
            synchronized(this@RoomUnknownQuestionStore) { byKey[key] }?.let { dao.upsert(it.toEntity()) }
            dao.trim(capacity)
        }
        return true
    }

    override fun all(): List<UnknownQuestion> = state.value

    @Synchronized
    override fun dismiss(key: String) {
        if (!loaded) dismissedEarly += key
        if (byKey.remove(key) == null && loaded) return
        publish()
        write { dao.delete(key) }
    }

    private fun UnknownQuestion.toEntity() = UnknownQuestionEntity(key, sample, count, firstSeenMillis, lastSeenMillis)
}
