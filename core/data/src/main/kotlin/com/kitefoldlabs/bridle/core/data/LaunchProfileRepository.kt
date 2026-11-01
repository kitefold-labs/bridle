package com.kitefoldlabs.bridle.core.data

import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.data.db.toEntity
import com.kitefoldlabs.bridle.core.data.db.toModel
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Launch profiles (tmux session + command presets). They are deleted with their host. */
public class LaunchProfileRepository internal constructor(
    database: BridleDatabase,
) {
    private val dao = database.launchProfileDao()

    /** Every profile: pinned first, then by name. */
    public fun observeAll(): Flow<List<LaunchProfile>> =
        dao.observeAll().map { profiles -> profiles.map { it.toModel() } }

    /** The profiles of one host: pinned first, then by name. */
    public fun observeForHost(hostId: EntityId): Flow<List<LaunchProfile>> =
        dao.observeForHost(hostId).map { profiles -> profiles.map { it.toModel() } }

    public suspend fun get(id: EntityId): LaunchProfile? = dao.get(id)?.toModel()

    /**
     * Inserts [profile] (when its id is 0) or updates it, and returns its id.
     *
     * @throws InvalidEntityException if [LaunchProfile.validate] fails.
     * @throws android.database.sqlite.SQLiteConstraintException if its host does not exist.
     */
    public suspend fun upsert(profile: LaunchProfile): EntityId {
        profile.validate().requireValid()
        return upsertedId(profile.id, dao.upsert(profile.toEntity()))
    }

    public suspend fun delete(id: EntityId): Boolean = dao.delete(id) > 0
}
