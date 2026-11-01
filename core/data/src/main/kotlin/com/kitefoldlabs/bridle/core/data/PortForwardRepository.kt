package com.kitefoldlabs.bridle.core.data

import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.data.db.toEntity
import com.kitefoldlabs.bridle.core.data.db.toModel
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.PortForward
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Saved port forwards. They are deleted with their host. */
public class PortForwardRepository internal constructor(
    database: BridleDatabase,
) {
    private val dao = database.portForwardDao()

    /** The forwards of one host, by bind port. */
    public fun observeForHost(hostId: EntityId): Flow<List<PortForward>> =
        dao.observeForHost(hostId).map { forwards -> forwards.map { it.toModel() } }

    public suspend fun get(id: EntityId): PortForward? = dao.get(id)?.toModel()

    /**
     * Inserts [portForward] (when its id is 0) or updates it, and returns its id.
     *
     * @throws InvalidEntityException if [PortForward.validate] fails.
     * @throws android.database.sqlite.SQLiteConstraintException if its host does not exist.
     */
    public suspend fun upsert(portForward: PortForward): EntityId {
        portForward.validate().requireValid()
        return upsertedId(portForward.id, dao.upsert(portForward.toEntity()))
    }

    public suspend fun delete(id: EntityId): Boolean = dao.delete(id) > 0
}
