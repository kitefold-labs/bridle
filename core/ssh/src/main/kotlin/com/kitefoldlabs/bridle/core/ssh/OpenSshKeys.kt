package com.kitefoldlabs.bridle.core.ssh

import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/** OpenSSH public-key text formats and fingerprints. */
public object OpenSshKeys {
    /** SSH wire-format blob of [key] (what `authorized_keys` base64-encodes). */
    public fun blob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    /** The SSH key-type name of [key], e.g. `ssh-ed25519` or `ecdsa-sha2-nistp256`. */
    public fun typeName(key: PublicKey): String {
        val type = KeyType.fromKey(key)
        require(type != KeyType.UNKNOWN) { "Unsupported key type: ${key.algorithm}" }
        return type.toString()
    }

    /** The model's [KeyAlgorithm] for [key]. */
    public fun algorithm(key: PublicKey): KeyAlgorithm =
        requireNotNull(KeyAlgorithm.fromSshName(typeName(key))) { "Unsupported key type: ${typeName(key)}" }

    /** One `authorized_keys` line: `<type> <base64> [comment]`. Newlines in [comment] are removed. */
    public fun authorizedKeyLine(
        key: PublicKey,
        comment: String? = null,
    ): String {
        val base = "${typeName(key)} ${Base64.getEncoder().encodeToString(blob(key))}"
        val clean = comment?.replace(Regex("[\\r\\n]+"), " ")?.trim()
        return if (clean.isNullOrEmpty()) base else "$base $clean"
    }

    /** `SHA256:<unpadded base64>` exactly as `ssh-keygen -lf` prints it. */
    public fun fingerprintSha256(blob: ByteArray): String =
        "SHA256:" +
            Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** Fingerprint of [key]. */
    public fun fingerprintSha256(key: PublicKey): String = fingerprintSha256(blob(key))

    /** Parses an `authorized_keys`-style line back into a [PublicKey]. */
    public fun parseAuthorizedKeyLine(line: String): PublicKey {
        val parts = line.trim().split(Regex("\\s+"))
        require(parts.size >= 2) { "Not an OpenSSH public key line" }
        val bytes =
            try {
                Base64.getDecoder().decode(parts[1])
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Public key is not valid base64", e)
            }
        val key = Buffer.PlainBuffer(bytes).readPublicKey()
        require(typeName(key) == parts[0]) { "Key type ${parts[0]} does not match key data" }
        return key
    }
}
