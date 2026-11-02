package com.kitefoldlabs.bridle.connect

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.ssh.AuthenticationFailedException
import com.kitefoldlabs.bridle.core.ssh.ConnectOptions
import com.kitefoldlabs.bridle.core.ssh.ConnectTarget
import com.kitefoldlabs.bridle.core.ssh.Credential
import com.kitefoldlabs.bridle.core.ssh.HostKeyPrompt
import com.kitefoldlabs.bridle.core.ssh.HostKeyVerifier
import com.kitefoldlabs.bridle.core.ssh.JcaSshKeySigner
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.core.ssh.SshEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Records what the connector asked for. */
private class CapturingEngine(
    private val connection: SshConnection,
) : SshEngine {
    var target: ConnectTarget? = null
    var verifier: HostKeyVerifier? = null
    var options: ConnectOptions? = null

    override suspend fun connect(
        target: ConnectTarget,
        hostKeys: HostKeyVerifier,
        options: ConnectOptions,
    ): SshConnection {
        this.target = target
        this.verifier = hostKeys
        this.options = options
        return connection
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val data =
        DataModule.inMemory(
            context,
            PreferenceDataStoreFactory.create(
                scope = scope,
            ) { File(context.cacheDir, "s-${System.nanoTime()}.preferences_pb") },
            allowMainThreadQueries = true,
        )

    @After
    fun tearDown() {
        data.close()
        scope.cancel()
    }

    @Test
    fun `broker hands questions to the UI and times out`() =
        runBlocking<Unit> {
            val broker = InteractionBroker(timeout = 2.seconds)
            val prompt = HostKeyPrompt("h", 22, "ssh-ed25519", "SHA256:x")
            val trust = async(Dispatchers.IO) { broker.confirmNewHost(prompt) }
            while (broker.pending.value == null) delay(5.milliseconds)
            broker.pending.value shouldBe Interaction.TrustHost(prompt)
            broker.answer(broker.pending.value!!, true)
            trust.await() shouldBe true
            broker.pending.value shouldBe null

            val password = async(Dispatchers.IO) { broker.requestPassword("Dev", "nate") }
            while (broker.pending.value == null) delay(5.milliseconds)
            broker.pending.value shouldBe Interaction.Password("Dev", "nate")
            broker.answer(broker.pending.value!!, "hunter2".toCharArray())
            String(password.await()!!) shouldBe "hunter2"

            val late = "late".toCharArray()
            broker.answer(Interaction.Password("Dev", "nate"), late) // nothing pending: dropped and zeroed
            late.all { it == '\u0000' } shouldBe true
            InteractionBroker(timeout = 50.milliseconds).confirmNewHost(prompt) shouldBe false
            InteractionBroker(timeout = 50.milliseconds).requestPassword("a", "b") shouldBe null
            InteractionBroker().pending.value shouldBe null
        }

    @Test
    fun `an answer to a stale question never answers the next one`() =
        runBlocking<Unit> {
            val broker = InteractionBroker(timeout = 300.milliseconds)
            val first =
                async(Dispatchers.IO) { broker.confirmNewHost(HostKeyPrompt("a", 22, "ssh-ed25519", "SHA256:a")) }
            while (broker.pending.value == null) delay(5.milliseconds)
            val shown = broker.pending.value!! // the dialog for host a
            first.await() shouldBe false // the user was too slow
            val second =
                async(Dispatchers.IO) { broker.confirmNewHost(HostKeyPrompt("b", 22, "ssh-ed25519", "SHA256:b")) }
            while (broker.pending.value == null) delay(5.milliseconds)
            broker.answer(shown, true) // "Trust" tapped on a's dialog
            // An equal question that the UI never showed doesn't count either.
            broker.answer(Interaction.TrustHost(HostKeyPrompt("b", 22, "ssh-ed25519", "SHA256:b")), true)
            second.await() shouldBe false // b was never trusted

            val password = async(Dispatchers.IO) { broker.requestPassword("Dev", "nate") }
            while (broker.pending.value == null) delay(5.milliseconds)
            val stale = "for-a".toCharArray()
            broker.answer(shown, stale)
            stale.all { it == '\u0000' } shouldBe true
            broker.answer(broker.pending.value!!, null) // cancelled
            password.await() shouldBe null
            val again = "twice".toCharArray()
            broker.answer(Interaction.Password("Dev", "nate"), again)
            again.all { it == '\u0000' } shouldBe true
        }

    @Test
    fun `withdrawing an attempt's questions closes them at once`() =
        runBlocking<Unit> {
            val broker = InteractionBroker(timeout = 10.seconds)
            val prompt = HostKeyPrompt("h", 22, "ssh-ed25519", "SHA256:x")
            val attempt = broker.questions()
            val other = broker.questions()
            val trust = async(Dispatchers.IO) { attempt.confirmNewHost(prompt) }
            while (broker.pending.value == null) delay(5.milliseconds)
            other.withdraw() // another attempt's: this question stays
            delay(50.milliseconds)
            broker.pending.value shouldBe Interaction.TrustHost(prompt)
            val started = System.nanoTime()
            attempt.withdraw()
            trust.await() shouldBe false
            (System.nanoTime() - started < 5_000_000_000L) shouldBe true // not the 10 s timeout
            broker.pending.value shouldBe null
            attempt.withdraw() // nothing showing: no-op
            // Asked after the attempt ended: answered at once, never shown.
            attempt.requestPassword("Dev", "nate") shouldBe null
            broker.pending.value shouldBe null
        }

    @Test
    fun `a password typed into a withdrawn question is zeroed`() =
        runBlocking<Unit> {
            val broker = InteractionBroker(timeout = 10.seconds)
            val attempt = broker.questions()
            val asked = async(Dispatchers.IO) { attempt.requestPassword("Dev", "nate") }
            while (broker.pending.value == null) delay(5.milliseconds)
            val shown = broker.pending.value!!
            attempt.withdraw()
            asked.await() shouldBe null
            val late = "hunter2".toCharArray()
            broker.answer(shown, late) // the dialog's OK, tapped as it closed
            late.all { it == '\u0000' } shouldBe true
        }

    @Test
    fun `cancelling a connection attempt closes its password question`() =
        runBlocking<Unit> {
            val hostId = data.hosts.upsert(Host(label = "Dev", hostname = "dev.example.com", username = "nate"))
            val broker = InteractionBroker(timeout = 10.seconds)
            // Like sshj: the password is asked for on a blocked I/O thread that cancellation can't reach.
            val engine =
                object : SshEngine {
                    override suspend fun connect(
                        target: ConnectTarget,
                        hostKeys: HostKeyVerifier,
                        options: ConnectOptions,
                    ): SshConnection =
                        withContext(Dispatchers.IO) {
                            val typed =
                                target.credentials
                                    .single()
                                    .shouldBeInstanceOf<Credential.Password>()
                                    .supply()
                            // No cancellation check: the I/O thread may see the withdrawn (empty)
                            // password before the cancellation reaches it.
                            throw AuthenticationFailedException("rejected ${typed.size}")
                        }
                }
            val connector =
                DefaultHostConnector(
                    data.hosts,
                    data.knownHosts,
                    HostCredentials(data.identities, { null }, broker),
                    broker,
                    engine,
                ) { 1L }
            val attempt = scope.async { connector.connect(hostId) }
            while (broker.pending.value == null) delay(5.milliseconds)
            broker.pending.value shouldBe Interaction.Password("Dev", "nate")
            withTimeout(5.seconds) { attempt.cancelAndJoin() }
            broker.pending.value shouldBe null
            // Reported as cancelled, not as the rejected empty password (which would go uncaught).
            shouldThrow<CancellationException> { attempt.await() }
            data.hosts.get(hostId)!!.lastConnectedAtEpochMs shouldBe null
        }

    @Test
    fun `connector resolves hosts, credentials and records the connection`() =
        runBlocking<Unit> {
            val pair =
                KeyPairGenerator
                    .getInstance(
                        "EC",
                    ).apply { initialize(ECGenParameterSpec("secp256r1")) }
                    .generateKeyPair()
            val identityId =
                data.identities.insert(
                    Identity(
                        alias = "phone",
                        source = KeySource.IMPORTED,
                        algorithm = KeyAlgorithm.ECDSA_P256,
                        publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAA phone",
                    ),
                )
            val bastion = data.hosts.upsert(Host(label = "Bastion", hostname = "bastion.example.com", username = "ops"))
            val hostId =
                data.hosts.upsert(
                    Host(
                        label = "GPU",
                        hostname = "gpu.internal",
                        username = "ml",
                        identityId = identityId,
                        jumpHostId = bastion,
                    ),
                )
            val connection = mockk<SshConnection>()
            val engine = CapturingEngine(connection)
            val broker = InteractionBroker(timeout = 50.milliseconds)
            val signer = JcaSshKeySigner(pair.public, pair.private)
            val credentials = HostCredentials(data.identities, { signer }, broker)
            val connector = DefaultHostConnector(data.hosts, data.knownHosts, credentials, broker, engine) { 1234L }

            connector.connect(hostId) shouldBe connection
            val target = engine.target!!
            target.hostname shouldBe "gpu.internal"
            target.jump!!.hostname shouldBe "bastion.example.com"
            target.credentials
                .first()
                .shouldBeInstanceOf<Credential.Key>()
                .signer shouldBe signer
            val password = target.credentials.last().shouldBeInstanceOf<Credential.Password>()
            password.supply().size shouldBe 0 // nobody answered → empty, server rejects
            target.jump!!.credentials.size shouldBe 1 // bastion has no key: password only
            engine.options!!.keepAlive shouldBe 30.seconds
            engine.verifier!!.knownKeyTypes("gpu.internal", 22) shouldBe emptyList()
            data.hosts.get(hostId)!!.lastConnectedAtEpochMs shouldBe 1234L

            val noKey = HostCredentials(data.identities, { null }, broker)
            noKey.credentialsFor(data.hosts.get(hostId)!!).size shouldBe 1
            shouldThrow<UnknownHostException> { connector.connect(999) }
        }
}
