package com.kitefoldlabs.bridle.core.backup

import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.model.KnownHost
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.Snippet
import com.kitefoldlabs.bridle.core.model.Validation

/** What is already on this device, as read by the data layer before an import. */
public data class ExistingData(
    val hosts: List<Host> = emptyList(),
    val groups: List<Group> = emptyList(),
    val identities: List<Identity> = emptyList(),
    val launchProfiles: List<LaunchProfile> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val portForwards: List<PortForward> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
)

public enum class EntityKind { GROUP, HOST, IDENTITY, LAUNCH_PROFILE, SNIPPET, PORT_FORWARD, KNOWN_HOST }

/** Something the user should be told about after an import. [label] is the entity's display name. */
public sealed interface ImportIssue {
    public val kind: EntityKind
    public val label: String

    /** An equivalent entity already exists (or appears earlier in the backup); the existing one is kept. */
    public data class Duplicate(
        override val kind: EntityKind,
        override val label: String,
    ) : ImportIssue

    /**
     * The backup trusts a different key for a host this device already trusts. The existing key is
     * kept: a trusted host key is never replaced silently, nor joined by a key of another type.
     * Show both fingerprints. [keyType] is the type of the backup's key; [existingFingerprint] is
     * the trusted key of that type or, if this device trusts only other types for the host, one
     * of those.
     */
    public data class KnownHostKeyConflict(
        val host: String,
        val port: Int,
        val keyType: String,
        val existingFingerprint: String,
        val backupFingerprint: String,
    ) : ImportIssue {
        override val kind: EntityKind get() = EntityKind.KNOWN_HOST
        override val label: String get() = knownHostLabel(host, port, keyType)
    }

    /**
     * A key the backup refers to does not exist on this device (private keys are never exported).
     * References to it were cleared; the user must generate or import a key and install it on
     * [usedBy] (host labels, then group names).
     */
    public data class MissingIdentity(
        override val label: String,
        val algorithm: KeyAlgorithm,
        val source: KeySource,
        val publicKeyOpenSsh: String,
        val usedBy: List<String>,
    ) : ImportIssue {
        override val kind: EntityKind get() = EntityKind.IDENTITY
    }

    /** The entity failed validation and was not imported. */
    public data class Invalid(
        override val kind: EntityKind,
        override val label: String,
        val problem: Validation.Invalid,
    ) : ImportIssue

    /** A launch profile or port forward whose host is not being imported; it was dropped. */
    public data class Orphaned(
        override val kind: EntityKind,
        override val label: String,
    ) : ImportIssue

    /** An optional reference ([field]) pointed nowhere, or formed a jump-host cycle, and was cleared. */
    public data class ReferenceCleared(
        override val kind: EntityKind,
        override val label: String,
        val field: String,
    ) : ImportIssue
}

/** Persists the entities of an [ImportPlan]; implemented by the data layer, ideally in one transaction. */
public interface ImportSink {
    /** Inserts [group] (whose id is 0) and returns its new id. */
    public fun insertGroup(group: Group): EntityId

    /** Inserts [host] (whose id is 0) and returns its new id. */
    public fun insertHost(host: Host): EntityId

    public fun insertLaunchProfile(profile: LaunchProfile)

    public fun insertSnippet(snippet: Snippet)

    public fun insertPortForward(portForward: PortForward)

    public fun insertKnownHost(knownHost: KnownHost)
}

/**
 * The result of [BackupMerge.plan]: entities to insert, and what the user should be told.
 *
 * Groups and hosts to insert carry **provisional** ids (negative, see [isProvisional]); any
 * reference with a provisional id points at another entity of this plan. Positive ids refer to
 * existing rows. [hosts] is ordered so that a host's provisional jump host always comes first.
 * Use [apply] rather than inserting by hand: it swaps provisional ids for the real ones.
 */
public data class ImportPlan(
    val groups: List<Group>,
    val hosts: List<Host>,
    val launchProfiles: List<LaunchProfile>,
    val snippets: List<Snippet>,
    val portForwards: List<PortForward>,
    val knownHosts: List<KnownHost>,
    val issues: List<ImportIssue>,
) {
    /** True when there is nothing to insert. */
    val isEmpty: Boolean
        get() =
            groups.isEmpty() &&
                hosts.isEmpty() &&
                launchProfiles.isEmpty() &&
                snippets.isEmpty() &&
                portForwards.isEmpty() &&
                knownHosts.isEmpty()

    /** Inserts everything into [sink] in dependency order, resolving provisional ids. */
    public fun apply(sink: ImportSink) {
        val groupIds = mutableMapOf<EntityId, EntityId>()
        val hostIds = mutableMapOf<EntityId, EntityId>()

        fun EntityId.resolve(ids: Map<EntityId, EntityId>): EntityId =
            if (isProvisional(this)) ids.getValue(this) else this

        groups.forEach { groupIds[it.id] = sink.insertGroup(it.copy(id = 0)) }
        hosts.forEach { host ->
            hostIds[host.id] =
                sink.insertHost(
                    host.copy(
                        id = 0,
                        groupId = host.groupId?.resolve(groupIds),
                        jumpHostId = host.jumpHostId?.resolve(hostIds),
                    ),
                )
        }
        launchProfiles.forEach { sink.insertLaunchProfile(it.copy(hostId = it.hostId.resolve(hostIds))) }
        snippets.forEach(sink::insertSnippet)
        portForwards.forEach { sink.insertPortForward(it.copy(hostId = it.hostId.resolve(hostIds))) }
        knownHosts.forEach(sink::insertKnownHost)
    }

    public companion object {
        /** Whether [id] is a provisional id of an entity inside a plan. */
        public fun isProvisional(id: EntityId): Boolean = id < 0
    }
}

internal fun knownHostLabel(
    host: String,
    port: Int,
    keyType: String,
): String = "$host:$port $keyType"
