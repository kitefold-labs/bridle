package com.kitefoldlabs.bridle.feature.keys

import android.app.Application
import androidx.fragment.app.FragmentActivity
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.security.AndroidKeyStoreBackend
import com.kitefoldlabs.bridle.core.security.AndroidKeyStoreWrappingStore
import com.kitefoldlabs.bridle.core.security.AndroidKeystoreAeadProvider
import com.kitefoldlabs.bridle.core.security.BiometricGate
import com.kitefoldlabs.bridle.core.security.BiometricPromptGate
import com.kitefoldlabs.bridle.core.security.ImportedKeyVault
import com.kitefoldlabs.bridle.core.security.KeyGenerationException
import com.kitefoldlabs.bridle.core.security.KeyImportException
import com.kitefoldlabs.bridle.core.security.KeystoreKeyManager
import com.kitefoldlabs.bridle.core.security.UserAuthPolicy
import com.kitefoldlabs.bridle.core.security.UserAuthenticationException
import com.kitefoldlabs.bridle.core.ssh.OpenSshKeys
import com.kitefoldlabs.bridle.di.KeystoreModule
import com.kitefoldlabs.bridle.di.SecurityModules
import com.kitefoldlabs.bridle.feature.MemoryAead
import com.kitefoldlabs.bridle.feature.SoftwareBackend
import com.kitefoldlabs.bridle.feature.SoftwareWrapping
import com.kitefoldlabs.bridle.feature.TestEnv
import com.kitefoldlabs.bridle.feature.TestKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import java.security.Signature
import javax.crypto.Cipher

