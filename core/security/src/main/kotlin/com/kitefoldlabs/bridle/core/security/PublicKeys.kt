package com.kitefoldlabs.bridle.core.security

import net.schmizz.sshj.common.Ed25519KeyFactory
import net.schmizz.sshj.common.KeyType
import java.security.PublicKey

/** Public-key plumbing shared by the Keystore and import paths. */
internal object PublicKeys {
    /** DER prefix of an X.509 `SubjectPublicKeyInfo` for Ed25519 (OID 1.3.101.112). */
    private val ED25519_SPKI_PREFIX =
        byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    private const val ED25519_KEY_SIZE = 32

    /**
     * Returns a [PublicKey] sshj understands. Android Keystore reports Ed25519 public keys with
     * the algorithm name set to the curve OID, which sshj doesn't recognise; those are re-created
     * from their X.509 encoding. Every other key is returned unchanged.
     */
    fun normalize(key: PublicKey): PublicKey {
        if (KeyType.fromKey(key) != KeyType.UNKNOWN) return key
        val encoded = key.encoded ?: return key
        val isEd25519 =
            encoded.size == ED25519_SPKI_PREFIX.size + ED25519_KEY_SIZE &&
                encoded.copyOfRange(0, ED25519_SPKI_PREFIX.size).contentEquals(ED25519_SPKI_PREFIX)
        return if (isEd25519) {
            Ed25519KeyFactory.getPublicKey(encoded.copyOfRange(ED25519_SPKI_PREFIX.size, encoded.size))
        } else {
            key
        }
    }
}
