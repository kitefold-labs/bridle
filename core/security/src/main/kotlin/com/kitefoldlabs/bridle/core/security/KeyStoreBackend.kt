package com.kitefoldlabs.bridle.core.security

import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Storage for non-exportable signing keys. Production uses [AndroidKeyStoreBackend]; tests use a
 * software implementation because Robolectric has no Android Keystore.
 */
public interface KeyStoreBackend {
    /** True when the device has a StrongBox secure element. */
    public val isStrongBoxAvailable: Boolean

    /** True when the Keystore can generate Ed25519 keys (Android 13+ with KeyMint 2.0+). */
    public val supportsEd25519: Boolean

    /**
     * Generates a key pair under [alias] (which must not exist yet) and returns its public half.
     *
     * @throws KeyGenerationException if the key can't be generated.
     */
    public fun generateKeyPair(
        alias: String,
        spec: KeyGenSpec,
    ): GeneratedKey

    /** The opaque private key handle for [alias], or null if there is none. */
    public fun privateKey(alias: String): PrivateKey?

    /** The public key for [alias], or null if there is none. */
    public fun publicKey(alias: String): PublicKey?

    /** Deletes [alias]; deleting a missing alias is not an error. */
    public fun delete(alias: String)

    /** Every alias in the store (including ones not created by Bridle). */
    public fun aliases(): List<String>
}

/** What to generate. */
public data class KeyGenSpec(
    val algorithm: KeyAlgorithm,
    val userAuth: UserAuthPolicy = UserAuthPolicy.None,
    /** Try StrongBox first when the device has it (only P-256 is eligible). */
    val preferStrongBox: Boolean = true,
) {
    init {
        require(algorithm in GENERATABLE) { "Keystore keys can't be ${algorithm.sshName}" }
    }

    public companion object {
        /** RSA modulus size for hardware RSA keys. */
        public const val RSA_KEY_SIZE: Int = 3072

        /** Algorithms the Keystore can generate (Ed25519 additionally needs [KeyStoreBackend.supportsEd25519]). */
        public val GENERATABLE: Set<KeyAlgorithm> =
            setOf(KeyAlgorithm.ECDSA_P256, KeyAlgorithm.ECDSA_P384, KeyAlgorithm.ED25519, KeyAlgorithm.RSA)
    }
}

/** Whether using a key requires the user to authenticate. */
public sealed interface UserAuthPolicy {
    /** The key can sign whenever the device is unlocked. */
    public data object None : UserAuthPolicy

    /** One biometric/device-credential unlock authorises signatures for [seconds]. */
    public data class TimeBound(
        val seconds: Int,
    ) : UserAuthPolicy {
        init {
            require(seconds > 0) { "seconds must be > 0; use PerUse for every signature" }
        }
    }

    /** Every signature needs a biometric prompt bound to that signature (`CryptoObject`). */
    public data object PerUse : UserAuthPolicy

    /** Value for [com.kitefoldlabs.bridle.core.model.Identity.requireUserAuth]. */
    public val requiresAuth: Boolean get() = this != None

    /** Value for [com.kitefoldlabs.bridle.core.model.Identity.authValiditySeconds] (0 = every signature). */
    public val validitySeconds: Int
        get() =
            when (this) {
                None -> DEFAULT_VALIDITY_SECONDS
                PerUse -> 0
                is TimeBound -> seconds
            }

    public companion object {
        /** Identity's default validity, recorded for keys that don't require authentication. */
        public const val DEFAULT_VALIDITY_SECONDS: Int = 300

        /** The policy an [com.kitefoldlabs.bridle.core.model.Identity]'s auth fields describe. */
        public fun of(
            requireUserAuth: Boolean,
            authValiditySeconds: Int,
        ): UserAuthPolicy =
            when {
                !requireUserAuth -> None
                authValiditySeconds == 0 -> PerUse
                else -> TimeBound(authValiditySeconds)
            }
    }
}

/** Where a generated key lives. */
public enum class HardwareLevel {
    STRONGBOX,
    TRUSTED_ENVIRONMENT,

    /** Software-only; used by test backends, never by [AndroidKeyStoreBackend]. */
    SOFTWARE,
}

/** Result of [KeyStoreBackend.generateKeyPair]. */
public data class GeneratedKey(
    val publicKey: PublicKey,
    val level: HardwareLevel,
)

/** Key generation failed (unsupported algorithm, Keystore error). Never carries key material. */
public class KeyGenerationException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
