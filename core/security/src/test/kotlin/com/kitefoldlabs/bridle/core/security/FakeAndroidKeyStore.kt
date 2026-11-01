package com.kitefoldlabs.bridle.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.StrongBoxUnavailableException
import com.kitefoldlabs.bridle.core.security.testdata.TestKeyFiles
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyPairGeneratorSpi
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration

/**
 * Minimal JCA stand-in for the `AndroidKeyStore` provider (Robolectric has none): an in-memory
 * `KeyStore` plus EC/RSA `KeyPairGenerator`s that accept [KeyGenParameterSpec] and record it.
 * Ed25519 public keys are reported with the OID as algorithm name, like real devices do.
 */
class FakeAndroidKeyStore : Provider(AndroidKeyStoreBackend.PROVIDER, 1.0, "Fake Android Keystore for tests") {
    init {
        service("KeyStore", AndroidKeyStoreBackend.PROVIDER) { FakeKeyStoreSpi() }
        service("KeyPairGenerator", "EC") { FakeGeneratorSpi("EC") }
        service("KeyPairGenerator", "RSA") { FakeGeneratorSpi("RSA") }
    }

    private fun service(
        type: String,
        algorithm: String,
        create: () -> Any,
    ) {
        putService(
            object : Service(this, type, algorithm, "fake.$type.$algorithm", null, null) {
                override fun newInstance(constructorParameter: Any?): Any = create()
            },
        )
    }

    companion object {
        val entries = linkedMapOf<String, KeyPair>()
        val specs = mutableListOf<KeyGenParameterSpec>()
        var rejectStrongBox = false
        var failure: Exception? = null

        fun install() {
            uninstall()
            Security.addProvider(FakeAndroidKeyStore())
        }

        fun uninstall() {
            Security.removeProvider(AndroidKeyStoreBackend.PROVIDER)
            entries.clear()
            specs.clear()
            rejectStrongBox = false
            failure = null
        }
    }
}

private class FakeGeneratorSpi(
    private val algorithm: String,
) : KeyPairGeneratorSpi() {
    private lateinit var spec: KeyGenParameterSpec

    override fun initialize(
        keysize: Int,
        random: SecureRandom?,
    ): Unit = throw UnsupportedOperationException()

    override fun initialize(
        params: AlgorithmParameterSpec,
        random: SecureRandom?,
    ) {
        spec = params as KeyGenParameterSpec
    }

    override fun generateKeyPair(): KeyPair {
        FakeAndroidKeyStore.specs += spec
        FakeAndroidKeyStore.failure?.let { throw it }
        if (spec.isStrongBoxBacked && FakeAndroidKeyStore.rejectStrongBox) throw StrongBoxUnavailableException()
        val curve = (spec.algorithmParameterSpec as? ECGenParameterSpec)?.name
        val pair =
            when {
                algorithm == "RSA" ->
                    KeyPairGenerator
                        .getInstance(
                            "RSA",
                        ).apply { initialize(spec.keySize) }
                        .generateKeyPair()
                curve == "ed25519" -> {
                    val software = TestKeyFiles.ed25519()
                    KeyPair(OidNamedPublicKey(software.public), software.private)
                }
                else ->
                    KeyPairGenerator
                        .getInstance(
                            "EC",
                        ).apply { initialize(ECGenParameterSpec(curve)) }
                        .generateKeyPair()
            }
        FakeAndroidKeyStore.entries[spec.keystoreAlias] = pair
        return pair
    }
}

/** How Android Keystore exposes Ed25519 public keys: algorithm = curve OID. */
class OidNamedPublicKey(
    private val delegate: PublicKey,
) : PublicKey {
    override fun getAlgorithm(): String = "1.3.101.112"

    override fun getFormat(): String = delegate.format

    override fun getEncoded(): ByteArray = delegate.encoded
}

private class FakeCertificate(
    private val key: PublicKey,
) : Certificate("X.509") {
    override fun getEncoded(): ByteArray = key.encoded

    override fun verify(key: PublicKey?): Unit = throw UnsupportedOperationException()

    override fun verify(
        key: PublicKey?,
        sigProvider: String?,
    ): Unit = throw UnsupportedOperationException()

    override fun toString(): String = "FakeCertificate"

    override fun getPublicKey(): PublicKey = key
}

private class FakeKeyStoreSpi : KeyStoreSpi() {
    private val entries get() = FakeAndroidKeyStore.entries

    override fun engineGetKey(
        alias: String,
        password: CharArray?,
    ): Key? = entries[alias]?.private

    override fun engineGetCertificateChain(alias: String): Array<Certificate>? =
        engineGetCertificate(alias)?.let { arrayOf(it) }

    override fun engineGetCertificate(alias: String): Certificate? = entries[alias]?.let { FakeCertificate(it.public) }

    override fun engineGetCreationDate(alias: String): Date = Date(0)

    override fun engineSetKeyEntry(
        alias: String,
        key: Key,
        password: CharArray?,
        chain: Array<out Certificate>?,
    ): Unit = throw UnsupportedOperationException()

    override fun engineSetKeyEntry(
        alias: String,
        key: ByteArray,
        chain: Array<out Certificate>?,
    ): Unit = throw UnsupportedOperationException()

    override fun engineSetCertificateEntry(
        alias: String,
        cert: Certificate,
    ): Unit = throw UnsupportedOperationException()

    override fun engineDeleteEntry(alias: String) {
        entries.remove(alias)
    }

    override fun engineAliases(): Enumeration<String> = Collections.enumeration(entries.keys.toList())

    override fun engineContainsAlias(alias: String): Boolean = alias in entries

    override fun engineSize(): Int = entries.size

    override fun engineIsKeyEntry(alias: String): Boolean = alias in entries

    override fun engineIsCertificateEntry(alias: String): Boolean = false

    override fun engineGetCertificateAlias(cert: Certificate): String? = null

    override fun engineStore(
        stream: OutputStream?,
        password: CharArray?,
    ): Unit = throw UnsupportedOperationException()

    override fun engineLoad(
        stream: InputStream?,
        password: CharArray?,
    ) = Unit
}
