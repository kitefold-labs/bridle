package com.kitefoldlabs.bridle.core.security

import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.ssh.JcaSshKeySigner
import com.kitefoldlabs.bridle.core.ssh.OpenSshKeys
import com.kitefoldlabs.bridle.core.ssh.SshKeySigner
import net.schmizz.sshj.common.SecurityUtils
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.security.auth.DestroyFailedException

/** An imported identity plus its encrypted private key, which the caller persists alongside it. */
public class ImportedKey(
    public val identity: Identity,
    encryptedKey: ByteArray,
) {
    private val blob = encryptedKey.copyOf()

    /** AEAD ciphertext of the private key; only [ImportedKeyVault] can use it. */
    public val encryptedKey: ByteArray get() = blob.copyOf()

    override fun toString(): String = "ImportedKey(${identity.algorithm}, ${blob.size} encrypted bytes)"
}

/**
 * Imports OpenSSH/PEM private keys (Ed25519, ECDSA, RSA; plain or passphrase-protected) and signs
 * with them.
 *
 * The decrypted key is re-encrypted with the [AeadProvider]'s AEAD. The associated data binds each
 * ciphertext to its identity's public key, so encrypted keys can't be swapped between identities.
 * Signing decrypts into memory for that one signature and then clears the buffers (best effort:
 * the JVM may keep copies inside key objects). Imported keys can never be exported again.
 */
public class ImportedKeyVault(
    private val aeadProvider: AeadProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Reads [keyText]'s format and whether it needs a passphrase, without decrypting it.
     *
     * @throws KeyImportException if it isn't a supported private key.
     */
    public fun inspect(keyText: CharArray): KeyFileInfo = PrivateKeyReader.inspect(keyText)

    /**
     * Imports [keyText] (left untouched; the caller owns and clears it). [passphrase] is zeroed
     * before this returns, whether or not the import succeeds.
     *
     * @param label user-facing name, stored in [Identity.alias].
     * @param comment public-key comment; OpenSSH keys keep theirs encrypted, so it isn't recovered.
     * @param userAuth app-enforced user authentication before each use (see [signer]).
     * @throws KeyImportException if the key can't be read or decrypted.
     * @throws KeyVaultUnavailableException if the vault can't protect keys on this device.
     */
    public fun import(
        keyText: CharArray,
        passphrase: CharArray?,
        label: String,
        comment: String? = null,
        userAuth: UserAuthPolicy = UserAuthPolicy.None,
    ): ImportedKey {
        val (pair, algorithm) =
            try {
                PrivateKeyReader.read(keyText, passphrase)
            } finally {
                passphrase?.fill('\u0000')
            }
        val plaintext: ByteArray = pair.private.encoded
        try {
            val ciphertext = aeadProvider.aead().encrypt(plaintext, associatedData(pair.public))
            val identity =
                Identity(
                    alias = label,
                    source = KeySource.IMPORTED,
                    algorithm = algorithm,
                    publicKeyOpenSsh = OpenSshKeys.authorizedKeyLine(pair.public, comment),
                    requireUserAuth = userAuth.requiresAuth,
                    authValiditySeconds = userAuth.validitySeconds,
                    createdAtEpochMs = clock(),
                )
            return ImportedKey(identity, byteArrayOf(FORMAT_VERSION) + ciphertext)
        } finally {
            plaintext.fill(0)
            destroyQuietly(pair.private)
        }
    }

    /**
     * A signer for an imported [identity] whose encrypted key is [encryptedKey]. When the identity
     * requires user authentication a [gate] is mandatory; Bridle enforces it (the vault's master
     * key isn't auth-bound), prompting at most once per [Identity.authValiditySeconds] (every
     * signature when 0).
     *
     * Decryption happens per signature; a ciphertext that doesn't belong to [identity] fails
     * with [StoredKeyException].
     */
    public fun signer(
        identity: Identity,
        encryptedKey: ByteArray,
        gate: BiometricGate? = null,
        reason: String = "",
    ): SshKeySigner {
        require(identity.source == KeySource.IMPORTED) { "Not an imported identity" }
        val publicKey = OpenSshKeys.parseAuthorizedKeyLine(identity.publicKeyOpenSsh)
        val base = VaultSigner(publicKey, identity.algorithm, encryptedKey.copyOf())
        if (!identity.requireUserAuth) return base
        requireNotNull(gate) { "This key requires user authentication; pass a BiometricGate" }
        return GatedSigner(
            base,
            gate,
            reason,
            appEnforcedValidityMillis = identity.authValiditySeconds * MILLIS_PER_SECOND,
            clock = clock,
        )
    }

    private inner class VaultSigner(
        override val publicKey: PublicKey,
        private val algorithm: KeyAlgorithm,
        private val encryptedKey: ByteArray,
    ) : SshKeySigner {
        override fun sign(
            algorithm: String,
            data: ByteArray,
        ): ByteArray {
            val plaintext = decrypt()
            try {
                val factory = SecurityUtils.getKeyFactory(jcaKeyAlgorithm(this.algorithm))
                val privateKey =
                    try {
                        factory.generatePrivate(PKCS8EncodedKeySpec(plaintext))
                    } catch (ignored: GeneralSecurityException) {
                        throw StoredKeyException("The stored key is damaged")
                    }
                try {
                    // Keep key and signature in the provider that decoded the key (sshj's).
                    return JcaSshKeySigner(publicKey, privateKey, factory.provider.name).sign(algorithm, data)
                } finally {
                    destroyQuietly(privateKey)
                }
            } finally {
                plaintext.fill(0)
            }
        }

        private fun decrypt(): ByteArray {
            if (encryptedKey.isEmpty() || encryptedKey[0] != FORMAT_VERSION) {
                throw StoredKeyException("Unknown stored key format")
            }
            val ciphertext = encryptedKey.copyOfRange(1, encryptedKey.size)
            val aead = aeadProvider.aead() // KeyVaultUnavailableException propagates as is
            return try {
                aead.decrypt(ciphertext, associatedData(publicKey))
            } catch (ignored: GeneralSecurityException) {
                throw StoredKeyException("The stored key doesn't belong to this identity or is damaged")
            }
        }
    }

    internal companion object {
        const val FORMAT_VERSION: Byte = 1
        private const val MILLIS_PER_SECOND = 1_000L
        private val AD_PREFIX = "bridle/imported-key/v1\u0000".toByteArray(Charsets.US_ASCII)

        /** Binds a ciphertext to the SSH wire encoding of the identity's public key. */
        fun associatedData(publicKey: PublicKey): ByteArray = AD_PREFIX + OpenSshKeys.blob(publicKey)

        fun jcaKeyAlgorithm(algorithm: KeyAlgorithm): String =
            when (algorithm) {
                KeyAlgorithm.ED25519 -> "Ed25519"
                KeyAlgorithm.RSA -> "RSA"
                else -> "EC"
            }

        /** Most JCA keys can't be destroyed; try anyway. */
        fun destroyQuietly(key: PrivateKey) {
            try {
                if (!key.isDestroyed) key.destroy()
            } catch (ignored: DestroyFailedException) {
                // Expected for JDK/Conscrypt keys: there is nothing more we can do.
            }
        }
    }
}
