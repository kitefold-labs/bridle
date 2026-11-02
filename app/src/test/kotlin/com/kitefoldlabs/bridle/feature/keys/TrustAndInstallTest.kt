package com.kitefoldlabs.bridle.feature.keys

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.kitefoldlabs.bridle.connect.Interaction
import com.kitefoldlabs.bridle.connect.InteractionBroker
import com.kitefoldlabs.bridle.connect.UnknownHostException
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KnownHost
import com.kitefoldlabs.bridle.core.security.ImportedKeyVault
import com.kitefoldlabs.bridle.core.security.KeystoreKeyManager
import com.kitefoldlabs.bridle.core.security.UserAuthPolicy
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.ssh.AuthenticationFailedException
import com.kitefoldlabs.bridle.core.ssh.ConnectionFailedException
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.HostKeyChangedException
import com.kitefoldlabs.bridle.core.ssh.HostKeyPrompt
import com.kitefoldlabs.bridle.core.ssh.HostKeyRejectedException
import com.kitefoldlabs.bridle.core.ssh.KeyInstallResult
import com.kitefoldlabs.bridle.core.ssh.RemoteCommandException
import com.kitefoldlabs.bridle.feature.FakeConnector
import com.kitefoldlabs.bridle.feature.MainDispatcherRule
import com.kitefoldlabs.bridle.feature.MemoryAead
import com.kitefoldlabs.bridle.feature.ScriptedConnection
import com.kitefoldlabs.bridle.feature.SoftwareBackend
import com.kitefoldlabs.bridle.feature.TestEnv
import com.kitefoldlabs.bridle.feature.await
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrustAndInstallTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val connector = FakeConnector()
    private val env = TestEnv(connector)
    private val data = env.data
    private val library =
        KeyLibrary(data.identities, KeystoreKeyManager(SoftwareBackend()), ImportedKeyVault(MemoryAead()))
    private val alerts = HostKeyAlerts()
    private val installer = KeyInstaller(connector, data.hosts, alerts)

    @After
    fun tearDown() = env.close()

    private fun changed(host: String = "dev.example") =
        HostKeyChangedException(host, 22, "ssh-ed25519", listOf("SHA256:old"), "SHA256:new")

    @Test
    fun `installing a key adds it to authorized_keys and makes it the host's key`() =
        runBlocking<Unit> {
            val identity =
                data.identities.get(
                    library.generate("Pixel", KeyAlgorithm.ECDSA_P256, UserAuthPolicy.None),
                )!!
            val hostId = data.hosts.upsert(Host(label = "Dev", hostname = "dev.example", username = "nate"))
            val connection = ScriptedConnection()
            connector.connect = { connection }
            installer.install(identity, hostId) shouldBe InstallOutcome.Installed(KeyInstallResult.ADDED)
            connection.closed shouldBe true
            connection.scripts.single().contains(identity.publicKeyOpenSsh.split(" ")[1]) shouldBe true
            data.hosts.get(hostId)!!.identityId shouldBe identity.id

            connection.output = ExecResult(1, "", "Permission denied")
            installer.install(identity, hostId) shouldBe InstallOutcome.Failed(InstallFailure.REMOTE_FAILED)
            connection.output = ExecResult(0, "BRIDLE_KEY_PRESENT\n", "")
            installer.install(identity, hostId) shouldBe InstallOutcome.Installed(KeyInstallResult.ALREADY_PRESENT)
            data.hosts.delete(hostId)
            installer.install(identity, hostId) shouldBe InstallOutcome.Installed(KeyInstallResult.ALREADY_PRESENT)
        }

    @Test
    fun `install failures are classified`() =
        runBlocking<Unit> {
            val identity =
                data.identities.get(
                    library.generate("Pixel", KeyAlgorithm.ECDSA_P256, UserAuthPolicy.None),
                )!!

            suspend fun failure(error: Exception): InstallOutcome {
                connector.connect = { throw error }
                return installer.install(identity, 1)
            }
            failure(AuthenticationFailedException("no")) shouldBe InstallOutcome.Failed(InstallFailure.AUTH_FAILED)
            failure(ConnectionFailedException("down")) shouldBe InstallOutcome.Failed(InstallFailure.UNREACHABLE)
            failure(HostKeyRejectedException(HostKeyPrompt("h", 22, "t", "f"))) shouldBe
                InstallOutcome.Failed(InstallFailure.HOST_NOT_TRUSTED)
            failure(RemoteCommandException("x", ExecResult(1, "", "denied"))) shouldBe
                InstallOutcome.Failed(InstallFailure.REMOTE_FAILED)
            failure(UnknownHostException(1)) shouldBe InstallOutcome.Failed(InstallFailure.HOST_MISSING)
            failure(IllegalStateException("?")) shouldBe InstallOutcome.Failed(InstallFailure.OTHER)
            val keyChanged = changed()
            failure(keyChanged) shouldBe InstallOutcome.Failed(InstallFailure.HOST_KEY_CHANGED)
            alerts.current.value shouldBe keyChanged
            shouldThrow<CancellationException> { failure(CancellationException("stop")) }
        }

    @Test
    fun `key details install, and delete closes the screen`() =
        runBlocking<Unit> {
            library.generate("Another", KeyAlgorithm.ECDSA_P256, UserAuthPolicy.None)
            val id = library.generate("Pixel", KeyAlgorithm.ECDSA_P256, UserAuthPolicy.None)
            val hostId = data.hosts.upsert(Host(label = "Dev", hostname = "dev.example", username = "nate"))
            val gate = CompletableDeferred<Unit>()
            KeyDetailViewModel(SavedStateHandle(), data.identities, data.hosts, library, installer)
                .state
                .await { !it.loading }
                .gone shouldBe true
            connector.connect = {
                gate.await()
                ScriptedConnection()
            }
            val vm =
                KeyDetailViewModel(
                    SavedStateHandle(mapOf(KeyDetailViewModel.ARG_IDENTITY_ID to id)),
                    data.identities,
                    data.hosts,
                    library,
                    installer,
                )
            val loaded = vm.state.await { it.identity != null && it.hosts.isNotEmpty() }
            loaded.fingerprint!!.startsWith("SHA256:") shouldBe true
            vm.install(404) // unknown host: ignored
            vm.state.value.install shouldBe InstallState.Idle
            vm.install(hostId)
            vm.state.await { it.install == InstallState.Running("Dev") }
            vm.install(hostId) // already running: ignored
            vm.dismissInstall() // not done yet: stays running
            vm.state.value.install shouldBe InstallState.Running("Dev")
            gate.complete(Unit)
            val done = vm.state.await { it.install is InstallState.Done }.install
            done shouldBe InstallState.Done("Dev", InstallOutcome.Installed(KeyInstallResult.ADDED))
            vm.dismissInstall()
            vm.state.await { it.install == InstallState.Idle }

            vm.requestDelete()
            vm.state.await { it.confirmDelete }
            vm.cancelDelete()
            vm.state.await { !it.confirmDelete }
            vm.requestDelete()
            vm.confirmDelete()
            vm.state.await { it.gone }
            data.identities.get(id) shouldBe null
            vm.install(hostId) // no identity: ignored
            vm.confirmDelete() // no identity: ignored
        }

    @Test
    fun `known hosts can be forgotten after confirmation`() =
        runBlocking<Unit> {
            val known = KnownHost("dev.example", 22, "ssh-ed25519", "AAAA", "SHA256:abc", 1, 1)
            data.knownHosts.trust(known)
            val vm = KnownHostsViewModel(data.knownHosts)
            vm.state
                .await { it.hosts.isNotEmpty() }
                .hosts
                .single() shouldBe known
            vm.confirmForget() // nothing pending
            vm.requestForget(known)
            vm.state.await { it.pendingForget == known }
            vm.cancelForget()
            vm.state.await { it.pendingForget == null }
            vm.requestForget(known)
            vm.confirmForget()
            vm.state.await { !it.loading && it.hosts.isEmpty() }
        }

    @Test
    fun `endpoints are shown like ssh does`() {
        endpoint("dev.example", 22) shouldBe "dev.example"
        endpoint("dev.example", 2222) shouldBe "dev.example:2222"
        endpoint("fe80::1", 2222) shouldBe "[fe80::1]:2222"
    }

    @Test
    fun `alerts show each changed key once`() {
        val error = changed()
        alerts.report(error)
        alerts.current.value shouldBe error
        alerts.dismiss()
        alerts.report(error)
        alerts.current.value shouldBe null
    }

    @Test
    fun `interaction view model answers the broker and forgets old keys`() =
        runBlocking<Unit> {
            val broker = InteractionBroker(timeout = 5.seconds)
            val vm = InteractionViewModel(broker, alerts, data.knownHosts, data.hosts, env.coordinator)
            val stale = Interaction.Password("Old", "nate")
            vm.trust(Interaction.TrustHost(HostKeyPrompt("old.example", 22, "t", "f")), true) // nothing pending
            val stray = "x".toCharArray()
            vm.password(stale, stray) // nothing pending: zeroed
            stray[0] shouldBe '\u0000'
            vm.password(stale, null)

            val prompt = HostKeyPrompt("dev.example", 22, "ssh-ed25519", "SHA256:x")
            val trust = async(Dispatchers.IO) { broker.confirmNewHost(prompt) }
            val question = vm.pending.await { it != null }.shouldBeInstanceOf<Interaction.TrustHost>()
            vm.password(stale, "no".toCharArray()) // answers another question: not delivered
            vm.trust(question.copy(), true) // an equal but different question: not delivered
            vm.trust(question, true)
            trust.await() shouldBe true

            val password = async(Dispatchers.IO) { broker.requestPassword("Dev", "nate") }
            val asked = vm.pending.await { it != null }.shouldBeInstanceOf<Interaction.Password>()
            vm.trust(Interaction.TrustHost(HostKeyPrompt("x", 22, "t", "f")), false) // not the pending question
            vm.password(asked, "hunter2".toCharArray())
            String(password.await()!!) shouldBe "hunter2"

            data.knownHosts.trust(KnownHost("dev.example", 22, "ssh-ed25519", "AAAA", "SHA256:old"))
            vm.forgetOldKey() // no warning showing
            alerts.report(changed())
            vm.keyChanged.await { it != null }
            vm.forgetOldKey()
            vm.keyChanged.value shouldBe null
            withRetry { data.knownHosts.find("dev.example", 22).isEmpty() }

            alerts.report(changed("other.example"))
            vm.dismissKeyChanged()
            vm.keyChanged.value shouldBe null
        }

    @Test
    fun `changed host keys on shared connections raise the warning`() =
        runBlocking<Unit> {
            val hostId = data.hosts.upsert(Host(label = "Dev", hostname = "dev.example", username = "nate"))
            val error = changed()
            connector.connect = { throw error }
            val vm = InteractionViewModel(InteractionBroker(), alerts, data.knownHosts, data.hosts, env.coordinator)
            env.coordinator.openTab(hostId, "Dev", AttachSpec.Shell, mockk(relaxed = true))
            vm.keyChanged.await { it != null } shouldBe error
            env.coordinator.disconnectAll()
        }

    private suspend fun withRetry(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            delay(10.milliseconds)
        }
        condition() shouldBe true
    }
}
