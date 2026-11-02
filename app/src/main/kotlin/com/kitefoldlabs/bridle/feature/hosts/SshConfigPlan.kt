package com.kitefoldlabs.bridle.feature.hosts

import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.ssh.SshConfigEntry
import com.kitefoldlabs.bridle.core.ssh.SshConfigImport

/** Something the user should know about one imported entry. */
enum class ImportNote {
    /** A host with this name already exists; it is not imported again (but can be a jump host). */
    EXISTS,

    /** No `User` and no default username: the entry can't be imported. */
    NO_USERNAME,

    /** Its name, address or port isn't valid for Bridle. */
    INVALID,

    /** `IdentityFile` is ignored: import or generate the key under Keys and pick it in the host. */
    IDENTITY_FILE_IGNORED,

    /** A ProxyJump hop names nothing Bridle can connect through; the host connects directly. */
    JUMP_UNRESOLVED,

    /** Bridle hosts have one jump host each; a hop that already has its own jump host ends the chain. */
    JUMP_CHAIN_SHORTENED,

    /** The ProxyJump settings form a loop; this host's jump host was dropped. */
    JUMP_LOOP,
}

/** Where a planned host's jump host comes from. */
sealed interface JumpRef {
    /** Another host in the same import, by [PlannedHost.key]. */
    data class Planned(
        val key: String,
    ) : JumpRef

    /** A host that is already saved. */
    data class Existing(
        val id: EntityId,
    ) : JumpRef
}

/**
 * One host the import would create. [key] is the config alias, or the hop text for hosts created
 * only to serve as a ProxyJump hop (`user@bastion:2222`), for which [fromConfig] is false.
 */
data class PlannedHost(
    val key: String,
    val host: Host,
    val jump: JumpRef?,
    val fromConfig: Boolean,
    val notes: List<ImportNote>,
) {
    /** False for entries that already exist or can't be saved. */
    val importable: Boolean get() =
        ImportNote.EXISTS !in notes &&
            ImportNote.NO_USERNAME !in notes &&
            ImportNote.INVALID !in notes
}

/** What an `ssh_config` import would do, before anything is saved. */
data class ImportPlan(
    val hosts: List<PlannedHost>,
) {
    /** Config entries the user can choose to import. */
    val selectable: List<PlannedHost> get() = hosts.filter { it.fromConfig && it.importable }

    /**
     * Saves the hosts in [selected] (by key) plus the planned jump hosts they need, then links
     * the jump hosts. Returns how many hosts were created.
     */
    suspend fun execute(
        selected: Set<String>,
        repository: HostRepository,
    ): Int {
        val byKey = hosts.associateBy { it.key }
        val chosen = linkedMapOf<String, PlannedHost>()

        fun include(planned: PlannedHost) {
            if (!planned.importable || chosen.put(planned.key, planned) != null) return
            (planned.jump as? JumpRef.Planned)?.let { byKey[it.key] }?.let(::include)
        }
        hosts.filter { it.key in selected }.forEach(::include)
        val ids = chosen.mapValues { (_, planned) -> repository.upsert(planned.host) }
        for ((key, planned) in chosen) {
            val jumpId =
                when (val jump = planned.jump) {
                    is JumpRef.Planned -> ids[jump.key]
                    is JumpRef.Existing -> jump.id
                    else -> null
                }
            if (jumpId != null) repository.upsert(planned.host.copy(id = ids.getValue(key), jumpHostId = jumpId))
        }
        return chosen.size
    }

    companion object {
        /**
         * Plans the import of [import] next to the [existing] hosts. Entries without a `User` get
         * [defaultUsername]. ProxyJump hops resolve to an imported alias, an existing host of
         * that name, or a new host made from a `[user@]host[:port]` hop.
         */
        fun of(
            import: SshConfigImport,
            existing: List<Host>,
            defaultUsername: String,
        ): ImportPlan = Planner(import.entries, existing, defaultUsername.trim()).plan()
    }
}

