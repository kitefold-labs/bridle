package com.kitefoldlabs.bridle.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import net.schmizz.sshj.transport.verification.HostKeyVerifier as SshjHostKeyVerifier

/**
 * [SshEngine] backed by sshj. Blocking sshj calls run on [ioDispatcher], which must have more than
 * one thread (the default, [Dispatchers.IO]): cancellation and timeouts are serviced by a sibling
 * coroutine while a call blocks.
 */
public class SshjEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val configFactory: () -> DefaultConfig = ::defaultConfig,
) : SshEngine {
    override suspend fun connect(
        target: ConnectTarget,
        hostKeys: HostKeyVerifier,
        options: ConnectOptions,
    ): SshConnection {
        val attempt = ConnectAttempt()
        var connected: SshjConnection? = null
        try {
            return withContext(ioDispatcher) {
                // sshj's connect/kex/auth calls block and ignore coroutine cancellation; closing the
                // half-open clients is the only way to make them return early.
                // UNDISPATCHED: a cancellation that lands before the watchdog was scheduled must still abort.
                val watchdog =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            attempt.abort()
                        }
                    }
                try {
                    connectBlocking(target, hostKeys, options, attempt).also { connected = it }
                } finally {
                    attempt.complete()
                    watchdog.cancel()
                }
            }
        } catch (e: CancellationException) {
            // Cancelled after the handshake finished: nobody will ever receive this connection.
            connected?.close()
            throw e
        } catch (e: ConnectionFailedException) {
            currentCoroutineContext().ensureActive() // report the cancellation, not its side effect
            throw e
        }
    }

    private fun connectBlocking(
        target: ConnectTarget,
        hostKeys: HostKeyVerifier,
        options: ConnectOptions,
        attempt: ConnectAttempt,
    ): SshjConnection {
        val jump = target.jump?.let { connectBlocking(it, hostKeys, options, attempt) }
        val client = SSHClient(configFactory())
        val verifier = VerifierAdapter(hostKeys)
        val passwords = mutableListOf<OneShotPasswordFinder>()
        var connected = false
        try {
            attempt.register(client)
            client.addHostKeyVerifier(verifier)
            client.connectTimeout = options.connectTimeout.inWholeMilliseconds.toInt()
            client.timeout = options.socketTimeout.inWholeMilliseconds.toInt()
            if (options.keepAlive.isPositive()) {
                // Must be set before connect(): sshj starts the keepalive thread only in onConnect().
                client.connection.keepAlive.keepAliveInterval =
                    options.keepAlive.inWholeSeconds
                        .coerceAtLeast(1)
                        .toInt()
            }
            if (jump == null) {
                client.connect(target.hostname, target.port)
            } else {
                client.connectVia(jump.client.newDirectConnection(target.hostname, target.port))
            }
            client.auth(target.username, target.credentials.flatMap { authMethods(it, passwords) })
            connected = true
            return SshjConnection(client, jump, ioDispatcher)
        } catch (e: IOException) {
            throw translate(e, verifier.failure)
        } finally {
            passwords.forEach { it.wipe() }
            if (!connected) {
                closeQuietly(client)
                jump?.close()
            }
        }
    }

    private fun authMethods(
        credential: Credential,
        passwords: MutableList<OneShotPasswordFinder>,
    ): List<AuthMethod> =
        when (credential) {
            is Credential.Key -> listOf(SignerAuthMethod(credential.signer))
            is Credential.Password -> {
                val finder = OneShotPasswordFinder(credential.supply).also { passwords += it }
                listOf(AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
            }
        }

    private fun translate(
        e: IOException,
        hostKeyFailure: HostKeyException?,
    ): Exception =
        when {
            hostKeyFailure != null -> hostKeyFailure
            e is UserAuthException -> AuthenticationFailedException("Authentication failed: ${e.message}", e)
            else -> ConnectionFailedException(e.message ?: e.javaClass.simpleName, e)
        }

    public companion object {
        /** sshj defaults plus keepalive that detects dead mobile links (no reply → disconnect). */
        public fun defaultConfig(): DefaultConfig =
            DefaultConfig().apply {
                keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
            }
    }
}

