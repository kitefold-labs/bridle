package com.kitefoldlabs.bridle.core.security

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
