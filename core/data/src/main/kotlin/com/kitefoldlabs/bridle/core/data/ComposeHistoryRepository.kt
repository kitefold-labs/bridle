package com.kitefoldlabs.bridle.core.data

import androidx.room.withTransaction
import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.data.db.ComposeHistoryEntity
import com.kitefoldlabs.bridle.core.model.EntityId
import kotlinx.coroutines.flow.Flow

/**
 * Persists compose-box history per host, in the shape of `core:terminal`'s `ComposeHistories`
 * snapshot (entries most recent first). The history rules (uniqueness, capacity, length limit)
 * belong to `ComposeHistory`; this class stores whatever it is given, in order.
 *
 * History can contain anything the user typed, so the UI should offer clearing it. It is deleted
 * with its host.
 */
public class ComposeHistoryRepository internal constructor(
    private val database: BridleDatabase,
) {
    private val dao = database.composeHistoryDao()
    private val hosts = database.hostDao()

    /** Every non-empty history, keyed by host id; pass it to `ComposeHistories(snapshot)`. */
    public suspend fun snapshot(): Map<EntityId, List<String>> = dao.getAll().groupBy({ it.hostId }, { it.text })

    public fun observe(hostId: EntityId): Flow<List<String>> = dao.observe(hostId)

    public suspend fun get(hostId: EntityId): List<String> = dao.get(hostId)

    /**
     * Replaces the history of [hostId] with [entries] (most recent first).
     * Returns false, storing nothing, if the host no longer exists.
     */
    public suspend fun save(
        hostId: EntityId,
        entries: List<String>,
    ): Boolean =
        database.withTransaction {
            val exists = hosts.exists(hostId)
            if (exists) replace(hostId, entries)
            exists
        }

    /**
     * Replaces every stored history with [snapshot], e.g. `ComposeHistories.snapshot()`.
     * Histories of hosts that no longer exist are skipped.
     */
    public suspend fun saveAll(snapshot: Map<EntityId, List<String>>): Unit =
        database.withTransaction {
            dao.clearAll()
            snapshot.forEach { (hostId, entries) -> if (hosts.exists(hostId)) replace(hostId, entries) }
        }

    /** Forgets the history of one host. Returns false if it had none. */
    public suspend fun clear(hostId: EntityId): Boolean = dao.clear(hostId) > 0

    /** Forgets every history. Returns the number of entries removed. */
    public suspend fun clearAll(): Int = dao.clearAll()

    private suspend fun replace(
        hostId: EntityId,
        entries: List<String>,
    ) {
        dao.clear(hostId)
        dao.insert(entries.mapIndexed { position, text -> ComposeHistoryEntity(hostId, position, text) })
    }
}
