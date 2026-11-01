package com.kitefoldlabs.bridle.core.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.ssh.JcaSshKeySigner
import com.kitefoldlabs.bridle.core.ssh.OpenSshKeys
import com.kitefoldlabs.bridle.core.ssh.SshKeySigner
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.SecurityUtils
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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
 *
 * Keys that require user authentication are additionally encrypted under a random data key that a
 * per-key [KeyWrappingStore] key protects, so the Keystore, not Bridle, enforces the
 * authentication: the vault's master key alone can't decrypt them.
 *
 * @param wrappingKeys needed to import keys that require user authentication; production passes
 *   [AndroidKeyStoreWrappingStore].
 */
public class ImportedKeyVault(
    private val aeadProvider: AeadProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val wrappingKeys: KeyWrappingStore? = null,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    private val random = SecureRandom()

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
     * @param userAuth user authentication before each use, enforced by a Keystore wrapping key
     *   (see [signer]). Importing itself never prompts.
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
            val blob =
                if (userAuth.requiresAuth) {
                    sealAuthBound(plaintext, pair.public, userAuth)
                } else {
                    byteArrayOf(FORMAT_VERSION) + aeadProvider.aead().encrypt(plaintext, associatedData(pair.public))
                }
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
            return ImportedKey(identity, blob)
        } finally {
            plaintext.fill(0)
            destroyQuietly(pair.private)
        }
    }

    /**
     * A signer for an imported [identity] whose encrypted key is [encryptedKey]. Keys that need
     * user authentication require a [gate]:
     *
     * - Keys imported with authentication are unwrapped by their Keystore wrapping key. Per-use
     *   keys prompt for every signature with the unwrapping cipher as `CryptoObject`; time-bound
     *   keys prompt when the Keystore reports that the window expired. Either way the Keystore
     *   refuses to unwrap the key without a fresh authentication.
     * - A key stored without a wrapping key whose identity was later set to require
     *   authentication can only be gated by Bridle itself: it prompts at most once per
     *   [Identity.authValiditySeconds] (every signature when 0). Importing the key again with
     *   authentication makes the Keystore enforce it.
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
        val blob = encryptedKey.copyOf()
        if (blob.firstOrNull() == FORMAT_AUTH_BOUND) {
            requireNotNull(gate) { AUTH_NEEDS_GATE }
            if (blob.getOrNull(1) == MODE_PER_USE) {
                return VaultSigner(publicKey, identity.algorithm, blob) { cipher ->
                    if (!runBlocking { gate.authenticate(reason, cipher) }) throw UserAuthenticationException()
                }
            }
            // Time-bound: the Keystore throws UserNotAuthenticatedException once the window has
            // expired; GatedSigner then prompts and retries.
            return GatedSigner(VaultSigner(publicKey, identity.algorithm, blob, NO_AUTHORIZATION), gate, reason)
        }
        val base = VaultSigner(publicKey, identity.algorithm, blob, NO_AUTHORIZATION)
        if (!identity.requireUserAuth) return base
        requireNotNull(gate) { AUTH_NEEDS_GATE }
        return GatedSigner(
            base,
            gate,
            reason,
            appEnforcedValidityMillis = identity.authValiditySeconds * MILLIS_PER_SECOND,
            clock = clock,
        )
    }

    /**
     * Deletes [encryptedKey]'s Keystore wrapping key, if it has one. Call it when deleting
     * [identity]; the key can't be used afterwards.
     */
    public fun delete(
        identity: Identity,
        encryptedKey: ByteArray,
    ) {
        if (encryptedKey.firstOrNull() != FORMAT_AUTH_BOUND) return
        val publicKey = OpenSshKeys.parseAuthorizedKeyLine(identity.publicKeyOpenSsh)
        val sealed =
            try {
                openAuthBound(encryptedKey, publicKey)
            } catch (ignored: StoredKeyException) {
                return // damaged or someone else's: there is no wrapping key we can identify
            }
        wrappingKeys?.delete(sealed.alias)
    }

    private fun sealAuthBound(
        plaintext: ByteArray,
        publicKey: PublicKey,
        policy: UserAuthPolicy,
    ): ByteArray {
        val store = wrappingKeys ?: throw KeyVaultUnavailableException(NO_WRAPPING_KEYS)
        val aead = aeadProvider.aead()
        val alias = WRAPPING_ALIAS_PREFIX + newId()
        store.create(alias, policy)
        val dataKey = ByteArray(DATA_KEY_BYTES).also(random::nextBytes)
        try {
            val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
            val ciphertext = dataKeyCipher(Cipher.ENCRYPT_MODE, dataKey, iv, publicKey).doFinal(plaintext)
            val inner = AuthBound(alias, store.wrap(alias, dataKey), iv, ciphertext).encode()
            val mode = if (policy == UserAuthPolicy.PerUse) MODE_PER_USE else MODE_TIME_BOUND
            val header = byteArrayOf(FORMAT_AUTH_BOUND, mode)
            return header + aead.encrypt(inner, associatedData(publicKey) + header)
        } catch (e: GeneralSecurityException) {
            store.delete(alias)
            throw KeyVaultUnavailableException("The key can't be protected", e)
        } finally {
            dataKey.fill(0)
        }
    }

    /** Decrypts the outer (vault) layer of an auth-bound blob; the header is bound as well. */
    private fun openAuthBound(
        blob: ByteArray,
        publicKey: PublicKey,
    ): AuthBound {
        if (blob.size <= HEADER_BYTES) throw StoredKeyException(DAMAGED)
        val header = blob.copyOfRange(0, HEADER_BYTES)
        val inner = decryptOuter(blob.copyOfRange(HEADER_BYTES, blob.size), associatedData(publicKey) + header)
        return AuthBound.decode(inner)
    }

    private fun decryptOuter(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val aead = aeadProvider.aead() // KeyVaultUnavailableException propagates as is
        return try {
            aead.decrypt(ciphertext, associatedData)
        } catch (ignored: GeneralSecurityException) {
            throw StoredKeyException(DAMAGED)
        }
    }

    private inner class VaultSigner(
        override val publicKey: PublicKey,
        private val algorithm: KeyAlgorithm,
        private val encryptedKey: ByteArray,
        /** Authorises a per-use unwrapping cipher before it is used (or does nothing). */
        private val authorize: (Cipher) -> Unit,
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

        private fun decrypt(): ByteArray =
            when (encryptedKey.firstOrNull()) {
                FORMAT_VERSION ->
                    decryptOuter(encryptedKey.copyOfRange(1, encryptedKey.size), associatedData(publicKey))
                FORMAT_AUTH_BOUND -> decryptAuthBound()
                else -> throw StoredKeyException("Unknown stored key format")
            }

        private fun decryptAuthBound(): ByteArray {
            val sealed = openAuthBound(encryptedKey, publicKey)
            val dataKey = unwrap(wrappingKeys ?: throw KeyVaultUnavailableException(NO_WRAPPING_KEYS), sealed)
            try {
                return dataKeyCipher(Cipher.DECRYPT_MODE, dataKey, sealed.iv, publicKey).doFinal(sealed.ciphertext)
            } catch (ignored: GeneralSecurityException) {
                throw StoredKeyException(DAMAGED)
            } finally {
                dataKey.fill(0)
            }
        }

        private fun unwrap(
            store: KeyWrappingStore,
            sealed: AuthBound,
        ): ByteArray {
            val cipher =
                // UserNotAuthenticatedException (a time-bound window expired) propagates as is:
                // GatedSigner prompts and retries.
                try {
                    store.unwrapCipher(sealed.alias)
                } catch (ignored: KeyPermanentlyInvalidatedException) {
                    throw StoredKeyException("This key's protection was reset (biometrics changed); import it again")
                }
            authorize(cipher)
            return try {
                cipher.doFinal(sealed.wrappedKey)
            } catch (ignored: GeneralSecurityException) {
                throw UserAuthenticationException("The Android Keystore refused to unlock this key")
            }
        }
    }

    /** The inner (vault-encrypted) part of an auth-bound blob. */
    private class AuthBound(
        val alias: String,
        val wrappedKey: ByteArray,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    ) {
        fun encode(): ByteArray {
            val aliasBytes = alias.toByteArray(Charsets.US_ASCII)
            return ByteBuffer
                .allocate(1 + aliasBytes.size + 2 + wrappedKey.size + iv.size + ciphertext.size)
                .put(aliasBytes.size.toByte())
                .put(aliasBytes)
                .putShort(wrappedKey.size.toShort())
                .put(wrappedKey)
                .put(iv)
                .put(ciphertext)
                .array()
        }

        companion object {
            private const val UNSIGNED_BYTE = 0xFF
            private const val UNSIGNED_SHORT = 0xFFFF

            fun decode(bytes: ByteArray): AuthBound =
                try {
                    val buffer = ByteBuffer.wrap(bytes)
                    val alias = ByteArray(buffer.get().toInt() and UNSIGNED_BYTE).also { buffer.get(it) }
                    val wrapped = ByteArray(buffer.getShort().toInt() and UNSIGNED_SHORT).also { buffer.get(it) }
                    val iv = ByteArray(GCM_IV_BYTES).also { buffer.get(it) }
                    val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    AuthBound(String(alias, Charsets.US_ASCII), wrapped, iv, ciphertext)
                } catch (ignored: BufferUnderflowException) {
                    throw StoredKeyException(DAMAGED)
                }
        }
    }

    internal companion object {
        /** Encrypted by the vault's master key only. */
        const val FORMAT_VERSION: Byte = 1

        /** Encrypted under a data key that a Keystore wrapping key protects: header, then vault ciphertext. */
        const val FORMAT_AUTH_BOUND: Byte = 2
        const val MODE_PER_USE: Byte = 0
        const val MODE_TIME_BOUND: Byte = 1

        /** Prefix of the Keystore aliases of wrapping keys (distinct from signing keys'). */
        const val WRAPPING_ALIAS_PREFIX: String = "bridle-wrap-"

        private const val HEADER_BYTES = 2
        private const val MILLIS_PER_SECOND = 1_000L
        private const val DATA_KEY_BYTES = 32
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val DAMAGED = "The stored key doesn't belong to this identity or is damaged"
        private const val AUTH_NEEDS_GATE = "This key requires user authentication; pass a BiometricGate"
        private const val NO_WRAPPING_KEYS = "Keys that require user authentication need the Android Keystore"
        private val NO_AUTHORIZATION: (Cipher) -> Unit = {}
        private val AD_PREFIX = "bridle/imported-key/v1\u0000".toByteArray(Charsets.US_ASCII)

        /** Binds a ciphertext to the SSH wire encoding of the identity's public key. */
        fun associatedData(publicKey: PublicKey): ByteArray = AD_PREFIX + OpenSshKeys.blob(publicKey)

        /** AES-256-GCM under a data key, bound to [publicKey] like the vault layer. */
        private fun dataKeyCipher(
            mode: Int,
            dataKey: ByteArray,
            iv: ByteArray,
            publicKey: PublicKey,
        ): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(associatedData(publicKey))
            }

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
