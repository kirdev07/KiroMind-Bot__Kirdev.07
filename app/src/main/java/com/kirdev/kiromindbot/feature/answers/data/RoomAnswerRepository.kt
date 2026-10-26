package com.kirdev.kiromindbot.feature.answers.data

import com.kirdev.kiromindbot.feature.answers.data.local.AnswerDao
import com.kirdev.kiromindbot.feature.answers.data.local.assemble
import com.kirdev.kiromindbot.feature.answers.data.local.toRows
import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Persistent answer base. The UI reads an in-memory snapshot and changes it immediately;
 * every change is then written in call order (a mutex: a single-thread dispatcher alone
 * would interleave writes at suspension points) and never on the main thread.
 *
 * [seed] is inserted only once, into an empty database on first launch ([isSeeded]/[markSeeded]).
 */
class RoomAnswerRepository(
    private val dao: AnswerDao,
    scope: CoroutineScope,
    private val seed: List<AnswerEntry> = emptyList(),
    private val isSeeded: () -> Boolean = { true },
    private val markSeeded: () -> Unit = {},
    io: CoroutineDispatcher = Dispatchers.IO
) : AnswerRepository {

    private val scope = CoroutineScope(scope.coroutineContext + io.limitedParallelism(1))
    private val writeLock = Mutex()
    private val items = MutableStateFlow<List<AnswerEntry>>(emptyList())
    override val entries: StateFlow<List<AnswerEntry>> = items

    private val loadedFlow = MutableStateFlow(false)
    /** False until the first read from disk finished. */
    val loaded: StateFlow<Boolean> = loadedFlow

    init {
        // Takes the lock first, so every later write waits for the initial load.
        this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writeLock.withLock {
                if (!isSeeded() && dao.count() == 0) {
                    dao.replaceAll(seed.mapIndexed { i, e -> e.toRows(i) })
                }
                markSeeded()
                reload()
                loadedFlow.value = true
            }
        }
    }

    private suspend fun reload() {
        items.value = assemble(dao.answers(), dao.variants(), dao.media())
    }

    /** Runs [block] on the writer; a change made before the first load is re-read afterwards. */
    private fun write(block: suspend () -> Unit) {
        val early = !loadedFlow.value
        // UNDISPATCHED queues on the mutex now, preserving call order.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writeLock.withLock {
                block()
                if (early) reload()
            }
        }
    }

    override fun upsert(answer: AnswerEntry) {
        require(answer.isValid)
        items.update { list ->
            if (list.any { it.id == answer.id }) list.map { if (it.id == answer.id) answer else it } else listOf(answer) + list
        }
        write {
            // Existing answers keep their place; new ones go to the top.
            val position = dao.positionOf(answer.id) ?: (dao.minPosition() - 1)
            dao.replace(answer.toRows(position))
        }
    }

    override fun toggleFavorite(id: Long) {
        items.update { list -> list.map { if (it.id == id) it.copy(favorite = !it.favorite) else it } }
        write { dao.toggleFavorite(id) }
    }

    override fun remove(id: Long) {
        items.update { list -> list.filterNot { it.id == id } }
        write { dao.deleteWithChildren(id) }
    }

    /** Suspends until every write queued so far is on disk (writes run in call order under one lock). */
    suspend fun awaitWrites() = writeLock.withLock { }

    override fun find(id: Long): AnswerEntry? = items.value.firstOrNull { it.id == id }

    override fun replaceAll(entries: List<AnswerEntry>) {
        val valid = entries.filter { it.isValid }
        items.value = valid
        write { dao.replaceEverything(valid.mapIndexed { i, e -> e.toRows(i) }) }
    }
}
