package com.kitefoldlabs.bridle.core.backup

import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.KnownHost
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.Snippet
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.model.Validators
import java.security.MessageDigest
import java.util.Base64

/**
 * Plans the import of a [BackupPayload] into a device that may already have data. Pure: it reads
 * nothing and writes nothing; the data layer applies the returned [ImportPlan].
 *
 * Rules:
 * - **Backup ids are not trusted.** Every reference (host → identity / jump host / group,
 *   group → identity, profile and port forward → host) is remapped to an existing row or to an
 *   entity of the plan. Optional references that resolve to nothing are cleared and reported;
 *   profiles and port forwards whose host is missing are dropped.
 * - **Duplicates keep the existing entity.** Groups match by name (ignoring case), hosts by
 *   hostname (ignoring case) + port + username, profiles by host + tmux session name, snippets by
 *   name + body, port forwards by host + type + ports + destination, known hosts by host + port +
 *   key type.
 * - **Identities are matched, never created.** A backup identity maps to an existing identity
 *   with the same public key (type and base64, ignoring the comment); otherwise it is reported as
 *   [ImportIssue.MissingIdentity].
 * - **Trusted host keys are never replaced or joined.** Once this device trusts a host (host +
 *   port), a backup key for it that is not already trusted, whether of the same or of another
 *   type, is reported as [ImportIssue.KnownHostKeyConflict] and only the existing key stays. This
 *   matches the TOFU verifier, which treats an unknown key type for a known host as changed.
 * - **Imported data passes the same validation as user input**, and known-host fingerprints are
 *   recomputed from the key; invalid entities are reported and skipped.
 */
public object BackupMerge {
    public fun plan(
        existing: ExistingData,
        backup: BackupPayload,
    ): ImportPlan = Planner(existing, backup).run()
}

