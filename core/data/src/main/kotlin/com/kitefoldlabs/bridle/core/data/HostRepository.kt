package com.kitefoldlabs.bridle.core.data

import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.data.db.toEntity
import com.kitefoldlabs.bridle.core.data.db.toModel
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Saved hosts. Deleting a host also deletes its launch profiles, port forwards and compose history. */
public class HostRepository internal constructor(
    database: BridleDatabase,
) {
    private val dao = database.hostDao()

    /** Every host: favourites first, then most recently connected, then the rest by label. */
    public fun observeAll(): Flow<List<Host>> = dao.observeAll().map { hosts -> hosts.map { it.toModel() } }

    /** The host with [id], or null while there is none. */
    public fun observe(id: EntityId): Flow<Host?> = dao.observe(id).map { it?.toModel() }

    public suspend fun get(id: EntityId): Host? = dao.get(id)?.toModel()

    /**
     * Inserts [host] (when its id is 0) or updates it, and returns its id.
     *
     * @throws InvalidEntityException if [Host.validate] fails.
     * @throws android.database.sqlite.SQLiteConstraintException if the identity, jump host or
     *   group it refers to does not exist.
     */
    public suspend fun upsert(host: Host): EntityId {
        host.validate().requireValid()
        return upsertedId(host.id, dao.upsert(host.toEntity()))
    }

    /** Returns false if there was no such host. Hosts that used it as a jump host lose the reference. */
    public suspend fun delete(id: EntityId): Boolean = dao.delete(id) > 0

    /** Records a successful connection, which moves the host up in [observeAll]. */
    public suspend fun markConnected(
        id: EntityId,
        atEpochMs: Long,
    ): Boolean = dao.markConnected(id, atEpochMs) > 0
}