/** Gate that answers [answer] and counts prompts. */
private class CountingGate(
    private val answer: Boolean,
) : BiometricGate {
    var prompts = 0

    override suspend fun authenticate(reason: String): Boolean {
        prompts++
        return answer
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeyStorageTest {
    private val env = TestEnv()
    private val backend = SoftwareBackend()
    private val keystore = KeystoreKeyManager(backend, deviceName = "Pixel 9")
    private val wrapping = SoftwareWrapping()
    private val vault = ImportedKeyVault(MemoryAead(), wrappingKeys = wrapping)
    private val library = KeyLibrary(env.data.identities, keystore, vault)

    @After
    fun tearDown() = env.close()

    private fun source(gate: BiometricGate = CountingGate(true)) =
        KeyStoreSignerSource(keystore, vault, env.data.identities, gate, env.context)

    private fun verifiesRsa(
        identity: Identity,
        signature: ByteArray,
        data: ByteArray,
    ) {
        Signature.getInstance("SHA256withRSA").run {
            initVerify(OpenSshKeys.parseAuthorizedKeyLine(identity.publicKeyOpenSsh))
            update(data)
            verify(signature)
        } shouldBe true
    }

    @Test
    fun `generated keys are saved, sign, and are deleted with their keystore entry`() =
        runBlocking<Unit> {
            library.availableAlgorithms() shouldBe
                listOf(KeyAlgorithm.ECDSA_P256, KeyAlgorithm.ECDSA_P384, KeyAlgorithm.RSA)
            val id = library.generate("Pixel", KeyAlgorithm.RSA, UserAuthPolicy.None)
            val identity = env.data.identities.get(id)!!
            identity.source shouldBe KeySource.KEYSTORE
            identity.publicKeyOpenSsh.endsWith("bridle@pixel-9") shouldBe true
            backend.keys.size shouldBe 1

            val signer = source().signer(identity).shouldNotBeNull()
            val data = byteArrayOf(1, 2, 3)
            verifiesRsa(identity, signer.sign("rsa-sha2-256", data), data)

            library.delete(identity)
            env.data.identities
                .get(id)
                .shouldBeNull()
            backend.keys.size shouldBe 0
            source().signer(identity).shouldBeNull() // Keystore entry gone
        }

    @Test
    fun `failed generation and failed saves leave nothing behind`() =
        runBlocking<Unit> {
            backend.failGeneration = true
            shouldThrow<KeyGenerationException> { library.generate("x", KeyAlgorithm.ECDSA_P256, UserAuthPolicy.None) }
            backend.failGeneration = false
            shouldThrow<IllegalArgumentException> {
                library.generate(
                    " ",
                    KeyAlgorithm.ECDSA_P256,
                    UserAuthPolicy.None,
                )
            }
            backend.keys.size shouldBe 0
        }

    @Test
    fun `keystore errors while deleting are tolerated`() =
        runBlocking<Unit> {
            val id = library.generate("Pixel", KeyAlgorithm.ECDSA_P256, UserAuthPolicy.None)
            backend.failDelete = true
            library.delete(env.data.identities.get(id)!!)
            env.data.identities
                .get(id)
                .shouldBeNull()
        }

    @Test
    fun `imported keys are encrypted, sign through the vault, and need the gate when protected`() =
        runBlocking<Unit> {
            val text = TestKeys.pkcs8Pem().toCharArray()
            library.inspect(text).encrypted shouldBe false
            val id = library.import(text, null, "Laptop", UserAuthPolicy.PerUse)
            wrapping.keys.size shouldBe 1
            val identity = env.data.identities.get(id)!!
            identity.source shouldBe KeySource.IMPORTED
            env.data.identities
                .encryptedPrivateKey(id)
                .shouldNotBeNull()

            val gate = CountingGate(true)
            val signer = source(gate).signer(identity).shouldNotBeNull()
            signer.sign("ecdsa-sha2-nistp256", byteArrayOf(9)).isNotEmpty() shouldBe true
            gate.prompts shouldBe 1

            val refused = source(CountingGate(false)).signer(identity).shouldNotBeNull()
            shouldThrow<UserAuthenticationException> { refused.sign("ecdsa-sha2-nistp256", byteArrayOf(9)) }

            library.delete(identity) // also removes its Keystore wrapping key
            env.data.identities
                .observeAll()
                .first() shouldBe emptyList()
            wrapping.keys.size shouldBe 0

            val plain =
                env.data.identities.get(
                    library.import(TestKeys.pkcs8Pem().toCharArray(), null, "p", UserAuthPolicy.None),
                )!!
            library.delete(plain) // no wrapping key to remove
            library.delete(plain) // already gone: no ciphertext
            val damaged =
                env.data.identities.insert(
                    plain.copy(id = 0, alias = "d", publicKeyOpenSsh = "junk"),
                    byteArrayOf(2, 0, 0),
                )
            library.delete(env.data.identities.get(damaged)!!) // unparseable public key: tolerated
            val failing = mockk<ImportedKeyVault> { every { delete(any(), any()) } throws StoredKeyFailure() }
            val other = env.data.identities.insert(plain.copy(id = 0, alias = "o"), byteArrayOf(2))
            KeyLibrary(env.data.identities, keystore, failing).delete(env.data.identities.get(other)!!)
            env.data.identities
                .observeAll()
                .first() shouldBe emptyList()
        }

    @Test
    fun `unusable identities have no signer`() =
        runBlocking<Unit> {
            val broken =
                Identity(
                    id = 5,
                    alias = "x",
                    source = KeySource.IMPORTED,
                    algorithm = KeyAlgorithm.ED25519,
                    publicKeyOpenSsh = "junk",
                )
            source().signer(broken).shouldBeNull() // no ciphertext stored
            val id = env.data.identities.insert(broken.copy(id = 0), byteArrayOf(1, 2, 3))
            source().signer(broken.copy(id = id)).shouldBeNull() // public key doesn't parse

            val ok =
                env.data.identities.get(
                    library.import(TestKeys.pkcs8Pem().toCharArray(), null, "k", UserAuthPolicy.None),
                )!!
            val failingVault =
                mockk<ImportedKeyVault> {
                    every { signer(any(), any(), any(), any()) } throws
                        StoredKeyFailure()
                }
            KeyStoreSignerSource(
                keystore,
                failingVault,
                env.data.identities,
                CountingGate(true),
                env.context,
            ).signer(ok).shouldBeNull()

            shouldThrow<KeyImportException.UnsupportedFormat> { library.inspect(TestKeys.PUTTY.toCharArray()) }
        }

    @Test
    fun `the gate prompts over the resumed activity only`() =
        runBlocking<Unit> {
            val tracker = ResumedActivityTracker()
            val prompted = mutableListOf<FragmentActivity>()
            val gate =
                ActivityBiometricGate(tracker) { activity ->
                    prompted += activity
                    CountingGate(true)
                }
            gate.authenticate("why") shouldBe false
            gate.authenticate("why", Signature.getInstance("SHA256withECDSA")) shouldBe false
            gate.authenticate("why", Cipher.getInstance("AES/GCM/NoPadding")) shouldBe false

            val controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
            tracker.attach(controller.get())
            controller.start().resume()
            tracker.current() shouldBeSameInstanceAs controller.get()
            gate.authenticate("why") shouldBe true
            gate.authenticate("why", Signature.getInstance("SHA256withECDSA")) shouldBe true
            gate.authenticate("why", Cipher.getInstance("AES/GCM/NoPadding")) shouldBe true
            prompted.size shouldBe 3

            val other = Robolectric.buildActivity(FragmentActivity::class.java).create()
            tracker.attach(other.get())
            other.start().resume()
            tracker.current() shouldBeSameInstanceAs other.get()
            controller.pause() // an activity that isn't the current one pausing changes nothing
            tracker.current() shouldBeSameInstanceAs other.get()
            other.pause()
            tracker.current().shouldBeNull()

            val real = ActivityBiometricGate(tracker, env.context)
            real.promptFor(controller.get()).shouldBeInstanceOf<BiometricPromptGate>()
        }

    @Test
    fun `security modules build the production objects`() {
        KeystoreModule.keyStoreBackend(env.context).shouldBeInstanceOf<AndroidKeyStoreBackend>()
        KeystoreModule.aeadProvider(env.context).shouldBeInstanceOf<AndroidKeystoreAeadProvider>()
        SecurityModules.keystoreKeyManager(backend).availableAlgorithms().first() shouldBe KeyAlgorithm.ECDSA_P256
        KeystoreModule.keyWrappingStore(env.context).shouldBeInstanceOf<AndroidKeyStoreWrappingStore>()
        SecurityModules.importedKeyVault(MemoryAead(), wrapping).shouldBeInstanceOf<ImportedKeyVault>()
        val gate = ActivityBiometricGate(ResumedActivityTracker(), env.context)
        SecurityModules.biometricGate(gate) shouldBeSameInstanceAs gate
    }
}

private class StoredKeyFailure : GeneralSecurityException("damaged")
