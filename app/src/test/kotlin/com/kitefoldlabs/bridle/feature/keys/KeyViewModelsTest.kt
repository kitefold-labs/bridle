package com.kitefoldlabs.bridle.feature.keys

import android.app.Application
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.security.ImportedKey
import com.kitefoldlabs.bridle.core.security.ImportedKeyVault
import com.kitefoldlabs.bridle.core.security.KeyFileFormat
import com.kitefoldlabs.bridle.core.security.KeyFileInfo
import com.kitefoldlabs.bridle.core.security.KeyImportException
import com.kitefoldlabs.bridle.core.security.KeyVaultUnavailableException
import com.kitefoldlabs.bridle.core.security.KeystoreKeyManager
import com.kitefoldlabs.bridle.core.security.UserAuthPolicy
import com.kitefoldlabs.bridle.feature.MainDispatcherRule
import com.kitefoldlabs.bridle.feature.MemoryAead
import com.kitefoldlabs.bridle.feature.SoftwareBackend
import com.kitefoldlabs.bridle.feature.SoftwareWrapping
import com.kitefoldlabs.bridle.feature.TestEnv
import com.kitefoldlabs.bridle.feature.TestKeys
import com.kitefoldlabs.bridle.feature.await
import com.kitefoldlabs.bridle.feature.hosts.DocumentReader
import com.kitefoldlabs.bridle.feature.hosts.document
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeyViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val env = TestEnv()
    private val backend = SoftwareBackend(supportsEd25519 = true)
    private val keystore = KeystoreKeyManager(backend, deviceName = "Pixel")
    private val library =
        KeyLibrary(env.data.identities, keystore, ImportedKeyVault(MemoryAead(), wrappingKeys = SoftwareWrapping()))
    private val documents = DocumentReader(env.context)

    @After
    fun tearDown() = env.close()

    @Test
    fun `auth settings map to policies`() {
        AuthSetting().policy() shouldBe UserAuthPolicy.None
        AuthSetting(AuthChoice.TIMED, 15).policy() shouldBe UserAuthPolicy.TimeBound(900)
        AuthSetting(AuthChoice.EVERY_USE).policy() shouldBe UserAuthPolicy.PerUse
    }

    @Test
    fun `keys list shows badges and fingerprints`() =
        runBlocking<Unit> {
            val vm = KeysViewModel(env.data.identities)
            vm.state.await { !it.loading }.keys shouldBe emptyList()
            library.generate("Pixel", KeyAlgorithm.ECDSA_P256, UserAuthPolicy.None)
            env.data.identities.insert(
                Identity(
                    alias = "Zz broken",
                    source = KeySource.IMPORTED,
                    algorithm = KeyAlgorithm.RSA,
                    publicKeyOpenSsh = "ssh-rsa !!",
                ),
            )
            val rows = vm.state.await { it.keys.size == 2 }.keys
            rows[0].isHardware shouldBe true
            rows[0].fingerprint!!.startsWith("SHA256:") shouldBe true
            rows[1].isHardware shouldBe false
            rows[1].fingerprint shouldBe null
        }

    @Test
    fun `generate validates the label and creates the key`() =
        runBlocking<Unit> {
            val vm = KeyGenerateViewModel(library)
            vm.state.value.algorithms
                .first() shouldBe KeyAlgorithm.ECDSA_P256
            vm.generate()
            vm.state.value.labelError shouldBe Validation.Reason.BLANK
            vm.setLabel("Pixel")
            vm.state.value.labelError shouldBe null
            vm.setAlgorithm(KeyAlgorithm.ECDSA_P384)
            vm.setAuth(AuthSetting(AuthChoice.TIMED, 1))
            vm.generate()
            vm.generate() // already busy: ignored
            val id = vm.state.await { it.createdId != null }.createdId!!
            val identity = env.data.identities.get(id)!!
            identity.algorithm shouldBe KeyAlgorithm.ECDSA_P384
            identity.requireUserAuth shouldBe true
            identity.authValiditySeconds shouldBe 60
            env.data.identities
                .observeAll()
                .first()
                .size shouldBe 1

            backend.failGeneration = true
            val failing = KeyGenerateViewModel(library)
            failing.setLabel("x")
            failing.generate()
            failing.state.await { it.failed }.createdId shouldBe null
        }

    @Test
    fun `import a plain key, from text or file`() =
        runBlocking<Unit> {
            val vm = KeyImportViewModel(library, documents)
            vm.submit()
            vm.state.value.labelError shouldBe Validation.Reason.BLANK
            vm.setLabel("Laptop")
            vm.submit()
            vm.state.value.error shouldBe KeyImportError.EMPTY
            vm.loadFile(env.document("key", TestKeys.pkcs8Pem()))
            vm.state.await { it.keyText.isNotEmpty() && !it.busy }.error shouldBe null
            vm.setAuth(AuthSetting(AuthChoice.EVERY_USE))
            vm.submit()
            vm.submit() // already busy: ignored
            val done = vm.state.await { it.createdId != null }
            done.keyText shouldBe "" // the private key isn't kept in memory once imported
            env.data.identities
                .get(done.createdId!!)!!
                .authValiditySeconds shouldBe 0
        }

    @Test
    fun `import errors are reported`() =
        runBlocking<Unit> {
            val vm = KeyImportViewModel(library, documents)
            vm.setLabel("k")
            vm.setKeyText(TestKeys.PUTTY)
            vm.submit()
            vm.state.await { it.error == KeyImportError.UNSUPPORTED_FORMAT }
            vm.setKeyText("not a key")
            vm.submit()
            vm.state.await { it.error == KeyImportError.MALFORMED }
            vm.loadFile(env.document("unreadable", null))
            vm.state.await { it.error == KeyImportError.READ_FAILED }
            vm.loadFile(env.document("huge", "x".repeat(KeyImportViewModel.MAX_KEY_CHARS + 1)))
            vm.state.await { it.error == KeyImportError.TOO_LARGE }
        }

    @Test
    fun `encrypted keys ask for the passphrase and retry`() =
        runBlocking<Unit> {
            val vault = mockk<ImportedKeyVault>()
            val release = CountDownLatch(1)
            every { vault.inspect(any()) } returns KeyFileInfo(KeyFileFormat.OPENSSH, encrypted = true)
            val identity =
                Identity(
                    alias = "k",
                    source = KeySource.IMPORTED,
                    algorithm = KeyAlgorithm.ED25519,
                    publicKeyOpenSsh = "x",
                )
            every { vault.import(any(), any(), any(), any(), any()) } answers {
                when (secondArg<CharArray?>()?.let(::String)) {
                    "" -> throw KeyImportException.PassphraseRequired()
                    "wrong" -> throw KeyImportException.WrongPassphrase()
                    "dsa" -> throw KeyImportException.UnsupportedKeyType("ssh-dss")
                    "vault" -> throw KeyVaultUnavailableException("no keystore")
                    "slow" -> {
                        release.await()
                        throw KeyImportException.Malformed()
                    }
                    else -> ImportedKey(identity, byteArrayOf(1))
                }
            }
            val vm = KeyImportViewModel(KeyLibrary(env.data.identities, keystore, vault), documents)
            vm.setLabel("k")
            vm.setKeyText("encrypted")
            vm.submit()
            vm.state.await { it.askPassphrase }.wrongPassphrase shouldBe false

            val wrong = "wrong".toCharArray()
            vm.submitPassphrase(wrong)
            vm.state.await { it.wrongPassphrase && !it.busy }
            wrong.all { it == '\u0000' } shouldBe true
            vm.submitPassphrase(CharArray(0))
            vm.state.await { !it.wrongPassphrase && it.askPassphrase && !it.busy }
            vm.cancelPassphrase()
            vm.state.value.askPassphrase shouldBe false

            vm.submitPassphrase("dsa".toCharArray())
            vm.state.await { it.error == KeyImportError.UNSUPPORTED_TYPE }.unsupportedType shouldBe "ssh-dss"
            vm.submitPassphrase("vault".toCharArray())
            vm.state.await { it.error == KeyImportError.VAULT_UNAVAILABLE }
            vm.submitPassphrase("slow".toCharArray())
            val dropped = "x".toCharArray()
            vm.submitPassphrase(dropped) // busy: zeroed and ignored
            dropped[0] shouldBe '\u0000'
            release.countDown()
            vm.state.await { it.error == KeyImportError.MALFORMED && !it.busy }
            vm.submitPassphrase("right".toCharArray())
            vm.state.await { it.createdId != null }
        }
}
