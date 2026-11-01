package com.kitefoldlabs.bridle.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.model.LaunchMode
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.core.model.RestartMode
import com.kitefoldlabs.bridle.core.model.SnippetRunMode

/*
 * Room entities. They mirror the core:model types one to one (see Mappers.kt) so that the domain
 * model never carries persistence annotations. Enums are stored by name.
 *
 * Deleting a referenced row never deletes a host: identity, jump host and group references are
 * set to NULL. Rows that cannot exist without their host (launch profiles, port forwards,
 * compose history) are deleted with it.
 */

@Entity(
    tableName = "identities",
    indices = [Index("keystoreAlias", unique = true)],
)
internal class IdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val alias: String,
    val source: KeySource,
    val algorithm: KeyAlgorithm,
    val publicKeyOpenSsh: String,
    val keystoreAlias: String?,
    val requireUserAuth: Boolean,
    val authValiditySeconds: Int,
    val createdAtEpochMs: Long,
    /**
     * IMPORTED keys only: AEAD ciphertext of the OpenSSH private key, under a Keystore key.
     * Never read by the list/get queries; see [IdentityRow].
     */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val encryptedPrivateKey: ByteArray?,
)

/** Every [IdentityEntity] column except the encrypted key, so lists never load key material. */
internal data class IdentityRow(
    val id: Long,
    val alias: String,
    val source: KeySource,
    val algorithm: KeyAlgorithm,
    val publicKeyOpenSsh: String,
    val keystoreAlias: String?,
    val requireUserAuth: Boolean,
    val authValiditySeconds: Int,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "host_groups",
    foreignKeys = [
        ForeignKey(
            entity = IdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["defaultIdentityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("defaultIdentityId")],
)
internal data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val name: String,
    val color: Int?,
    val defaultIdentityId: Long?,
    val defaultUsername: String?,
)

@Entity(
    tableName = "hosts",
    foreignKeys = [
        ForeignKey(
            entity = IdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["identityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["jumpHostId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("identityId"), Index("jumpHostId"), Index("groupId")],
)
internal data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val label: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val identityId: Long?,
    val jumpHostId: Long?,
    val groupId: Long?,
    val tags: Set<String>,
    val color: Int?,
    val favorite: Boolean,
    val keepAliveSeconds: Int,
    val defaultTmuxSession: String?,
    val startupCommand: String?,
    val env: Map<String, String>,
    val lastConnectedAtEpochMs: Long?,
)

/** Keyed by host, port and key type; [host] is normalised (see `KnownHostRepository.normalizeHost`). */
@Entity(tableName = "known_hosts", primaryKeys = ["host", "port", "keyType"])
internal data class KnownHostEntity(
    val host: String,
    val port: Int,
    val keyType: String,
    val publicKeyBase64: String,
    val fingerprintSha256: String,
    val addedAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
)

@Entity(
    tableName = "launch_profiles",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("hostId")],
)
internal data class LaunchProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val hostId: Long,
    val name: String,
    val sessionName: String,
    val workingDir: String,
    val command: String,
    val restartMode: RestartMode,
    val restartCommand: String?,
    val env: Map<String, String>,
    val mode: LaunchMode,
    val pinned: Boolean,
)

@Entity(tableName = "snippets")
internal data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val name: String,
    val body: String,
    val runMode: SnippetRunMode,
    val packageName: String?,
)

@Entity(
    tableName = "port_forwards",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("hostId")],
)
internal data class PortForwardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val hostId: Long,
    val type: PortForwardType,
    val bindPort: Int,
    val destHost: String?,
    val destPort: Int?,
    val autoStart: Boolean,
)

/**
 * One compose-box history entry. [position] 0 is the most recent. One row per entry (rather than
 * one JSON blob per host) keeps every row far below Android's 2 MB cursor-window limit.
 */
@Entity(
    tableName = "compose_history",
    primaryKeys = ["hostId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ComposeHistoryEntity(
    val hostId: Long,
    val position: Int,
    val text: String,
)