private class Planner(
    private val existing: ExistingData,
    private val backup: BackupPayload,
) {
    private val issues = mutableListOf<ImportIssue>()
    private val identityIds = mutableMapOf<EntityId, EntityId>()
    private val missingIdentityIds = mutableSetOf<EntityId>()
    private val groupIds = mutableMapOf<EntityId, EntityId>()
    private val hostIds = mutableMapOf<EntityId, EntityId>()

    fun run(): ImportPlan {
        planIdentities()
        val groups = planGroups()
        val hosts = planHosts()
        return ImportPlan(
            groups = groups,
            hosts = hosts,
            launchProfiles = planProfiles(),
            snippets = planSnippets(),
            portForwards = planPortForwards(),
            knownHosts = planKnownHosts(),
            issues = issues.toList(),
        )
    }

    private fun planIdentities() {
        val byKey =
            existing.identities
                .mapNotNull { identity -> publicKeyId(identity.publicKeyOpenSsh)?.let { it to identity.id } }
                .toMap()
        backup.identities.forEach { identity ->
            val match = publicKeyId(identity.publicKeyOpenSsh)?.let(byKey::get)
            when {
                identity.id in identityIds || identity.id in missingIdentityIds -> Unit
                match != null -> identityIds[identity.id] = match
                else -> {
                    missingIdentityIds += identity.id
                    val usedBy =
                        backup.hosts.filter { it.identityId == identity.id }.map { it.label } +
                            backup.groups.filter { it.defaultIdentityId == identity.id }.map { it.name }
                    issues +=
                        ImportIssue.MissingIdentity(
                            label = identity.alias,
                            algorithm = identity.algorithm,
                            source = identity.source,
                            publicKeyOpenSsh = identity.publicKeyOpenSsh,
                            usedBy = usedBy,
                        )
                }
            }
        }
    }

    /** Maps a backup identity reference; missing identities are already reported, unknown ids are cleared. */
    private fun identity(
        ref: EntityId?,
        kind: EntityKind,
        label: String,
        field: String,
    ): EntityId? {
        if (ref == null || ref in missingIdentityIds) return null
        return identityIds[ref] ?: run {
            issues += ImportIssue.ReferenceCleared(kind, label, field)
            null
        }
    }

    private fun planGroups(): List<Group> {
        val byName = existing.groups.associateBy({ groupKey(it.name) }, { it.id }).toMutableMap()
        val planned = mutableListOf<Group>()
        backup.groups.forEach { group ->
            val problem =
                Validators.all(
                    Validators.label(group.name),
                    group.defaultUsername?.let(Validators::username) ?: Validation.Valid,
                )
            val match = byName[groupKey(group.name)]
            when {
                problem is Validation.Invalid -> issues += ImportIssue.Invalid(EntityKind.GROUP, group.name, problem)
                match != null -> {
                    groupIds.putIfAbsent(group.id, match)
                    issues += ImportIssue.Duplicate(EntityKind.GROUP, group.name)
                }
                else -> {
                    val id = provisional(planned.size)
                    groupIds.putIfAbsent(group.id, id)
                    byName[groupKey(group.name)] = id
                    planned +=
                        group.copy(
                            id = id,
                            defaultIdentityId =
                                identity(group.defaultIdentityId, EntityKind.GROUP, group.name, "defaultIdentityId"),
                        )
                }
            }
        }
        return planned
    }

    private fun planHosts(): List<Host> {
        val byKey = existing.hosts.associateBy(::hostKey) { it.id }.toMutableMap()
        // New hosts with their *backup* jump-host reference, resolved once every host has an id.
        val pending = mutableListOf<Host>()
        backup.hosts.forEach { host ->
            val problem = host.validate()
            val match = byKey[hostKey(host)]
            when {
                problem is Validation.Invalid -> issues += ImportIssue.Invalid(EntityKind.HOST, host.label, problem)
                match != null -> {
                    hostIds.putIfAbsent(host.id, match)
                    issues += ImportIssue.Duplicate(EntityKind.HOST, host.label)
                }
                else -> {
                    val id = provisional(pending.size)
                    hostIds.putIfAbsent(host.id, id)
                    byKey[hostKey(host)] = id
                    pending +=
                        host.copy(
                            id = id,
                            identityId = identity(host.identityId, EntityKind.HOST, host.label, "identityId"),
                            groupId = reference(host.groupId, groupIds, EntityKind.HOST, host.label, "groupId"),
                        )
                }
            }
        }
        return orderByJumpHost(pending)
    }

    /**
     * Resolves jump hosts and orders [pending] so that each host's provisional jump host is
     * inserted before it. Cycles (including a host jumping through itself) are broken by clearing
     * the jump host of one host *on* the cycle; hosts that merely jump into a cycle keep theirs.
     */
    private fun orderByJumpHost(pending: List<Host>): List<Host> {
        val remaining =
            pending
                .map { host ->
                    host.copy(
                        jumpHostId = reference(host.jumpHostId, hostIds, EntityKind.HOST, host.label, "jumpHostId"),
                    )
                }.toMutableList()
        val placed = mutableSetOf<EntityId>()
        val ordered = mutableListOf<Host>()
        while (remaining.isNotEmpty()) {
            val ready =
                remaining.firstOrNull { host ->
                    host.jumpHostId.let { it == null || !ImportPlan.isProvisional(it) || it in placed }
                }
            val next =
                ready ?: onCycle(remaining).let { stuck ->
                    issues += ImportIssue.ReferenceCleared(EntityKind.HOST, stuck.label, "jumpHostId")
                    stuck.copy(jumpHostId = null)
                }
            remaining.removeAll { it.id == next.id }
            placed += next.id
            ordered += next
        }
        return ordered
    }

    /**
     * A host on a jump-host cycle among [stuck]. Every host in [stuck] waits for another host in
     * [stuck], so following jump hosts from any of them must revisit one, which is on the cycle.
     */
    private fun onCycle(stuck: List<Host>): Host {
        val byId = stuck.associateBy<Host, EntityId?> { it.id }
        val visited = mutableSetOf<EntityId>()
        var host = stuck.first()
        while (visited.add(host.id)) host = byId.getValue(host.jumpHostId)
        return host
    }

    private fun planProfiles(): List<LaunchProfile> {
        val seen = existing.launchProfiles.mapTo(mutableSetOf()) { it.hostId to it.sessionName }
        return backup.launchProfiles.mapNotNull { profile ->
            val hostId = owner(profile.hostId, EntityKind.LAUNCH_PROFILE, profile.name)
            val mapped = hostId?.let { profile.copy(id = 0, hostId = it) }
            val problem = profile.validate()
            when {
                mapped == null -> null
                problem is Validation.Invalid -> {
                    issues += ImportIssue.Invalid(EntityKind.LAUNCH_PROFILE, profile.name, problem)
                    null
                }
                !seen.add(mapped.hostId to mapped.sessionName) -> {
                    issues += ImportIssue.Duplicate(EntityKind.LAUNCH_PROFILE, profile.name)
                    null
                }
                else -> mapped
            }
        }
    }

    private fun planSnippets(): List<Snippet> {
        val seen = existing.snippets.mapTo(mutableSetOf()) { it.name to it.body }
        return backup.snippets.mapNotNull { snippet ->
            when {
                snippet.name.isBlank() -> {
                    issues +=
                        ImportIssue.Invalid(
                            EntityKind.SNIPPET,
                            snippet.name,
                            Validation.Invalid("name", Validation.Reason.BLANK),
                        )
                    null
                }
                !seen.add(snippet.name to snippet.body) -> {
                    issues += ImportIssue.Duplicate(EntityKind.SNIPPET, snippet.name)
                    null
                }
                else -> snippet.copy(id = 0)
            }
        }
    }

    private fun planPortForwards(): List<PortForward> {
        val seen = existing.portForwards.mapTo(mutableSetOf(), ::forwardKey)
        return backup.portForwards.mapNotNull { forward ->
            val label = "${forward.type.name.lowercase()} :${forward.bindPort}"
            val mapped =
                owner(forward.hostId, EntityKind.PORT_FORWARD, label)?.let { forward.copy(id = 0, hostId = it) }
            val problem = forward.validate()
            when {
                mapped == null -> null
                problem is Validation.Invalid -> {
                    issues += ImportIssue.Invalid(EntityKind.PORT_FORWARD, label, problem)
                    null
                }
                !seen.add(forwardKey(mapped)) -> {
                    issues += ImportIssue.Duplicate(EntityKind.PORT_FORWARD, label)
                    null
                }
                else -> mapped
            }
        }
    }

    private fun planKnownHosts(): List<KnownHost> {
        val onDevice = existing.knownHosts.groupBy(::hostPortKey)
        val planned = mutableMapOf<Triple<String, Int, String>, KnownHost>()
        backup.knownHosts.forEach { candidate ->
            when (val checked = KnownHostCheck.check(candidate)) {
                is KnownHostCheck.Result.Bad -> {
                    val label = knownHostLabel(candidate.host, candidate.port, candidate.keyType)
                    issues += ImportIssue.Invalid(EntityKind.KNOWN_HOST, label, checked.problem)
                }
                is KnownHostCheck.Result.Good -> {
                    val accepted = checked.knownHost
                    // Like TofuHostKeyVerifier: once this device trusts a host, a key of any
                    // other type (or value) for it is a conflict, never an addition.
                    val trusted = onDevice[hostPortKey(accepted)] ?: listOfNotNull(planned[knownHostKey(accepted)])
                    val current = trusted.firstOrNull { it.keyType == accepted.keyType } ?: trusted.firstOrNull()
                    when {
                        current == null -> planned[knownHostKey(accepted)] = accepted
                        current.keyType == accepted.keyType && current.publicKeyBase64 == accepted.publicKeyBase64 ->
                            issues +=
                                ImportIssue.Duplicate(
                                    EntityKind.KNOWN_HOST,
                                    knownHostLabel(accepted.host, accepted.port, accepted.keyType),
                                )
                        else ->
                            issues +=
                                ImportIssue.KnownHostKeyConflict(
                                    host = current.host,
                                    port = current.port,
                                    keyType = accepted.keyType,
                                    existingFingerprint = current.fingerprintSha256,
                                    backupFingerprint = accepted.fingerprintSha256,
                                )
                    }
                }
            }
        }
        return planned.values.toList()
    }

    /** The new id of a profile's or port forward's host, or null (reported) if it is not imported. */
    private fun owner(
        backupHostId: EntityId,
        kind: EntityKind,
        label: String,
    ): EntityId? =
        hostIds[backupHostId] ?: run {
            issues += ImportIssue.Orphaned(kind, label)
            null
        }

    private fun reference(
        ref: EntityId?,
        ids: Map<EntityId, EntityId>,
        kind: EntityKind,
        label: String,
        field: String,
    ): EntityId? {
        if (ref == null) return null
        return ids[ref] ?: run {
            issues += ImportIssue.ReferenceCleared(kind, label, field)
            null
        }
    }

    private fun provisional(index: Int): EntityId = -(index + 1L)

    private fun groupKey(name: String) = name.trim().lowercase()

    private fun hostKey(host: Host) =
        Triple(host.hostname.removeSurrounding("[", "]").lowercase(), host.port, host.username)

    private fun knownHostKey(knownHost: KnownHost) =
        Triple(knownHost.host.lowercase(), knownHost.port, knownHost.keyType)

    private fun hostPortKey(knownHost: KnownHost) = knownHost.host.lowercase() to knownHost.port

    private fun forwardKey(forward: PortForward) =
        listOf(forward.hostId, forward.type, forward.bindPort, forward.destHost?.lowercase(), forward.destPort)

    /** `type base64`, without the comment; null for a line without both, which matches nothing. */
    private fun publicKeyId(line: String) =
        line
            .trim()
            .split(Regex("\\s+"))
            .take(2)
            .takeIf { it.size == 2 }
            ?.joinToString(" ")
}

