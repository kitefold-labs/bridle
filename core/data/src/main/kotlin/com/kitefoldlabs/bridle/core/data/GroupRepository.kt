package com.kitefoldlabs.bridle.core.data

import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.data.db.toEntity
import com.kitefoldlabs.bridle.core.data.db.toModel
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Validators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Host groups. Deleting a group keeps its hosts and clears their group. */
public class GroupRepository internal constructor(
    database: BridleDatabase,
) {
    private val dao = database.groupDao()

    /** Every group, by name. */
    public fun observeAll(): Flow<List<Group>> = dao.observeAll().map { groups -> groups.map { it.toModel() } }

    public suspend fun get(id: EntityId): Group? = dao.get(id)?.toModel()

    /**
     * Inserts [group] (when its id is 0) or updates it, and returns its id.
     *
     * @throws InvalidEntityException if the name is not a valid label.
     */
    public suspend fun upsert(group: Group): EntityId {
        Validators.label(group.name).requireValid()
        return upsertedId(group.id, dao.upsert(group.toEntity()))
    }

    public suspend fun delete(id: EntityId): Boolean = dao.delete(id) > 0
}
