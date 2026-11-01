package com.kitefoldlabs.bridle.core.security

import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.ssh.JcaSshKeySigner
import com.kitefoldlabs.bridle.core.ssh.OpenSshKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeystoreKeyManagerTest {
    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun manager(backend: KeyStoreBackend = SoftwareKeyStoreBackend()) =
        KeystoreKeyManager(backend, deviceName = "Pixel 9 Pro", clock = { 42L }, newId = { uuid })

    @Test
    fun `creates a keystore identity with an openssh public key`() {
        val backend = SoftwareKeyStoreBackend()
        val identity = manager(backend).createIdentity("Phone key")
        identity.alias shouldBe "Phone key"
        identity.source shouldBe KeySource.KEYSTORE
        identity.algorithm shouldBe KeyAlgorithm.ECDSA_P256
        identity.keystoreAlias shouldBe "bridle-key-$uuid"
        identity.createdAtEpochMs shouldBe 42L
        identity.requireUserAuth shouldBe false
        identity.publicKeyOpenSsh shouldStartWith "ecdsa-sha2-nistp256 AAAA"
        identity.publicKeyOpenSsh shouldEndWith " bridle@pixel-9-pro"
        OpenSshKeys.blob(OpenSshKeys.parseAuthorizedKeyLine(identity.publicKeyOpenSsh)) shouldBe
            OpenSshKeys.blob(backend.keys.getValue("bridle-key-$uuid").public)
        backend.specs.single() shouldBe KeyGenSpec(KeyAlgorithm.ECDSA_P256)
    }

    @Test
    fun `every offered algorithm can be generated and signs verifiably`() {
        for (algorithm in manager().availableAlgorithms()) {
            val m = KeystoreKeyManager(SoftwareKeyStoreBackend(), deviceName = "x")
            val identity = m.createIdentity("k", algorithm, comment = "c")
            identity.algorithm shouldBe algorithm
            identity.publicKeyOpenSsh shouldStartWith algorithm.sshName
            val signer = m.signer(identity)
            signer.shouldBeInstanceOf<JcaSshKeySigner>()
            val sshAlgorithm = if (algorithm == KeyAlgorithm.RSA) "rsa-sha2-512" else algorithm.sshName
            signer.sign(sshAlgorithm, byteArrayOf(9)).isNotEmpty() shouldBe true
        }
    }

    @Test
    fun `ed25519 is only offered when the keystore supports it`() {
        manager().availableAlgorithms() shouldContainExactly
            listOf(KeyAlgorithm.ECDSA_P256, KeyAlgorithm.ED25519, KeyAlgorithm.ECDSA_P384, KeyAlgorithm.RSA)
        val old = manager(SoftwareKeyStoreBackend(supportsEd25519 = false))
        old.availableAlgorithms() shouldContainExactly
            listOf(KeyAlgorithm.ECDSA_P256, KeyAlgorithm.ECDSA_P384, KeyAlgorithm.RSA)
        shouldThrow<KeyGenerationException> { old.createIdentity("k", KeyAlgorithm.ED25519) }
        shouldThrow<KeyGenerationException> { old.createIdentity("k", KeyAlgorithm.ECDSA_P521) }
    }

    @Test
    fun `strongbox availability is reported`() {
        manager(SoftwareKeyStoreBackend(isStrongBoxAvailable = true)).isStrongBoxAvailable shouldBe true
        manager().isStrongBoxAvailable shouldBe false
    }

    @Test
    fun `user auth policy is recorded on the identity and the key`() {
        val backend = SoftwareKeyStoreBackend()
        val timed = manager(backend).createIdentity("t", userAuth = UserAuthPolicy.TimeBound(90))
        timed.requireUserAuth shouldBe true
        timed.authValiditySeconds shouldBe 90
        val perUse = manager(backend).createIdentity("p", userAuth = UserAuthPolicy.PerUse)
        perUse.requireUserAuth shouldBe true
        perUse.authValiditySeconds shouldBe 0
        backend.specs.map { it.userAuth } shouldContainExactly
            listOf(UserAuthPolicy.TimeBound(90), UserAuthPolicy.PerUse)
    }

    @Test
    fun `signers honour the auth policy`() {
        val m = KeystoreKeyManager(SoftwareKeyStoreBackend(), deviceName = "x")
        val timed = m.createIdentity("t", userAuth = UserAuthPolicy.TimeBound(30))
        val perUse = m.createIdentity("p", userAuth = UserAuthPolicy.PerUse)
        shouldThrow<IllegalArgumentException> { m.signer(timed) }
        shouldThrow<IllegalArgumentException> { m.signer(perUse) }
        val gate = FakeGate()
        m.signer(timed, gate, "why").shouldBeInstanceOf<GatedSigner>()
        val perUseSigner = m.signer(perUse, gate, "sign in")
        perUseSigner.shouldBeInstanceOf<PerUseKeystoreSigner>()
        perUseSigner.sign("ecdsa-sha2-nistp256", byteArrayOf(1)).isNotEmpty() shouldBe true
        gate.reasons shouldContainExactly listOf("sign in")
    }

    @Test
    fun `delete, exists and orphan cleanup`() {
        val backend = SoftwareKeyStoreBackend()
        var next = 0
        val m = KeystoreKeyManager(backend, deviceName = "x", newId = { UUID(0, (++next).toLong()) })
        val kept = m.createIdentity("kept")
        val orphan = m.createIdentity("orphan")
        backend.keys["someone-else"] = backend.keys.getValue(kept.keystoreAlias!!)
        m.exists(kept) shouldBe true
        m.orphanedAliases(listOf(kept, imported())) shouldContainExactly listOf(orphan.keystoreAlias!!)
        m.deleteAlias(orphan.keystoreAlias!!)
        m.exists(orphan) shouldBe false
        shouldThrow<IllegalArgumentException> { m.deleteAlias("someone-else") }
        m.delete(kept)
        m.exists(kept) shouldBe false
        shouldThrow<IllegalStateException> { m.signer(kept) }
        backend.keys.keys shouldContainExactly setOf("someone-else")
    }

    @Test
    fun `missing public key is reported`() {
        val backend =
            object : KeyStoreBackend by SoftwareKeyStoreBackend() {
                val inner = SoftwareKeyStoreBackend()

                override fun generateKeyPair(
                    alias: String,
                    spec: KeyGenSpec,
                ) = inner.generateKeyPair(alias, spec)

                override fun privateKey(alias: String) = inner.privateKey(alias)

                override fun publicKey(alias: String) = null
            }
        val m = KeystoreKeyManager(backend, deviceName = "x")
        shouldThrow<IllegalStateException> { m.signer(m.createIdentity("k")) }
    }

    @Test
    fun `imported identities are rejected`() {
        shouldThrow<IllegalArgumentException> { manager().signer(imported()) }
        shouldThrow<IllegalArgumentException> { manager().delete(imported()) }
    }

    @Test
    fun `device names are sanitised for key comments`() {
        KeystoreKeyManager.sanitizeDeviceName("Pixel 9 Pro") shouldBe "pixel-9-pro"
        KeystoreKeyManager.sanitizeDeviceName("  SM-S918B/DS ") shouldBe "sm-s918b-ds"
        KeystoreKeyManager.sanitizeDeviceName("日本") shouldBe "android"
        KeystoreKeyManager.sanitizeDeviceName("a".repeat(100)).length shouldBe 40
        KeystoreKeyManager(SoftwareKeyStoreBackend()).defaultComment shouldBe "bridle@robolectric"
    }

    private fun imported() =
        Identity(
            alias = "imp",
            source = KeySource.IMPORTED,
            algorithm = KeyAlgorithm.ED25519,
            publicKeyOpenSsh = "ssh-ed25519 AAAA",
        )
}
