package com.kirdev.kiromindbot.feature.protection.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.kirdev.kiromindbot.feature.protection.domain.BlockedUser
import com.kirdev.kiromindbot.feature.protection.domain.InMemoryBlacklist
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "blocked_users")
data class BlockedUserEntity(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: Long,
    val reason: String,
    @ColumnInfo(name = "blocked_at") val blockedAt: Long,
    /** Null: permanent until the owner unblocks. */
    val until: Long?
)

@Dao
interface BlockedUserDao {
    @Query("SELECT * FROM blocked_users") suspend fun all(): List<BlockedUserEntity>
    @Upsert suspend fun upsert(row: BlockedUserEntity)
    @Query("DELETE FROM blocked_users WHERE user_id = :id") suspend fun delete(id: Long)
}

/**
 * Blacklist persisted in Room. Checks run against the in-memory copy (no disk read per message);
 * changes are written in call order by a mutex-guarded background writer.
 */
class RoomBlacklist(
    private val dao: BlockedUserDao,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO
) : InMemoryBlacklist() {
    private val scope = CoroutineScope(scope.coroutineContext + io)
    private val lock = Mutex()

    init {
        write {
            val stored = dao.all().map { BlockedUser(it.userId, it.reason, it.blockedAt, it.until) }
            synchronized(this) {
                // Blocks made while loading are newer and win.
                stored.forEach { if (it.userId !in byId) byId[it.userId] = it }
                publish()
            }
        }
    }

    private fun write(block: suspend () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { lock.withLock { block() } }
    }

    override fun onBlocked(user: BlockedUser) = write { dao.upsert(BlockedUserEntity(user.userId, user.reason, user.blockedAtMillis, user.untilMillis)) }
    override fun onUnblocked(userId: Long) = write { dao.delete(userId) }
}
