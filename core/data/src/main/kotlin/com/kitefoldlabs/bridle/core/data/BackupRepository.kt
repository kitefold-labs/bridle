package com.kitefoldlabs.bridle.core.data

import androidx.room.withTransaction
import com.kitefoldlabs.bridle.core.backup.BackupMerge
import com.kitefoldlabs.bridle.core.backup.BackupPayload
import com.kitefoldlabs.bridle.core.backup.ExistingData
import com.kitefoldlabs.bridle.core.backup.IdentityMetadata
import com.kitefoldlabs.bridle.core.backup.ImportPlan
import com.kitefoldlabs.bridle.core.backup.ImportSink
import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.data.db.toEntity
import com.kitefoldlabs.bridle.core.data.db.toModel
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.KnownHost
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.Snippet

/**
 * Reads and writes the data that `core:backup` exports and imports. Encryption and merge
 * planning live in `core:backup`; this class only provides consistent snapshots and applies an
 * [ImportPlan] atomically.
 */
public class BackupRepository internal constructor(
    private val database: BridleDatabase,
) {
    /** A consistent snapshot of everything on the device, for [BackupMerge.plan]. */
    public suspend fun existingData(): ExistingData = database.withTransaction { snapshot() }

    /**
     * Everything a backup carries. Identities are reduced to [IdentityMetadata]: the encrypted
     * private keys are never exported. Compose history is not part of a backup.
     */
    public suspend fun export(
        createdAtEpochMs: Long,
        appVersion: String,
    ): BackupPayload {
        val data = existingData()
        return BackupPayload(
            createdAtEpochMs = createdAtEpochMs,
            appVersion = appVersion,
            hosts = data.hosts,
            groups = data.groups,
            identities = data.identities.map(IdentityMetadata::from),
            launchProfiles = data.launchProfiles,
            snippets = data.snippets,
            portForwards = data.portForwards,
            knownHosts = data.knownHosts,
        )
    }

    /**
     * Inserts everything in [plan] in one transaction: either all of it is stored or, if any
     * insert fails, none of it. Use this after showing the plan's issues to the user; the plan
     * should be fresh (made from [existingData] just before), or duplicates may slip in.
     */
    public suspend fun apply(plan: ImportPlan): Unit = database.withTransaction { plan.apply(sink) }

    /** Plans and applies the import of [backup] in one transaction, and returns the plan applied. */
    public suspend fun import(backup: BackupPayload): ImportPlan =
        database.withTransaction {
            BackupMerge.plan(snapshot(), backup).also { it.apply(sink) }
        }

    private suspend fun snapshot(): ExistingData =
        ExistingData(
            hosts = database.hostDao().getAll().map { it.toModel() },
            groups = database.groupDao().getAll().map { it.toModel() },
            identities = database.identityDao().getAll().map { it.toModel() },
            launchProfiles = database.launchProfileDao().getAll().map { it.toModel() },
            snippets = database.snippetDao().getAll().map { it.toModel() },
            portForwards = database.portForwardDao().getAll().map { it.toModel() },
            knownHosts = database.knownHostDao().getAll().map { it.toModel() },
        )

    /** Synchronous inserts; only ever called on Room's transaction thread, inside [apply] or [import]. */
    private val sink =
        object : ImportSink {
            override fun insertGroup(group: Group): EntityId = database.groupDao().insertBlocking(group.toEntity())

            override fun insertHost(host: Host): EntityId = database.hostDao().insertBlocking(host.toEntity())

            override fun insertLaunchProfile(profile: LaunchProfile) {
                database.launchProfileDao().insertBlocking(profile.toEntity())
            }

            override fun insertSnippet(snippet: Snippet) {
                database.snippetDao().insertBlocking(snippet.toEntity())
            }

            override fun insertPortForward(portForward: PortForward) {
                database.portForwardDao().insertBlocking(portForward.toEntity())
            }

            override fun insertKnownHost(knownHost: KnownHost) {
                // IGNORE on conflict: a key this device already trusts is never replaced by a backup.
                database.knownHostDao().insertBlocking(
                    knownHost.toEntity(KnownHostRepository.normalizeHost(knownHost.host)),
                )
            }
        }
}
