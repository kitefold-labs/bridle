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
import kotlinx.serialization.Serializable

/**
 * Everything an encrypted backup carries: the user's configuration, and nothing secret.
 *
 * **Private keys are not exported, by design.** Keys generated in the Android Keystore cannot leave
 * the secure hardware, and imported keys are deliberately never re-exported (see SECURITY.md). A
 * backup therefore holds only [IdentityMetadata] (alias, algorithm, public key, source) so that,
 * on the new phone, hosts can be re-linked to a key with the same public key, and the user can be
 * told exactly which keys to re-create or re-import. Passwords are never stored, so they are never
 * exported either.
 *
 * Note that user-entered values such as [Host.env], [Host.startupCommand] and
 * [LaunchProfile.env] *are* included; they are protected by the backup passphrase.
 *
 * Entity ids are those of the exporting device. They are used only to resolve references inside
 * the backup and are never trusted on import (see [BackupMerge]).
 */
@Serializable
public data class BackupPayload(
    /** Schema version of this payload, independent of the file container's version byte. */
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val createdAtEpochMs: Long,
    /** Version name of the app that wrote the backup, for diagnostics only. */
    val appVersion: String,
    val hosts: List<Host> = emptyList(),
    val groups: List<Group> = emptyList(),
    val identities: List<IdentityMetadata> = emptyList(),
    val launchProfiles: List<LaunchProfile> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val portForwards: List<PortForward> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
) {
    public companion object {
        /** The newest payload schema this build reads and the one it writes. */
        public const val CURRENT_FORMAT_VERSION: Int = 1
    }
}

/**
 * The exportable, non-secret part of an [Identity]. There is deliberately no field that could
 * hold private key material, a Keystore handle or a password.
 */
@Serializable
public data class IdentityMetadata(
    val id: EntityId,
    val alias: String,
    val algorithm: KeyAlgorithm,
    /** One-line OpenSSH public key, e.g. `ssh-ed25519 AAAA… comment`. */
    val publicKeyOpenSsh: String,
    val source: KeySource,
) {
    public companion object {
        /** Strips everything but the public metadata from [identity]. */
        public fun from(identity: Identity): IdentityMetadata =
            IdentityMetadata(
                id = identity.id,
                alias = identity.alias,
                algorithm = identity.algorithm,
                publicKeyOpenSsh = identity.publicKeyOpenSsh,
                source = identity.source,
            )
    }
}
