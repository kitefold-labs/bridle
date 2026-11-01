package com.kitefoldlabs.bridle.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Per-identity Keystore keys that make user authentication a cryptographic requirement for
 * imported keys, instead of a check Bridle performs.
 *
 * Each imported key that requires authentication gets its own key pair here, created with
 * `setUserAuthenticationRequired`. The imported key's data key is wrapped with the public half,
 * which needs no authentication, so importing never prompts. Unwrapping is done by the Keystore
 * and only after the user authenticated: per-use keys through a `BiometricPrompt` bound to the
 * unwrapping [Cipher] (`CryptoObject`), time-bound keys within their validity window.
 */
public interface KeyWrappingStore {
    /**
     * Creates the wrapping key [alias] (which must not exist) protected by [policy], which must
     * require authentication.
     *
     * @throws KeyVaultUnavailableException if the Keystore can't create it (for example, no
     *   screen lock or no enrolled biometric).
     */
    public fun create(
        alias: String,
        policy: UserAuthPolicy,
    )

    /**
     * Encrypts [dataKey] for [alias]. Needs no authentication.
     *
     * @throws StoredKeyException if [alias] doesn't exist.
     */
    public fun wrap(
        alias: String,
        dataKey: ByteArray,
    ): ByteArray

    /**
     * A [Cipher] initialised to unwrap data keys with [alias]. For a time-bound key whose window
     * has expired this throws `UserNotAuthenticatedException`; a per-use key's cipher must be
     * authorised with a `BiometricPrompt.CryptoObject` before `doFinal`.
     *
     * @throws StoredKeyException if [alias] doesn't exist.
     */
    public fun unwrapCipher(alias: String): Cipher

    /** Deletes [alias]; deleting a missing alias is not an error. */
    public fun delete(alias: String)
}

/**
 * [KeyWrappingStore] on the Android Keystore: RSA-OAEP (SHA-256, MGF1 with SHA-1, which every
 * Keystore version supports) key pairs with `PURPOSE_DECRYPT` only.
 */
public class AndroidKeyStoreWrappingStore(
    private val platform: KeystorePlatform,
    private val providerName: String = AndroidKeyStoreBackend.PROVIDER,
) : KeyWrappingStore {
    public constructor(context: Context) : this(KeystorePlatform.of(context))

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(providerName).apply { load(null) } }

    // Key generation reports a missing screen lock or biometric as IllegalStateException.
    @Suppress("TooGenericExceptionCaught")
    override fun create(
        alias: String,
        policy: UserAuthPolicy,
    ) {
        require(policy.requiresAuth) { "Wrapping keys exist only for keys that require user authentication" }
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, providerName).run {
                initialize(parameterSpec(alias, policy))
                generateKeyPair()
            }
        } catch (e: GeneralSecurityException) {
            throw KeyVaultUnavailableException(CANT_CREATE, e)
        } catch (e: ProviderException) {
            throw KeyVaultUnavailableException(CANT_CREATE, e)
        } catch (e: IllegalStateException) {
            throw KeyVaultUnavailableException(CANT_CREATE, e)
        }
    }

    /** The exact [KeyGenParameterSpec] used for a wrapping key; internal for tests. */
    internal fun parameterSpec(
        alias: String,
        policy: UserAuthPolicy,
    ): KeyGenParameterSpec {
        val builder =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KEY_SIZE)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
        KeystoreUserAuth(platform.sdkInt).apply(builder, policy)
        return builder.build()
    }

    override fun wrap(
        alias: String,
        dataKey: ByteArray,
    ): ByteArray {
        val certified = keyStore.getCertificate(alias)?.publicKey ?: throw StoredKeyException(MISSING)
        // A plain copy of the public key, so any provider can encrypt with it.
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(certified.encoded))
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, publicKey, OAEP)
            doFinal(dataKey)
        }
    }

    override fun unwrapCipher(alias: String): Cipher {
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: throw StoredKeyException(MISSING)
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, privateKey, OAEP) }
    }

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    internal companion object {
        const val KEY_SIZE: Int = 2048
        const val TRANSFORMATION: String = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        val OAEP: OAEPParameterSpec =
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        private const val CANT_CREATE = "The Android Keystore can't protect this key with user authentication"
        private const val MISSING = "This key's protection was removed from the Android Keystore; import it again"
    }
}
