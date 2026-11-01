package com.kitefoldlabs.bridle.core.data

import androidx.room.Room
import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.model.KnownHost
import org.robolectric.RuntimeEnvironment
import java.security.MessageDigest
import java.util.Base64

/** Shared test data. Every key and fingerprint here is a throwaway value, not a real secret. */
internal object Fixtures {
    fun database(): BridleDatabase =
        Room
            .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BridleDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    fun host(
        label: String = "Dev box",
        hostname: String = "devbox.example.test",
        username: String = "dev",
    ): Host = Host(label = label, hostname = hostname, username = username)

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

    fun fingerprint(blob: ByteArray): String =
        "SHA256:" +
            Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    fun knownHost(
        host: String = "devbox.example.test",
        port: Int = 22,
        keyType: String = "ssh-ed25519",
        filler: Int = 1,
        addedAt: Long = 1,
    ): KnownHost {
        val blob = keyBlob(keyType, filler)
        return KnownHost(
            host = host,
            port = port,
            keyType = keyType,
            publicKeyBase64 = Base64.getEncoder().encodeToString(blob),
            fingerprintSha256 = fingerprint(blob),
            addedAtEpochMs = addedAt,
            lastSeenAtEpochMs = addedAt,
        )
    }

    fun publicKeyLine(filler: Int): String =
        "ssh-ed25519 " + Base64.getEncoder().encodeToString(keyBlob("ssh-ed25519", filler)) + " phone"

    fun identity(
        alias: String = "Pixel key",
        filler: Int = 9,
        keystoreAlias: String? = "bridle-key-$filler",
    ): Identity =
        Identity(
            alias = alias,
            source = if (keystoreAlias == null) KeySource.IMPORTED else KeySource.KEYSTORE,
            algorithm = KeyAlgorithm.ED25519,
            publicKeyOpenSsh = publicKeyLine(filler),
            keystoreAlias = keystoreAlias,
            requireUserAuth = true,
            authValiditySeconds = 60,
            createdAtEpochMs = 1_700_000_000_000,
        )
}