/** Bridges our [HostKeyVerifier] to sshj, keeping the typed failure so it can be rethrown. */
internal class VerifierAdapter(
    private val delegate: HostKeyVerifier,
) : SshjHostKeyVerifier {
    @Volatile
    var failure: HostKeyException? = null
        private set

    override fun verify(
        hostname: String,
        port: Int,
        key: PublicKey,
    ): Boolean =
        try {
            delegate.verify(hostname, port, key)
            true
        } catch (e: HostKeyException) {
            failure = e
            false
        }

    override fun findExistingAlgorithms(
        hostname: String,
        port: Int,
    ): List<String> = delegate.knownKeyTypes(hostname, port)
}

/**
 * Hands sshj the password when the server asks for it and never retries with the same secret.
 * sshj copies the characters into the outgoing packet (and wipes that copy) but not the array we
 * return, so every array handed out is zeroed by [wipe] once authentication has finished.
 */
internal class OneShotPasswordFinder(
    private val supply: () -> CharArray,
) : PasswordFinder {
    private val issued = mutableListOf<CharArray>()

    @Synchronized
    override fun reqPassword(resource: Resource<*>?): CharArray = supply().also { issued += it }

    override fun shouldRetry(resource: Resource<*>?): Boolean = false

    @Synchronized
    fun wipe() {
        issued.forEach { it.fill('\u0000') }
        issued.clear()
    }
}

/**
 * The clients opened by one [SshjEngine.connect] call (target plus jump hops), so a cancelled
 * connect can close them all, including hops that are still being set up.
 */
internal class ConnectAttempt {
    private val clients = mutableListOf<SSHClient>()
    private var finished = false

    @Synchronized
    fun register(client: SSHClient) {
        if (finished) throw ConnectionFailedException("Connect was cancelled")
        clients += client
    }

    /** Closes every client registered so far, unless the attempt already [complete]d. */
    @Synchronized
    fun abort() {
        if (finished) return
        finished = true
        for (client in clients) {
            // close() alone leaves a thread waiting for kex/auth asleep until the transport timeout;
            // die() fails those waits immediately. It needs a started transport.
            if (client.transport.isRunning) client.transport.die(IOException("Connect was cancelled"))
            closeQuietly(client)
        }
    }

    @Synchronized
    fun complete() {
        finished = true
    }
}

