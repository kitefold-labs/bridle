package com.kitefoldlabs.bridle.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.ChecksSdkIntAtLeast
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec

/** Device facts [AndroidKeyStoreBackend] depends on; injectable so every branch is testable. */
public interface KeystorePlatform {
    public val sdkInt: Int

    public fun hasSystemFeature(
        name: String,
        version: Int,
    ): Boolean

    public companion object {
        /** Reads the real device through [PackageManager]. */
        public fun of(context: Context): KeystorePlatform = PackageManagerPlatform(context.packageManager)
    }
}

private class PackageManagerPlatform(
    private val packageManager: PackageManager,
) : KeystorePlatform {
    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override fun hasSystemFeature(
        name: String,
        version: Int,
    ): Boolean =
        if (version ==
            0
        ) {
            packageManager.hasSystemFeature(name)
        } else {
            packageManager.hasSystemFeature(name, version)
        }
}

/**
 * [KeyStoreBackend] on the Android Keystore. Keys are generated with `PURPOSE_SIGN` only; P-256
 * keys go to StrongBox when the device has one (falling back to the TEE if StrongBox refuses).
 * Private keys never leave secure hardware: [privateKey] returns an opaque handle that the
 * `AndroidKeyStore` JCA provider signs with.
 */
public class AndroidKeyStoreBackend(
    private val platform: KeystorePlatform,
    private val providerName: String = PROVIDER,
) : KeyStoreBackend {
    public constructor(context: Context) : this(KeystorePlatform.of(context))

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(providerName).apply { load(null) } }

    override val isStrongBoxAvailable: Boolean
        get() = platform.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE, 0)

    override val supportsEd25519: Boolean
        get() = isAtLeastTiramisu() && platform.hasSystemFeature(FEATURE_HARDWARE_KEYSTORE, KEYMINT_CURVE25519_VERSION)

    override fun generateKeyPair(
        alias: String,
        spec: KeyGenSpec,
    ): GeneratedKey {
        if (spec.algorithm == KeyAlgorithm.ED25519 && !supportsEd25519) {
            throw KeyGenerationException("This device's Keystore can't generate Ed25519 keys")
        }
        if (keyStore.containsAlias(alias)) throw KeyGenerationException("Keystore alias already exists")
        val strongBox = spec.preferStrongBox && spec.algorithm in STRONGBOX_ALGORITHMS && isStrongBoxAvailable
        return try {
            if (strongBox) {
                try {
                    GeneratedKey(generate(alias, spec, strongBox = true), HardwareLevel.STRONGBOX)
                } catch (ignored: ProviderException) {
                    // StrongBoxUnavailableException (a ProviderException), or a StrongBox that is
                    // present but refuses this key: the TEE is still hardware-backed.
                    delete(alias)
                    GeneratedKey(generate(alias, spec, strongBox = false), HardwareLevel.TRUSTED_ENVIRONMENT)
                }
            } else {
                GeneratedKey(generate(alias, spec, strongBox = false), HardwareLevel.TRUSTED_ENVIRONMENT)
            }
        } catch (e: GeneralSecurityException) {
            throw KeyGenerationException("Keystore key generation failed", e)
        } catch (e: ProviderException) {
            throw KeyGenerationException("Keystore key generation failed", e)
        } catch (e: IllegalStateException) {
            // How the Keystore reports a key that needs user authentication on a device with no
            // screen lock, or a per-use key with no biometric enrolled.
            throw KeyGenerationException("Keystore key generation failed", e)
        }
    }

    override fun privateKey(alias: String): PrivateKey? = keyStore.getKey(alias, null) as? PrivateKey

    override fun publicKey(alias: String): PublicKey? =
        keyStore.getCertificate(alias)?.publicKey?.let(PublicKeys::normalize)

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    override fun aliases(): List<String> = keyStore.aliases().toList()

    private fun generate(
        alias: String,
        spec: KeyGenSpec,
        strongBox: Boolean,
    ): PublicKey {
        val algorithm =
            if (spec.algorithm == KeyAlgorithm.RSA) KeyProperties.KEY_ALGORITHM_RSA else KeyProperties.KEY_ALGORITHM_EC
        val generator = KeyPairGenerator.getInstance(algorithm, providerName)
        generator.initialize(parameterSpec(alias, spec, strongBox))
        return PublicKeys.normalize(generator.generateKeyPair().public)
    }

    /** The exact [KeyGenParameterSpec] used for [spec]; internal for tests. */
    internal fun parameterSpec(
        alias: String,
        spec: KeyGenSpec,
        strongBox: Boolean,
    ): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
        when (spec.algorithm) {
            KeyAlgorithm.RSA ->
                builder
                    .setAlgorithmParameterSpec(
                        RSAKeyGenParameterSpec(KeyGenSpec.RSA_KEY_SIZE, RSAKeyGenParameterSpec.F4),
                    ).setKeySize(KeyGenSpec.RSA_KEY_SIZE)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            KeyAlgorithm.ED25519 ->
                builder
                    .setAlgorithmParameterSpec(ECGenParameterSpec(ED25519_CURVE))
                    .setDigests(KeyProperties.DIGEST_NONE)
            else ->
                builder
                    .setAlgorithmParameterSpec(ECGenParameterSpec(curveName(spec.algorithm)))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
        }
        builder.setIsStrongBoxBacked(strongBox)
        KeystoreUserAuth(platform.sdkInt).apply(builder, spec.userAuth)
        return builder.build()
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    private fun isAtLeastTiramisu(): Boolean = platform.sdkInt >= Build.VERSION_CODES.TIRAMISU

    public companion object {
        /** The JCA provider (and `KeyStore` type) of the Android Keystore. */
        public const val PROVIDER: String = "AndroidKeyStore"

        /** `PackageManager.FEATURE_HARDWARE_KEYSTORE` (API 31); spelled out so lint needn't guard it. */
        internal const val FEATURE_HARDWARE_KEYSTORE: String = "android.hardware.hardware_keystore"

        /** KeyMint 2.0 (feature version 200) added Curve25519. */
        internal const val KEYMINT_CURVE25519_VERSION: Int = 200

        internal const val ED25519_CURVE: String = "ed25519"

        /** StrongBox implementations are only required to support P-256 (and RSA-2048, which we don't use). */
        private val STRONGBOX_ALGORITHMS = setOf(KeyAlgorithm.ECDSA_P256)

        internal fun curveName(algorithm: KeyAlgorithm): String =
            if (algorithm == KeyAlgorithm.ECDSA_P384) "secp384r1" else "secp256r1"
    }
}
