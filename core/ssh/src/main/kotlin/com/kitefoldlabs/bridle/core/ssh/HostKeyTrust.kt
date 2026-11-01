package com.kitefoldlabs.bridle.core.ssh

import com.kitefoldlabs.bridle.core.model.KnownHost
import java.security.PublicKey
import java.util.Base64

/** Persistence for trusted host keys (implemented by the data layer). */
public interface KnownHostsStore {
    public fun find(
        host: String,
        port: Int,
    ): List<KnownHost>

    public fun trust(knownHost: KnownHost)

    public fun touch(
        host: String,
        port: Int,
        keyType: String,
        nowEpochMs: Long,
    )
}

/** What the UI is asked when a host is seen for the first time. */
public data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprintSha256: String,
)

/** Asks the user whether to trust a new host key. Called from an I/O thread; may block. */
public fun interface HostKeyPrompter {
    public fun confirmNewHost(prompt: HostKeyPrompt): Boolean
}

/** Decides whether a server's host key is acceptable. */
public interface HostKeyVerifier {
    /**
     * Key types already trusted for this host, most preferred first. The engine offers these
     * host-key algorithms first so a server with several keys presents the one we know (as
     * OpenSSH does), instead of producing a spurious "key changed" error.
     */
    public fun knownKeyTypes(
        host: String,
        port: Int,
    ): List<String> = emptyList()

    /** @throws HostKeyException when the key must not be accepted. */
    public fun verify(
        host: String,
        port: Int,
        key: PublicKey,
    )
}

public sealed class HostKeyException(
    message: String,
) : Exception(message)

/** The user declined to trust a new host. */
public class HostKeyRejectedException(
    public val prompt: HostKeyPrompt,
) : HostKeyException("Host key for ${prompt.host}:${prompt.port} was not trusted")

/**
 * The server presented a different key than the one previously trusted. This is how a
 * man-in-the-middle attack looks, so the connection is refused; the UI shows both fingerprints
 * and requires an explicit "replace key" action.
 */
public class HostKeyChangedException(
    public val host: String,
    public val port: Int,
    public val keyType: String,
    public val expectedFingerprints: List<String>,
    public val actualFingerprint: String,
) : HostKeyException("HOST KEY CHANGED for $host:$port — possible man-in-the-middle attack")

/**
 * Trust-on-first-use verifier.
 *
 * - Known host, matching key → accept (and record last-seen time).
 * - Known host, key of the same type differs → [HostKeyChangedException].
 * - Known host but only other key types trusted → treated as changed too (no silent downgrade).
 * - Unknown host → ask [prompter]; trust and accept, or throw [HostKeyRejectedException].
 */
public class TofuHostKeyVerifier(
    private val store: KnownHostsStore,
    private val prompter: HostKeyPrompter,
    private val clock: () -> Long = System::currentTimeMillis,
) : HostKeyVerifier {
    override fun knownKeyTypes(
        host: String,
        port: Int,
    ): List<String> = store.find(host, port).map { it.keyType }.distinct()

    override fun verify(
        host: String,
        port: Int,
        key: PublicKey,
    ) {
        val blob = OpenSshKeys.blob(key)
        val type = OpenSshKeys.typeName(key)
        val fingerprint = OpenSshKeys.fingerprintSha256(blob)
        val known = store.find(host, port)
        val encoded = Base64.getEncoder().encodeToString(blob)
        when {
            known.any { it.keyType == type && it.publicKeyBase64 == encoded } ->
                store.touch(host, port, type, clock())
            known.isNotEmpty() ->
                throw HostKeyChangedException(
                    host = host,
                    port = port,
                    keyType = type,
                    expectedFingerprints = known.map { it.fingerprintSha256 },
                    actualFingerprint = fingerprint,
                )
            else -> {
                val prompt = HostKeyPrompt(host, port, type, fingerprint)
                if (!prompter.confirmNewHost(prompt)) throw HostKeyRejectedException(prompt)
                val now = clock()
                store.trust(KnownHost(host, port, type, encoded, fingerprint, now, now))
            }
        }
    }
}
