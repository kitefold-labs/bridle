package com.kitefoldlabs.bridle.core.backup

import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.model.KnownHost
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.core.model.Snippet
import java.security.MessageDigest
import java.util.Base64

/** Shared test data. Every key and fingerprint here is a throwaway value, not a real secret. */
internal object Fixtures {
    /** The cheapest parameters [Argon2Params] accepts, so tests stay fast. */
    val FAST = Argon2Params(memoryKiB = Argon2Params.MIN_MEMORY_KIB, iterations = 1, parallelism = 1)

    const val PASSPHRASE = "correct horse battery staple"

    /** An SSH wire-format blob: string(keyType) followed by filler bytes. */
    fun keyBlob(
        keyType: String,
        filler: Int,
    ): ByteArray {
        val type = keyType.toByteArray()
        val len = type.size
        val prefix = byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte())
        return prefix + type + ByteArray(32) { (it + filler).toByte() }
    }

    fun knownHost(
        host: String = "devbox.example.test",
        port: Int = 22,
        keyType: String = "ssh-ed25519",
        filler: Int = 1,
    ): KnownHost {
        val blob = keyBlob(keyType, filler)
        return KnownHost(
            host = host,
            port = port,
            keyType = keyType,
            publicKeyBase64 = Base64.getEncoder().encodeToString(blob),
            fingerprintSha256 = fingerprint(blob),
            addedAtEpochMs = 1,
            lastSeenAtEpochMs = 2,
        )
    }

    fun fingerprint(blob: ByteArray): String =
        "SHA256:" +
            Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    fun publicKeyLine(
        filler: Int,
        comment: String = "phone",
    ): String = "ssh-ed25519 " + Base64.getEncoder().encodeToString(keyBlob("ssh-ed25519", filler)) + " " + comment

    fun identity(
        id: Long,
        filler: Int,
        alias: String = "key$id",
        source: KeySource = KeySource.KEYSTORE,
    ): IdentityMetadata = IdentityMetadata(id, alias, KeyAlgorithm.ED25519, publicKeyLine(filler), source)

    fun samplePayload(): BackupPayload =
        BackupPayload(
            createdAtEpochMs = 1_700_000_000_000,
            appVersion = "1.0.0",
            hosts =
                listOf(
                    Host(id = 1, label = "Dev box", hostname = "devbox.example.test", username = "dev", identityId = 7),
                    Host(id = 2, label = "Bastion", hostname = "10.0.0.1", port = 2222, username = "ops", groupId = 3),
                ),
            groups = listOf(Group(id = 3, name = "Work", defaultIdentityId = 7)),
            identities = listOf(identity(7, filler = 9, alias = "Pixel key")),
            launchProfiles = listOf(LaunchProfile.claude(hostId = 1, workingDir = "~/src/app", sessionName = "claude")),
            snippets = listOf(Snippet(id = 4, name = "Disk", body = "df -h {{path:/}}")),
            portForwards = listOf(PortForward(id = 5, hostId = 1, type = PortForwardType.DYNAMIC, bindPort = 1080)),
            knownHosts = listOf(knownHost()),
        )
}
