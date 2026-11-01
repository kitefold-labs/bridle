package com.kitefoldlabs.bridle.connect

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
            broker.answer(true)
            trust.await() shouldBe true
            broker.pending.value shouldBe null

            val password = async(Dispatchers.IO) { broker.requestPassword("Dev", "nate") }
            while (broker.pending.value == null) delay(5.milliseconds)
            broker.pending.value shouldBe Interaction.Password("Dev", "nate")
            broker.answer("hunter2".toCharArray())
            String(password.await()!!) shouldBe "hunter2"

            broker.answer(true) // nothing pending: ignored
            InteractionBroker(timeout = 50.milliseconds).confirmNewHost(prompt) shouldBe false
            InteractionBroker(timeout = 50.milliseconds).requestPassword("a", "b") shouldBe null
            InteractionBroker().pending.value shouldBe null
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