private class Planner(
    private val entries: List<SshConfigEntry>,
    existing: List<Host>,
    private val defaultUsername: String,
) {
    private val existingByLabel = existing.groupBy { it.label }.mapValues { it.value.first().id }
    private val planned = linkedMapOf<String, PlannedHost>()
    private val entriesByAlias = entries.associateBy { it.alias }

    fun plan(): ImportPlan {
        entries.forEach { planned[it.alias] = fromEntry(it) }
        entries.forEach(::linkJumps)
        breakLoops()
        return ImportPlan(planned.values.toList())
    }

    private fun fromEntry(entry: SshConfigEntry): PlannedHost {
        val user = entry.user ?: defaultUsername
        val host = Host(label = entry.alias, hostname = entry.hostname, port = entry.port, username = user)
        val notes =
            buildList {
                if (entry.alias in existingByLabel) add(ImportNote.EXISTS)
                if (user.isBlank()) {
                    add(ImportNote.NO_USERNAME)
                } else if (host.validate() != Validation.Valid) {
                    add(ImportNote.INVALID)
                }
                if (entry.identityFiles.isNotEmpty()) add(ImportNote.IDENTITY_FILE_IGNORED)
            }
        return PlannedHost(entry.alias, host, jump = null, fromConfig = true, notes = notes)
    }

    /**
     * Resolves [hop] to a jump reference, planning a new host for a `[user@]host[:port]` hop
     * (which logs in as [user] when it names none, as `Host *` defaults usually intend).
     */
    private fun resolve(
        hop: String,
        user: String,
    ): JumpRef? {
        existingByLabel[hop]?.let { return JumpRef.Existing(it) }
        planned[hop]?.let { return if (it.importable) JumpRef.Planned(hop) else null }
        val host = parseHop(hop, user) ?: return null
        planned[hop] = PlannedHost(hop, host, jump = null, fromConfig = false, notes = emptyList())
        return JumpRef.Planned(hop)
    }

    /** `ProxyJump a,b` means a, then b, then the target: target → b → a. */
    private fun linkJumps(entry: SshConfigEntry) {
        val hops = entry.proxyJump
        if (hops.isEmpty()) return
        val refs = hops.map { resolve(it, entry.user ?: defaultUsername) }
        val notes = mutableListOf<ImportNote>()
        if (null in refs) notes += ImportNote.JUMP_UNRESOLVED
        val chained =
            (hops.lastIndex downTo 1).all { index ->
                val hop = refs[index]
                val previous = refs[index - 1]
                hop != null && previous != null && chainThrough(hop, previous, hops[index - 1])
            }
        if (!chained && null !in refs) notes += ImportNote.JUMP_CHAIN_SHORTENED
        val target = planned.getValue(entry.alias)
        planned[entry.alias] = target.copy(jump = refs.last(), notes = target.notes + notes)
    }

    /**
     * Makes [hop] jump through [previous]. Only hosts created for the chain can be changed; a
     * config entry or saved host keeps its own settings. False if the chain can't continue.
     */
    private fun chainThrough(
        hop: JumpRef,
        previous: JumpRef,
        previousText: String,
    ): Boolean {
        if (hop !is JumpRef.Planned) return false
        entriesByAlias[hop.key]?.let { return it.proxyJump.lastOrNull() == previousText }
        val hopHost = planned.getValue(hop.key)
        return when (hopHost.jump) {
            null -> {
                planned[hop.key] = hopHost.copy(jump = previous)
                true
            }
            else -> hopHost.jump == previous
        }
    }

    private fun breakLoops() {
        for (key in planned.keys.toList()) {
            val seen = mutableSetOf(key)
            var next = (planned.getValue(key).jump as? JumpRef.Planned)?.key
            while (next != null) {
                if (!seen.add(next)) {
                    val looping = planned.getValue(key)
                    planned[key] = looping.copy(jump = null, notes = looping.notes + ImportNote.JUMP_LOOP)
                    break
                }
                next = (planned.getValue(next).jump as? JumpRef.Planned)?.key
            }
        }
    }

    /** `[user@]host[:port]` (IPv6 in brackets), as [defaultUser] when no user is given. */
    private fun parseHop(
        hop: String,
        defaultUser: String,
    ): Host? {
        val user = hop.substringBefore('@', missingDelimiterValue = "").ifEmpty { defaultUser }
        val address = hop.substringAfter('@')
        val (hostname, portText) =
            when {
                address.startsWith("[") ->
                    address.substringAfter('[').substringBefore(']') to
                        address.substringAfter("]:", "")
                address.count { it == ':' } == 1 -> address.substringBefore(':') to address.substringAfter(':')
                else -> address to ""
            }
        val port = if (portText.isEmpty()) Host.DEFAULT_SSH_PORT else portText.toIntOrNull() ?: return null
        val host = Host(label = hop, hostname = hostname, port = port, username = user)
        return host.takeIf { it.validate() == Validation.Valid }
    }
}
