package com.kitefoldlabs.bridle.core.ssh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** Timeouts, cancellation, cleanup and secret hygiene of [SshjEngine]. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SshjEngineRobustnessTest {
    @TempDir
    lateinit var home: Path

    private val engine = SshjEngine(Dispatchers.IO)
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() = closeables.asReversed().forEach { it.close() }

    private fun <T : AutoCloseable> T.track(): T = also { closeables += it }

    private fun server() = TestSshServer(home).track()

    private fun verifier() = TofuHostKeyVerifier(MemoryKnownHosts(), AcceptingPrompter())

    private fun TestSshServer.authenticatedSessions() = server.activeSessions.count { it.isAuthenticated }

    @Test
    fun `exec timeout fires on time even while the command keeps running`() =
        runBlocking<Unit> {
            val conn = engine.connect(server().target(TestSshServer.password()), verifier()).track()
            val elapsed =
                measureTime {
                    shouldThrow<ExecTimeoutException> { conn.exec("sleep 30", timeout = 300.milliseconds) }
                }
            elapsed shouldBeLessThan 10.seconds
            conn.exec("echo still-usable").stdout shouldBe "still-usable\n"
        }

    @Test
    fun `cancelling exec returns promptly`() =
        runBlocking<Unit> {
            val conn = engine.connect(server().target(TestSshServer.password()), verifier()).track()
            val job = launch { conn.exec("sleep 30", timeout = 60.seconds) }
            delay(300)
            val elapsed =
                measureTime {
                    job.cancel()
                    withTimeout(10.seconds) { job.join() }
                }
            job.isCancelled shouldBe true
            elapsed shouldBeLessThan 10.seconds
        }

    @Test
    fun `a dropped connection during exec is a ConnectionFailedException`() =
        runBlocking<Unit> {
            val srv = server()
            val conn = engine.connect(srv.target(TestSshServer.password()), verifier()).track()
            launch {
                delay(300)
                srv.close()
            }
            shouldThrow<ConnectionFailedException> { conn.exec("sleep 30", timeout = 60.seconds) }
        }

    @Test
    fun `large stdin and large output do not deadlock`() =
        runBlocking<Unit> {
            // The server writes more than a channel window before it reads stdin, and stdin is itself larger
            // than a window: stdin and stdout must be pumped at the same time or both sides wait forever.
            val size = 3_000_000
            val srv = TestSshServer(home, commands = { _, _ -> WriteThenReadCommand(size) }).track()
            val conn = engine.connect(srv.target(TestSshServer.password()), verifier()).track()
            val result = conn.runScript("p".repeat(size), timeout = 60.seconds)
            result.isSuccess shouldBe true
            result.stdout.length shouldBe size + "\nread=$size".length
            result.stdout.endsWith("\nread=$size") shouldBe true
        }

    @Test
    fun `keepalive thread actually runs when enabled`() =
        runBlocking<Unit> {
            val srv = server()
            val on = engine.connect(srv.target(TestSshServer.password()), verifier()).track() as SshjConnection
            on.client.connection.keepAlive.isAlive shouldBe true
            on.client.connection.keepAlive.keepAliveInterval shouldBe 30

            val sub =
                engine
                    .connect(
                        srv.target(TestSshServer.password()),
                        verifier(),
                        ConnectOptions(keepAlive = 200.milliseconds),
                    ).track() as SshjConnection
            sub.client.connection.keepAlive.keepAliveInterval shouldBe 1

            val off =
                engine
                    .connect(
                        srv.target(TestSshServer.password()),
                        verifier(),
                        ConnectOptions(keepAlive = Duration.ZERO),
                    ).track() as SshjConnection
            off.client.connection.keepAlive.isAlive shouldBe false
        }

    @Test
    fun `password arrays are wiped after success and after failure`() =
        runBlocking<Unit> {
            val srv = server()
            val good = TestSshServer.PASSWORD.toCharArray()
            engine.connect(srv.target(Credential.Password { good }), verifier()).track()
            good.all { it == '\u0000' } shouldBe true

            val bad = "wrong".toCharArray()
            shouldThrow<AuthenticationFailedException> {
                engine.connect(srv.target(Credential.Password { bad }), verifier())
            }
            bad.all { it == '\u0000' } shouldBe true
        }

    @Test
    fun `cancelling connect while the host key prompt is open closes the half-open connection`() =
        runBlocking<Unit> {
            val srv = server()
            val prompted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val prompter =
                HostKeyPrompter {
                    prompted.countDown()
                    release.await(60, TimeUnit.SECONDS)
                    true
                }
            try {
                val job =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        engine.connect(
                            srv.target(TestSshServer.password()),
                            TofuHostKeyVerifier(MemoryKnownHosts(), prompter),
                        )
                    }
                prompted.await(10, TimeUnit.SECONDS) shouldBe true
                val elapsed =
                    measureTime {
                        job.cancel()
                        withTimeout(10.seconds) { job.join() }
                    }
                elapsed shouldBeLessThan 10.seconds
                job.isCancelled shouldBe true
                waitFor { srv.server.activeSessions.isEmpty() }
            } finally {
                release.countDown()
            }
        }

    @Test
    fun `a connection completed after cancellation is closed, not leaked`() =
        runBlocking<Unit> {
            val srv = server()
            // The connect body is the first task the engine dispatches; it returns only once the
            // handshake has finished, so the latch opens exactly when a live connection exists.
            val bodyDone = CountDownLatch(1)
            val io =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) = Dispatchers.IO.dispatch(
                        context,
                        Runnable {
                            block.run()
                            bodyDone.countDown()
                        },
                    )
                }
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    SshjEngine(io).connect(srv.target(TestSshServer.password()), verifier())
                }
            // Blocking this (the caller's) thread means the finished connect cannot resume before the cancel.
            bodyDone.await(10, TimeUnit.SECONDS) shouldBe true
            srv.authenticatedSessions() shouldBe 1
            job.cancel()
            job.join()
            job.isCancelled shouldBe true
            waitFor { srv.server.activeSessions.isEmpty() }
        }

    @Test
    fun `a connect cancelled before it starts opens nothing`() =
        runBlocking<Unit> {
            val srv = server()
            val queued = ArrayDeque<Runnable>()
            val held =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        queued += block
                    }
                }
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    SshjEngine(held).connect(srv.target(TestSshServer.password()), verifier())
                }
            job.cancel()
            while (queued.isNotEmpty()) queued.removeFirst().run()
            job.join()
            job.isCancelled shouldBe true
            srv.server.activeSessions.isEmpty() shouldBe true
        }

    @Test
    fun `failures on the final hop close the jump connection`() =
        runBlocking<Unit> {
            val bastion = server()
            val inner = TestSshServer(home).track()
            shouldThrow<AuthenticationFailedException> {
                engine.connect(
                    inner.target(TestSshServer.password("nope"), jump = bastion.target(TestSshServer.password())),
                    verifier(),
                )
            }
            waitFor { bastion.server.activeSessions.isEmpty() }

            // A non-I/O failure (here from the host key prompter) must clean up just the same.
            val throwing =
                HostKeyPrompter { prompt ->
                    check(prompt.port != inner.port) { "boom" }
                    true
                }
            shouldThrow<ConnectionFailedException> {
                engine.connect(
                    inner.target(TestSshServer.password(), jump = bastion.target(TestSshServer.password())),
                    TofuHostKeyVerifier(MemoryKnownHosts(), throwing),
                )
            }
            waitFor { bastion.server.activeSessions.isEmpty() }
        }

    @Test
    fun `connect attempt bookkeeping`() {
        val aborted = ConnectAttempt().apply { abort() }
        shouldThrow<ConnectionFailedException> { aborted.register(SSHClient()) }
        val done = ConnectAttempt().apply { complete() }
        done.abort() // no-op once complete
        shouldThrow<ConnectionFailedException> { done.register(SSHClient()) }
    }

    @Test
    fun `connect options reject durations sshj cannot represent`() {
        shouldThrow<IllegalArgumentException> { ConnectOptions(connectTimeout = (-1).seconds) }
        shouldThrow<IllegalArgumentException> { ConnectOptions(connectTimeout = Duration.INFINITE) }
        shouldThrow<IllegalArgumentException> { ConnectOptions(socketTimeout = 30.days) }
        shouldThrow<IllegalArgumentException> { ConnectOptions(keepAlive = (-1).seconds) }
        ConnectOptions(connectTimeout = Duration.ZERO, socketTimeout = 1.days, keepAlive = 1.days).keepAlive shouldBe
            1.days
    }

    @Test
    fun `password finder hands out fresh arrays and wipes all of them`() {
        val finder = OneShotPasswordFinder { "pw".toCharArray() }
        val first = finder.reqPassword(null)
        val second = finder.reqPassword(null)
        finder.shouldRetry(null) shouldBe false
        finder.wipe()
        (first.concatToString() + second.concatToString()) shouldBe "\u0000\u0000\u0000\u0000"
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(10.seconds) {
            while (!condition()) delay(20)
        }
    }
}

/** Writes [bytes] bytes, then reads stdin to EOF and reports how much arrived; independent threads like sshd. */
private class WriteThenReadCommand(
    private val bytes: Int,
) : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var exit: ExitCallback

    override fun setInputStream(input: InputStream) {
        this.input = input
    }

    override fun setOutputStream(output: OutputStream) {
        this.output = output
    }

    override fun setErrorStream(err: OutputStream) = Unit

    override fun setExitCallback(callback: ExitCallback) {
        exit = callback
    }

    override fun start(
        channel: ChannelSession,
        env: Environment,
    ) {
        thread(isDaemon = true) {
            output.write(ByteArray(bytes) { 'x'.code.toByte() })
            output.flush()
            val read = input.readBytes().size
            output.write("\nread=$read".toByteArray())
            output.flush()
            exit.onExit(0)
        }
    }

    override fun destroy(channel: ChannelSession) = Unit
}
