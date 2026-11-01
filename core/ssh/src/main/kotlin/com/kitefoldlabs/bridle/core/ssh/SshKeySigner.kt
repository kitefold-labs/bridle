package com.kitefoldlabs.bridle.core.ssh

import net.schmizz.sshj.common.Buffer
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * Signs SSH user-authentication data without exposing the private key.
 *
 * Implementations may be backed by the Android Keystore (the key never leaves secure hardware),
 * by an imported key decrypted into memory, or by a hardware token.
 */
public interface SshKeySigner {
    /** Public half; its type decides which signature algorithms are offered. */
    public val publicKey: PublicKey

    /**
     * Signs [data] with the SSH signature [algorithm] (e.g. `rsa-sha2-512`, `ssh-ed25519`,
     * `ecdsa-sha2-nistp256`) and returns the *signature blob* in SSH wire encoding (for ECDSA:
     * `mpint r || mpint s`; for RSA and Ed25519: the raw signature bytes).
     */
    public fun sign(
        algorithm: String,
        data: ByteArray,
    ): ByteArray
}

/**
 * [SshKeySigner] on top of the Java Cryptography Architecture.
 *
 * With [provider] = null the platform picks the provider that owns [privateKey]; this is what
 * makes non-exportable Android Keystore keys work (the `AndroidKeyStore` provider is selected
 * automatically), while software keys use the default JDK/Conscrypt/BouncyCastle providers.
 */
public class JcaSshKeySigner(
    override val publicKey: PublicKey,
    private val privateKey: PrivateKey,
    private val provider: String? = null,
) : SshKeySigner {
    override fun sign(
        algorithm: String,
        data: ByteArray,
    ): ByteArray {
        val jcaName = jcaAlgorithm(algorithm)
        val signature =
            if (provider ==
                null
            ) {
                Signature.getInstance(jcaName)
            } else {
                Signature.getInstance(jcaName, provider)
            }
        signature.initSign(privateKey)
        signature.update(data)
        val raw = signature.sign()
        return if (algorithm.startsWith("ecdsa-")) SshSignatures.ecdsaDerToSsh(raw) else raw
    }

    public companion object {
        /** Maps an SSH signature algorithm name to its JCA name. */
        public fun jcaAlgorithm(sshAlgorithm: String): String =
            when (sshAlgorithm) {
                "ssh-ed25519" -> "Ed25519"
                "ecdsa-sha2-nistp256" -> "SHA256withECDSA"
                "ecdsa-sha2-nistp384" -> "SHA384withECDSA"
                "ecdsa-sha2-nistp521" -> "SHA512withECDSA"
                "rsa-sha2-512" -> "SHA512withRSA"
                "rsa-sha2-256" -> "SHA256withRSA"
                "ssh-rsa" -> "SHA1withRSA"
                else -> throw IllegalArgumentException("Unsupported signature algorithm: $sshAlgorithm")
            }
    }
}

/** Signature encoding helpers. */
public object SshSignatures {
    private const val DER_SEQUENCE: Int = 0x30
    private const val DER_INTEGER: Int = 0x02
    private const val LONG_FORM: Int = 0x80
    private const val LENGTH_MASK: Int = 0x7F
    private const val BYTE_MASK: Int = 0xFF
    private const val MAX_LENGTH_BYTES: Int = 2

    /** Converts a DER `SEQUENCE { INTEGER r, INTEGER s }` ECDSA signature to SSH `mpint r || mpint s`. */
    public fun ecdsaDerToSsh(der: ByteArray): ByteArray {
        val reader = DerReader(der)
        reader.expectTag(DER_SEQUENCE)
        val end = reader.readLength() + reader.position
        require(end == der.size) { "Trailing data in ECDSA signature" }
        val r = reader.readInteger()
        val s = reader.readInteger()
        require(reader.position == end) { "Unexpected data in ECDSA signature" }
        return Buffer
            .PlainBuffer()
            .putMPInt(r)
            .putMPInt(s)
            .compactData
    }

    private class DerReader(
        private val bytes: ByteArray,
    ) {
        var position: Int = 0
            private set

        fun expectTag(tag: Int) {
            require(next() == tag) { "Malformed DER: expected tag $tag" }
        }

        fun readLength(): Int {
            val first = next()
            if (first and LONG_FORM == 0) return first
            val count = first and LENGTH_MASK
            require(count in 1..MAX_LENGTH_BYTES) { "Malformed DER length" }
            var length = 0
            repeat(count) { length = (length shl Byte.SIZE_BITS) or next() }
            return length
        }

        fun readInteger(): BigInteger {
            expectTag(DER_INTEGER)
            val length = readLength()
            require(length > 0 && position + length <= bytes.size) { "Malformed DER integer" }
            val value = BigInteger(bytes.copyOfRange(position, position + length))
            position += length
            require(value.signum() > 0) { "ECDSA signature values must be positive" }
            return value
        }

        private fun next(): Int {
            require(position < bytes.size) { "Truncated DER" }
            return bytes[position++].toInt() and BYTE_MASK
        }
    }
}