/** Integrity checks for an imported [KnownHost]; the stored fingerprint is never taken on trust. */
internal object KnownHostCheck {
    sealed interface Result {
        data class Good(
            val knownHost: KnownHost,
        ) : Result

        data class Bad(
            val problem: Validation.Invalid,
        ) : Result
    }

    private const val LENGTH_BYTES = 4
    private const val BYTE_MASK = 0xFFL

    fun check(knownHost: KnownHost): Result {
        val blob =
            try {
                Base64.getDecoder().decode(knownHost.publicKeyBase64)
            } catch (ignored: IllegalArgumentException) {
                null
            }
        val fingerprint = blob?.let(::fingerprint)
        val problem =
            Validators.all(
                Validators.hostname(knownHost.host),
                Validators.port(knownHost.port),
                when {
                    blob == null -> invalid("publicKeyBase64")
                    blobType(blob) != knownHost.keyType -> invalid("keyType")
                    fingerprint != knownHost.fingerprintSha256 -> invalid("fingerprintSha256")
                    else -> Validation.Valid
                },
            )
        return when (problem) {
            is Validation.Invalid -> Result.Bad(problem)
            Validation.Valid -> Result.Good(knownHost.copy(publicKeyBase64 = Base64.getEncoder().encodeToString(blob)))
        }
    }

    /** The key type named at the start of an SSH wire-format public key blob, or null. */
    fun blobType(blob: ByteArray): String? {
        if (blob.size < LENGTH_BYTES) return null
        val length =
            (0 until LENGTH_BYTES).fold(0L) { acc, i -> (acc shl Byte.SIZE_BITS) or (blob[i].toLong() and BYTE_MASK) }
        if (length > blob.size - LENGTH_BYTES) return null
        return String(blob, LENGTH_BYTES, length.toInt(), Charsets.UTF_8)
    }

    fun fingerprint(blob: ByteArray): String =
        "SHA256:" +
            Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    private fun invalid(field: String) = Validation.Invalid(field, Validation.Reason.ILLEGAL_CHARACTERS)
}
