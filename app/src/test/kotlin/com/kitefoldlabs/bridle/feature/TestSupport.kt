package com.kitefoldlabs.bridle.feature

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.security.AeadProvider
import com.kitefoldlabs.bridle.core.security.GeneratedKey
import com.kitefoldlabs.bridle.core.security.HardwareLevel
import com.kitefoldlabs.bridle.core.security.KeyGenSpec
import com.kitefoldlabs.bridle.core.security.KeyGenerationException
import com.kitefoldlabs.bridle.core.security.KeyStoreBackend
import com.kitefoldlabs.bridle.core.security.KeyWrappingStore
import com.kitefoldlabs.bridle.core.security.UserAuthPolicy
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.session.HostConnector
import com.kitefoldlabs.bridle.core.ssh.ConnectionState
import com.kitefoldlabs.bridle.core.ssh.DuplexStream
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.ssh.ShellChannel
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.service.SessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runs view-model coroutines eagerly on the calling thread (Room resumes them on its own threads). */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(Dispatchers.Unconfined)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** An in-memory database plus the session plumbing view models need. */
class TestEnv(
    connector: HostConnector = HostConnector { throw IllegalStateException("no connections in this test") },
) : AutoCloseable {
    val context: Application = ApplicationProvider.getApplicationContext()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val data: DataModule =
        DataModule.inMemory(
            context,
            PreferenceDataStoreFactory.create(
                scope = scope,
            ) { File(context.cacheDir, "s-${System.nanoTime()}.preferences_pb") },
            allowMainThreadQueries = true,
        )
    val connections = ConnectionManager(connector, scope)
    val coordinator = SessionCoordinator(connections, scope)

    override fun close() {
        data.close()
        scope.cancel()
    }
}

/** Waits (in real time: Room works on its own threads) for the first value matching [predicate]. */
suspend fun <T> Flow<T>.await(
    timeout: Duration = 30.seconds,
    predicate: (T) -> Boolean,
): T = withTimeout(timeout) { first(predicate) }

/** Software [KeyStoreBackend]: Robolectric has no Android Keystore. */
class SoftwareBackend(
    override val supportsEd25519: Boolean = false,
    override val isStrongBoxAvailable: Boolean = false,
) : KeyStoreBackend {
    val keys = linkedMapOf<String, KeyPair>()
    var failGeneration = false

    /** Thrown by [delete] when set. */
    var deleteFailure: Exception? = null

    override fun generateKeyPair(
        alias: String,
        spec: KeyGenSpec,
    ): GeneratedKey {
        if (failGeneration) throw KeyGenerationException("refused")
        val pair = if (spec.algorithm == KeyAlgorithm.RSA) TestKeys.rsa() else TestKeys.ec()
        keys[alias] = pair
        return GeneratedKey(pair.public, HardwareLevel.SOFTWARE)
    }

    override fun privateKey(alias: String): PrivateKey? = keys[alias]?.private

    override fun publicKey(alias: String): PublicKey? = keys[alias]?.public

    override fun delete(alias: String) {
        deleteFailure?.let { throw it }
        keys.remove(alias)
    }

    override fun aliases(): List<String> = keys.keys.toList()
}

/** Software [KeyWrappingStore] (RSA-OAEP like the Keystore one, but never locked). */
class SoftwareWrapping : KeyWrappingStore {
    val keys = linkedMapOf<String, KeyPair>()

    override fun create(
        alias: String,
        policy: UserAuthPolicy,
    ) {
        keys[alias] = TestKeys.rsa()
    }

    override fun wrap(
        alias: String,
        dataKey: ByteArray,
    ): ByteArray = cipher(Cipher.ENCRYPT_MODE, keys.getValue(alias).public).doFinal(dataKey)

    override fun unwrapCipher(alias: String): Cipher = cipher(Cipher.DECRYPT_MODE, keys.getValue(alias).private)

    override fun delete(alias: String) {
        keys.remove(alias)
    }

    private fun cipher(
        mode: Int,
        key: java.security.Key,
    ): Cipher =
        Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(mode, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        }
}

/** In-memory Tink AES-256-GCM. */
class MemoryAead : AeadProvider {
    private val aead: Aead by lazy {
        AeadConfig.register()
        KeysetHandle
            .generateNew(PredefinedAeadParameters.AES256_GCM, RegistryConfiguration.get())
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun aead(): Aead = aead
}

/** Throwaway keys, generated at test time so no key material is committed. */
object TestKeys {
    fun ec(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

    fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** An unencrypted PKCS#8 PEM file. The armour is assembled so the source doesn't look like a key. */
    fun pkcs8Pem(pair: KeyPair = ec()): String {
        val label = "PRIVATE" + " KEY"
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

    const val PUTTY: String = "PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: none\n"
}

/** A connection that records scripts and answers them with [output]. */
class ScriptedConnection(
    var output: ExecResult = ExecResult(0, "BRIDLE_KEY_ADDED\n", ""),
) : SshConnection {
    val scripts = mutableListOf<String>()
    var closed = false
    override val state = MutableStateFlow(ConnectionState.CONNECTED)

    override suspend fun exec(
        command: String,
        timeout: Duration,
    ) = ExecResult(0, "", "")

    override suspend fun runScript(
        script: String,
        timeout: Duration,
    ): ExecResult {
        scripts += script
        return output
    }

    override suspend fun openShell(
        pty: PtySpec,
        command: String?,
    ): ShellChannel =
        object : ShellChannel {
            override val input: InputStream = InputStream.nullInputStream()
            override val output: OutputStream = OutputStream.nullOutputStream()
            override val isOpen = true
            override val exitStatus: Int? = null

            override suspend fun resize(
                columns: Int,
                rows: Int,
            ) = Unit

            override fun close() = Unit
        }

    override suspend fun openDirectTcpip(
        host: String,
        port: Int,
    ): DuplexStream = throw UnsupportedOperationException()

    override fun close() {
        closed = true
        state.value = ConnectionState.DISCONNECTED
    }
}

/** A [HostConnector] whose behaviour tests can change. */
class FakeConnector(
    var connect: suspend (EntityId) -> SshConnection = { ScriptedConnection() },
) : HostConnector {
    override suspend fun connect(hostId: EntityId): SshConnection = connect.invoke(hostId)
}
