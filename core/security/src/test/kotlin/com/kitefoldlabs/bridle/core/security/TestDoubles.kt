package com.kitefoldlabs.bridle.core.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.security.testdata.TestKeyFiles
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher

/** Software [KeyStoreBackend] (Robolectric has no Android Keystore). */
class SoftwareKeyStoreBackend(
    override val isStrongBoxAvailable: Boolean = false,
    override val supportsEd25519: Boolean = true,
) : KeyStoreBackend {
    val keys = linkedMapOf<String, KeyPair>()
    val specs = mutableListOf<KeyGenSpec>()

    override fun generateKeyPair(
        alias: String,
        spec: KeyGenSpec,
    ): GeneratedKey {
        specs += spec
        val pair =
            when (spec.algorithm) {
                KeyAlgorithm.ED25519 -> TestKeyFiles.ed25519()
                KeyAlgorithm.RSA -> KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                else ->
                    KeyPairGenerator
                        .getInstance("EC")
                        .apply { initialize(ECGenParameterSpec(AndroidKeyStoreBackend.curveName(spec.algorithm))) }
                        .generateKeyPair()
            }
        keys[alias] = pair
        return GeneratedKey(pair.public, HardwareLevel.SOFTWARE)
    }

    override fun privateKey(alias: String): PrivateKey? = keys[alias]?.private

    override fun publicKey(alias: String): PublicKey? = keys[alias]?.public

    override fun delete(alias: String) {
        keys.remove(alias)
    }

    override fun aliases(): List<String> = keys.keys.toList()
}

/** [BiometricGate] that answers from [answers] (then [default]) and records every request. */
class FakeGate(
    private vararg val answers: Boolean,
    private val default: Boolean = true,
) : BiometricGate {
    val reasons = mutableListOf<String>()
    val signatures = mutableListOf<Signature>()

    override suspend fun authenticate(reason: String): Boolean {
        reasons += reason
        return answers.getOrElse(reasons.size - 1) { default }
    }

    override suspend fun authenticate(
        reason: String,
        signature: Signature,
    ): Boolean {
        signatures += signature
        return authenticate(reason)
    }

    val ciphers = mutableListOf<Cipher>()

    override suspend fun authenticate(
        reason: String,
        cipher: Cipher,
    ): Boolean {
        ciphers += cipher
        return authenticate(reason)
    }
}

/**
 * Software [KeyWrappingStore] that simulates the Keystore's enforcement: while [locked], a
 * time-bound key's cipher can't be initialised ([UserNotAuthenticatedException]), and a per-use
 * key's cipher only works once [authorize] was called with it (as a `CryptoObject` prompt does);
 * until then it is keyed with a decoy and `doFinal` fails.
 */
class SoftwareWrappingStore : KeyWrappingStore {
    val keys = linkedMapOf<String, Pair<KeyPair, UserAuthPolicy>>()
    var locked = true
    var invalidated = false
    var failCreate: Exception? = null
    var failWrap = false
    private val perUse = mutableMapOf<Cipher, String>()
    private val decoy by lazy { rsa() }

    override fun create(
        alias: String,
        policy: UserAuthPolicy,
    ) {
        failCreate?.let { throw it }
        keys[alias] = rsa() to policy
    }

    override fun wrap(
        alias: String,
        dataKey: ByteArray,
    ): ByteArray {
        if (failWrap) throw StoredKeyException("missing")
        return Cipher.getInstance(AndroidKeyStoreWrappingStore.TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, keys.getValue(alias).first.public, AndroidKeyStoreWrappingStore.OAEP)
            doFinal(dataKey)
        }
    }

    override fun unwrapCipher(alias: String): Cipher {
        if (invalidated) throw KeyPermanentlyInvalidatedException()
        val (pair, policy) = keys[alias] ?: throw StoredKeyException("missing")
        if (locked && policy is UserAuthPolicy.TimeBound) throw UserNotAuthenticatedException()
        val cipher = Cipher.getInstance(AndroidKeyStoreWrappingStore.TRANSFORMATION)
        if (policy == UserAuthPolicy.PerUse) {
            cipher.init(Cipher.DECRYPT_MODE, decoy.private, AndroidKeyStoreWrappingStore.OAEP)
            perUse[cipher] = alias
        } else {
            cipher.init(Cipher.DECRYPT_MODE, pair.private, AndroidKeyStoreWrappingStore.OAEP)
        }
        return cipher
    }

    /** What a successful `CryptoObject` prompt does: [cipher] may now use its real key. */
    fun authorize(cipher: Cipher) {
        val alias = perUse.remove(cipher) ?: return
        cipher.init(Cipher.DECRYPT_MODE, keys.getValue(alias).first.private, AndroidKeyStoreWrappingStore.OAEP)
    }

    override fun delete(alias: String) {
        keys.remove(alias)
    }

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}

/** A gate that unlocks [store] (time-bound keys) and authorises per-use ciphers it is shown. */
class UnlockingGate(
    private val store: SoftwareWrappingStore,
    private val answer: Boolean = true,
) : BiometricGate {
    var plain = 0
    var bound = 0

    override suspend fun authenticate(reason: String): Boolean {
        plain++
        if (answer) store.locked = false
        return answer
    }

    override suspend fun authenticate(
        reason: String,
        cipher: Cipher,
    ): Boolean {
        bound++
        if (answer) store.authorize(cipher)
        return answer
    }
}

/** Gate that only implements the mandatory method, to exercise the interface default. */
class PlainGate(
    private val answer: Boolean,
) : BiometricGate {
    var calls = 0

    override suspend fun authenticate(reason: String): Boolean {
        calls++
        return answer
    }
}

/** In-memory Tink AES-256-GCM keyset. */
class MemoryAeadProvider : AeadProvider {
    private val aead: Aead =
        run {
            AeadConfig.register()
            KeysetHandle
                .generateNew(PredefinedAeadParameters.AES256_GCM, RegistryConfiguration.get())
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    var calls = 0

    override fun aead(): Aead {
        calls++
        return aead
    }
}
