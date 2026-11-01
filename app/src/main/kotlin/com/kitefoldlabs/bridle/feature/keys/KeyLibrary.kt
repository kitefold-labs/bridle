package com.kitefoldlabs.bridle.feature.keys

import com.kitefoldlabs.bridle.core.data.IdentityRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.security.ImportedKeyVault
import com.kitefoldlabs.bridle.core.security.KeyFileInfo
import com.kitefoldlabs.bridle.core.security.KeystoreKeyManager
import com.kitefoldlabs.bridle.core.security.UserAuthPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates, imports and deletes identities, keeping the Keystore, the imported-key vault and the
 * database in step. Key operations run on the I/O dispatcher (RSA generation takes seconds).
 */
@Singleton
class KeyLibrary
    @Inject
    constructor(
        private val identities: IdentityRepository,
        private val keystore: KeystoreKeyManager,
        private val vault: ImportedKeyVault,
    ) {
        /** Algorithms this device can generate, default first. */
        fun availableAlgorithms(): List<KeyAlgorithm> = keystore.availableAlgorithms()

        /**
         * Generates a hardware key and saves its identity.
         *
         * @throws com.kitefoldlabs.bridle.core.security.KeyGenerationException if the Keystore fails.
         */
        suspend fun generate(
            label: String,
            algorithm: KeyAlgorithm,
            userAuth: UserAuthPolicy,
        ): EntityId =
            withContext(Dispatchers.IO) {
                val identity = keystore.createIdentity(label, algorithm, userAuth)
                try {
                    identities.insert(identity)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Don't leave an unreachable key behind in the Keystore.
                    keystore.delete(identity)
                    throw e
                }
            }

        /**
         * Reads whether [keyText] is a supported private key and whether it needs a passphrase.
         *
         * @throws com.kitefoldlabs.bridle.core.security.KeyImportException if it isn't usable.
         */
        suspend fun inspect(keyText: CharArray): KeyFileInfo = withContext(Dispatchers.IO) { vault.inspect(keyText) }

        /**
         * Imports [keyText] (which the caller clears), encrypting it under the vault key, and saves
         * the identity. [passphrase] is zeroed.
         *
         * @throws com.kitefoldlabs.bridle.core.security.KeyImportException if it can't be read.
         * @throws com.kitefoldlabs.bridle.core.security.KeyVaultUnavailableException if keys can't be protected.
         */
        suspend fun import(
            keyText: CharArray,
            passphrase: CharArray?,
            label: String,
            userAuth: UserAuthPolicy,
        ): EntityId =
            withContext(Dispatchers.IO) {
                val imported = vault.import(keyText, passphrase, label, comment = null, userAuth = userAuth)
                identities.insert(imported.identity, imported.encryptedKey)
            }

        /**
         * Deletes [identity] and its Keystore entry: the signing key of a hardware key, or the
         * wrapping key of an imported key that requires user authentication.
         */
        suspend fun delete(identity: Identity) {
            withContext(Dispatchers.IO) {
                val encrypted =
                    if (identity.source ==
                        KeySource.IMPORTED
                    ) {
                        identities.encryptedPrivateKey(identity.id)
                    } else {
                        null
                    }
                identities.delete(identity.id)
                try {
                    when {
                        identity.source == KeySource.KEYSTORE -> keystore.delete(identity)
                        encrypted != null -> vault.delete(identity, encrypted)
                    }
                } catch (ignored: GeneralSecurityException) {
                    // The entry is unreachable either way; nothing refers to it any more.
                } catch (ignored: IllegalArgumentException) {
                    // A stored public key that no longer parses: no wrapping key can be identified.
                }
            }
        }
    }
