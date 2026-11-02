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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates, imports and deletes identities, keeping the Keystore, the imported-key vault and the
 * database in step. Key operations run on the I/O dispatcher (RSA generation takes seconds).
 *
 * Generating and deleting are serialised: deleting also removes Keystore keys that no identity
 * refers to (left behind when the app died between generating a key and saving it, or when an
 * earlier delete failed), and must not mistake a key being generated for one of those.
 */
@Singleton
class KeyLibrary
    @Inject
    constructor(
        private val identities: IdentityRepository,
        private val keystore: KeystoreKeyManager,
        private val vault: ImportedKeyVault,
    ) {
        private val keystoreLock = Mutex()

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
                keystoreLock.withLock {
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
                try {
                    identities.insert(imported.identity, imported.encryptedKey)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Don't leave the key's Keystore wrapping key behind.
                    deleteQuietly { vault.delete(imported.identity, imported.encryptedKey) }
                    throw e
                }
            }

        /**
         * Deletes [identity] and its Keystore entry: the signing key of a hardware key, or the
         * wrapping key of an imported key that requires user authentication. The identity is
         * deleted even if the Keystore fails; its hardware key is then removed by a later delete.
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
                keystoreLock.withLock {
                    identities.delete(identity.id)
                    when {
                        identity.source == KeySource.KEYSTORE -> deleteQuietly { keystore.delete(identity) }
                        encrypted != null -> deleteQuietly { vault.delete(identity, encrypted) }
                    }
                    val remaining = identities.observeAll().first()
                    deleteQuietly { keystore.orphanedAliases(remaining).forEach(keystore::deleteAlias) }
                }
            }
        }

        /**
         * Runs a Keystore deletion, ignoring its failure: the entry is unreachable either way, as
         * nothing refers to it any more.
         */
        private inline fun deleteQuietly(delete: () -> Unit) {
            try {
                delete()
            } catch (ignored: GeneralSecurityException) {
                // KeyStoreException and the like.
            } catch (ignored: ProviderException) {
                // The Keystore daemon failing; Android reports that as a RuntimeException.
            } catch (ignored: IllegalArgumentException) {
                // A stored public key that no longer parses: no wrapping key can be identified.
            }
        }
    }