internal class SshjConnection(
    internal val client: SSHClient,
    private val jump: SshjConnection?,
    private val ioDispatcher: CoroutineDispatcher,
) : SshConnection {
    private val mutableState = MutableStateFlow(ConnectionState.CONNECTED)
    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    init {
        client.transport.setDisconnectListener { _, _ -> mutableState.value = ConnectionState.DISCONNECTED }
    }

    override suspend fun exec(
        command: String,
        timeout: Duration,
    ): ExecResult = run(command, stdin = null, timeout = timeout)

    override suspend fun runScript(
        script: String,
        timeout: Duration,
    ): ExecResult = run("sh -s", stdin = script.toByteArray(Charsets.UTF_8), timeout = timeout)

    private suspend fun run(
        command: String,
        stdin: ByteArray?,
        timeout: Duration,
    ): ExecResult =
        withContext(ioDispatcher) {
            val session = guard { client.startSession() }
            try {
                val cmd = guard { session.exec(command) }
                // Every blocking sshj call runs in its own child. The streams are drained while stdin is
                // fed, so neither side can stall on a full window, and closing the channel (on success,
                // timeout or cancellation) is what makes the blocked reads return. supervisorScope keeps
                // a child's failure from overtaking the timeout/cancellation that caused it.
                supervisorScope {
                    // Not awaited: if the command exits without reading all of stdin the write fails,
                    // and the exit status is still the meaningful result.
                    launch(IgnoreFailures) {
                        cmd.outputStream.use { stream -> stdin?.let(stream::write) } // close() sends EOF
                    }
                    val out = async { cmd.inputStream.readCapped() }
                    val err = async { cmd.errorStream.readCapped() }
                    val exit =
                        async {
                            cmd.join()
                            cmd.exitStatus
                        }
                    try {
                        withTimeoutOrNull(timeout) { ExecResult(exit.await(), out.await(), err.await()) }
                            ?: throw ExecTimeoutException(timeout)
                    } catch (e: IOException) {
                        throw ConnectionFailedException("Remote command failed: ${e.message}", e)
                    } finally {
                        closeQuietly(session)
                    }
                }
            } finally {
                closeQuietly(session)
            }
        }

    override suspend fun openShell(
        pty: PtySpec,
        command: String?,
    ): ShellChannel =
        withContext(ioDispatcher) {
            val session = guard { client.startSession() } as SessionChannel
            try {
                session.allocatePTY(pty.term, pty.columns, pty.rows, 0, 0, emptyMap())
                if (command == null) session.startShell() else session.exec(command)
                SshjShellChannel(session, ioDispatcher)
            } catch (e: IOException) {
                closeQuietly(session)
                throw ConnectionFailedException("Could not open shell: ${e.message}", e)
            }
        }

    override suspend fun openSftp(): SftpSession =
        withContext(ioDispatcher) {
            SshjSftpSession(guard { client.newSFTPClient() }, ioDispatcher)
        }

    override suspend fun openDirectTcpip(
        host: String,
        port: Int,
    ): DuplexStream =
        withContext(ioDispatcher) {
            val connection = guard { client.newDirectConnection(host, port) }
            DirectStream(connection)
        }

    override fun close() {
        closeQuietly(client)
        jump?.close()
        mutableState.value = ConnectionState.DISCONNECTED
    }

    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: SSHException) {
            throw ConnectionFailedException(e.message ?: "SSH error", e)
        } catch (e: IllegalStateException) {
            // sshj signals "not connected" this way once the transport is gone.
            throw ConnectionFailedException(e.message ?: "Not connected", e)
        }
}

internal class SshjShellChannel(
    private val session: SessionChannel,
    private val ioDispatcher: CoroutineDispatcher,
) : ShellChannel {
    private val closed = AtomicBoolean(false)
    override val input: InputStream get() = session.inputStream
    override val output: OutputStream get() = session.outputStream
    override val isOpen: Boolean get() = !closed.get() && session.isOpen
    override val exitStatus: Int? get() = session.exitStatus

    override suspend fun resize(
        columns: Int,
        rows: Int,
    ) {
        PtySpec(columns, rows) // validates
        withContext(ioDispatcher) {
            try {
                session.changeWindowDimensions(columns, rows, 0, 0)
            } catch (e: TransportException) {
                throw ConnectionFailedException("Resize failed: ${e.message}", e)
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) closeQuietly(session)
    }
}

internal class DirectStream(
    private val connection: DirectConnection,
) : DuplexStream {
    override val input: InputStream get() = connection.inputStream
    override val output: OutputStream get() = connection.outputStream

    override fun close() = closeQuietly(connection)
}

/** Raised when an exec exceeds its timeout; the channel is closed. */
public class ExecTimeoutException(
    public val timeout: Duration,
) : Exception("Remote command did not finish within $timeout")

/** Reads to EOF, keeping at most [MAX_EXEC_OUTPUT] bytes so a runaway command cannot exhaust memory. */
internal fun InputStream.readCapped(limit: Int = MAX_EXEC_OUTPUT): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_SIZE)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        val room = limit - out.size()
        if (room > 0) out.write(buffer, 0, minOf(n, room))
    }
    return out.toString(Charsets.UTF_8)
}

/** For children whose failure is expected and deliberately ignored. */
private val IgnoreFailures = CoroutineExceptionHandler { _, _ -> }

internal const val MAX_EXEC_OUTPUT: Int = 4 * 1024 * 1024
private const val BUFFER_SIZE: Int = 8 * 1024

internal fun closeQuietly(closeable: AutoCloseable) {
    try {
        closeable.close()
    } catch (_: IOException) {
        // Closing a dead channel/transport is expected to fail; nothing useful to report.
    }
}
