package com.kitefoldlabs.bridle.core.security

import android.os.Build
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.ssh.JcaSshKeySigner
import com.kitefoldlabs.bridle.core.ssh.OpenSshKeys
import com.kitefoldlabs.bridle.core.ssh.SshKeySigner
import java.util.UUID

/**
 * Creates, deletes and signs with hardware-backed ("Keystore") identities.
 *
 * Each identity gets its own Keystore alias, `bridle-key-<uuid>`, recorded in
 * [Identity.keystoreAlias]. The private key never leaves the Keystore.
 */
public class KeystoreKeyManager(
    private val backend: KeyStoreBackend,
    /** Device name used in the default public-key comment (`bridle@<device>`). */
    deviceName: String = Build.MODEL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    /** `bridle@<device>`, with the device name reduced to characters safe in a key comment. */
    public val defaultComment: String = "bridle@" + sanitizeDeviceName(deviceName)

    /** Algorithms this device can generate, in the order the UI should offer them. */
    public fun availableAlgorithms(): List<KeyAlgorithm> =
        buildList {
            add(KeyAlgorithm.ECDSA_P256)
            if (backend.supportsEd25519) add(KeyAlgorithm.ED25519)
            add(KeyAlgorithm.ECDSA_P384)
            add(KeyAlgorithm.RSA)
        }

    /** True when the device has StrongBox (P-256 keys are then generated there). */
    public val isStrongBoxAvailable: Boolean get() = backend.isStrongBoxAvailable

    /**
     * Generates a new key pair in the Keystore and returns its (unsaved, `id = 0`) identity.
     *
     * @param label user-facing name, stored in [Identity.alias].
     * @throws KeyGenerationException if [algorithm] isn't available here or generation fails.
     */
    public fun createIdentity(
        label: String,
        algorithm: KeyAlgorithm = KeyAlgorithm.ECDSA_P256,
        userAuth: UserAuthPolicy = UserAuthPolicy.None,
        comment: String = defaultComment,
    ): Identity {
        if (algorithm !in availableAlgorithms()) {
            throw KeyGenerationException("${algorithm.sshName} keys can't be generated on this device")
        }
        val alias = ALIAS_PREFIX + newId()
        val generated = backend.generateKeyPair(alias, KeyGenSpec(algorithm, userAuth))
        return Identity(
            alias = label,
            source = KeySource.KEYSTORE,
            algorithm = algorithm,
            publicKeyOpenSsh = OpenSshKeys.authorizedKeyLine(generated.publicKey, comment),
            keystoreAlias = alias,
            requireUserAuth = userAuth.requiresAuth,
            authValiditySeconds = userAuth.validitySeconds,
            createdAtEpochMs = clock(),
        )
    }

    /** Deletes the Keystore key behind [identity]. The identity can't sign afterwards. */
    public fun delete(identity: Identity) {
        backend.delete(keystoreAlias(identity))
    }

    /** True when the Keystore still holds [identity]'s key (it can vanish, e.g. after a lock-screen reset). */
    public fun exists(identity: Identity): Boolean = backend.privateKey(keystoreAlias(identity)) != null

    /** Bridle-created aliases that no identity in [identities] refers to (left over from failed saves). */
    public fun orphanedAliases(identities: Collection<Identity>): List<String> {
        val known = identities.mapNotNull { it.keystoreAlias }.toSet()
        return backend.aliases().filter { it.startsWith(ALIAS_PREFIX) && it !in known }
    }

    /** Deletes a Keystore alias returned by [orphanedAliases]. */
    public fun deleteAlias(alias: String) {
        require(alias.startsWith(ALIAS_PREFIX)) { "Not a Bridle key alias" }
        backend.delete(alias)
    }

    /**
     * A signer for [identity]. Keys that need user authentication require a [gate]: time-bound
     * keys prompt when the Keystore reports the unlock expired, per-use keys prompt for every
     * signature (bound to it with a `CryptoObject`).
     *
     * @param reason prompt text shown to the user, e.g. "Sign in to build-server".
     * @throws IllegalStateException if the Keystore no longer holds the key.
     */
    public fun signer(
        identity: Identity,
        gate: BiometricGate? = null,
        reason: String = "",
    ): SshKeySigner {
        val alias = keystoreAlias(identity)
        val privateKey = checkNotNull(backend.privateKey(alias)) { "Keystore key is missing" }
        val publicKey = checkNotNull(backend.publicKey(alias)) { "Keystore key is missing" }
        return when (val policy = UserAuthPolicy.of(identity.requireUserAuth, identity.authValiditySeconds)) {
            UserAuthPolicy.None -> JcaSshKeySigner(publicKey, privateKey, provider = null)
            else -> {
                requireNotNull(gate) { "This key requires user authentication; pass a BiometricGate" }
                if (policy == UserAuthPolicy.PerUse) {
                    PerUseKeystoreSigner(publicKey, privateKey, gate, reason)
                } else {
                    GatedSigner(JcaSshKeySigner(publicKey, privateKey, provider = null), gate, reason)
                }
            }
        }
    }

    // Identity's init guarantees that Keystore identities have an alias.
    @Suppress("UnsafeCallOnNullableType")
    private fun keystoreAlias(identity: Identity): String {
        require(identity.source == KeySource.KEYSTORE) { "Not a Keystore identity" }
        return identity.keystoreAlias!!
    }

    public companion object {
        /** Prefix of every Keystore alias Bridle creates. */
        public const val ALIAS_PREFIX: String = "bridle-key-"

        private const val MAX_DEVICE_NAME = 40

        /** Lower-cases [name] and keeps `[a-z0-9._-]`, collapsing everything else to `-`. */
        public fun sanitizeDeviceName(name: String): String =
            name
                .lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .trim('-')
                .take(MAX_DEVICE_NAME)
                .ifEmpty { "android" }
    }
}
