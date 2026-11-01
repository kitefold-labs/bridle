package com.kitefoldlabs.bridle.core.ssh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SshjEngineTest {
    @TempDir
    lateinit var home: Path

    private val engine = SshjEngine(Dispatchers.IO)
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() = closeables.asReversed().forEach { it.close() }

    private fun <T : AutoCloseable> T.track(): T = also { closeables += it }

    private fun server(vararg keys: java.security.PublicKey) =
        TestSshServer(home, authorizedKeys = keys.toSet()).track()

    private fun verifier(store: MemoryKnownHosts = MemoryKnownHosts()) = TofuHostKeyVerifier(store, AcceptingPrompter())

    @Test
    fun `password auth, exec, exit status and stderr`() =
        runBlocking<Unit> {
            val srv = server()
            val conn = engine.connect(srv.target(TestSshServer.password()), verifier()).track()
            conn.state.value shouldBe ConnectionState.CONNECTED

            val ok = conn.exec("echo hello")
            ok.stdout shouldBe "hello\n"
            ok.isSuccess shouldBe true

            val fail = conn.exec("sh -c 'echo oops >&2; exit 3'")
            fail.exitStatus shouldBe 3
            fail.stderr shouldBe "oops\n"
        }

    @Test
    fun `scripts run through sh stdin with HOME set`() =
        runBlocking<Unit> {
            val srv = server()
            val conn = engine.connect(srv.target(TestSshServer.password()), verifier()).track()
            val r = conn.runScript("x='a b'\nprintf '%s|%s' \"\$x\" \"\$HOME\"\n")
            r.stdout shouldBe "a b|$home"
            AuthorizedKeys.install(conn, OpenSshKeys.authorizedKeyLine(TestKeys.ec().public)) shouldBe
                KeyInstallResult.ADDED
        }

    @Test
    fun `key auth works for ecdsa, ed25519 and rsa via the signer`() =
        runBlocking<Unit> {
            val ec = TestKeys.ec()
            val ed = TestKeys.ed25519()
            val rsa = TestKeys.rsa()
            val srv = server(ec.public, ed.public, rsa.public)
            for (pair in listOf(ec, ed, rsa)) {
                val cred = Credential.Key(JcaSshKeySigner(pair.public, pair.private))
                engine.connect(srv.target(cred), verifier()).use { it.exec("true").isSuccess shouldBe true }
            }
        }

    @Test
    fun `falls back from unknown key to password`() =
        runBlocking<Unit> {
            val stranger = TestKeys.ec()
            val srv = server()
            val target =
                srv.target(
                    Credential.Key(JcaSshKeySigner(stranger.public, stranger.private)),
                    TestSshServer.password(),
                )
            engine.connect(target, verifier()).use { it.exec("true").isSuccess shouldBe true }
        }

    @Test
    fun `bad credentials raise AuthenticationFailedException`() =
        runBlocking<Unit> {
            val srv = server()
            shouldThrow<AuthenticationFailedException> {
                engine.connect(srv.target(TestSshServer.password("wrong")), verifier())
            }
        }

    @Test
    fun `host key problems are surfaced with their typed exception`() =
        runBlocking<Unit> {
            val srv = server()
            shouldThrow<HostKeyRejectedException> {
                engine.connect(
                    srv.target(TestSshServer.password()),
                    TofuHostKeyVerifier(MemoryKnownHosts(), AcceptingPrompter(false)),
                )
            }
            val store = MemoryKnownHosts()
            engine.connect(srv.target(TestSshServer.password()), verifier(store)).close()
            store.hosts.size shouldBe 1
            // Same address, different server key → refuse.
            val impostor = TestSshServer(home, hostKey = TestKeys.ec()).track()
            store.hosts.replaceAll { it.copy(port = impostor.port) }
            val e =
                shouldThrow<HostKeyChangedException> {
                    engine.connect(impostor.target(TestSshServer.password()), verifier(store))
                }
            e.actualFingerprint shouldBe OpenSshKeys.fingerprintSha256(impostor.hostKey.public)
        }

    @Test
    fun `unreachable host raises ConnectionFailedException`() =
        runBlocking<Unit> {
            val closedPort = ServerSocket(0).use { it.localPort }
            val target = ConnectTarget("127.0.0.1", closedPort, "x", listOf(TestSshServer.password()))
            shouldThrow<ConnectionFailedException> { engine.connect(target, verifier()) }
        }

    @Test
    fun `exec times out and output is capped`() =
        runBlocking<Unit> {
            val srv = server()
            val conn = engine.connect(srv.target(TestSshServer.password()), verifier()).track()
            shouldThrow<ExecTimeoutException> { conn.exec("sleep 5", timeout = 300.milliseconds) }
            "x".repeat(10).byteInputStream().readCapped(limit = 4) shouldBe "xxxx"
        }

    @Test
    fun `interactive shell with pty, resize and command mode`() =
        runBlocking<Unit> {
            val srv = server()
            val conn =
                engine
                    .connect(
                        srv.target(TestSshServer.password()),
                        verifier(),
                        ConnectOptions(keepAlive = 5.seconds, socketTimeout = 10.seconds),
                    ).track()

            val shell = conn.openShell(PtySpec(100, 30)).track()
            shell.isOpen shouldBe true
            shell.resize(120, 40)
            shouldThrow<IllegalArgumentException> { shell.resize(0, 1) }
            shell.output.write("echo shell-ok\nexit\n".toByteArray())
            shell.output.flush()
            readUntilEof(shell.input) shouldContain "shell-ok"
            waitFor { !shell.isOpen || shell.exitStatus != null }
            shell.close()
            shell.close() // idempotent
            shell.isOpen shouldBe false

            val cmd = conn.openShell(PtySpec(), command = "echo direct-ok").track()
            readUntilEof(cmd.input) shouldContain "direct-ok"
        }

    @Test
    fun `direct tcpip and proxy jump`() =
        runBlocking<Unit> {
            val echo = ServerSocket(0).track()
            thread(isDaemon = true) {
                echo.accept().use { s -> s.getOutputStream().write(s.getInputStream().read()) }
            }
            val bastion = server()
            val inner = TestSshServer(home).track()
            val conn = engine.connect(bastion.target(TestSshServer.password()), verifier()).track()
            conn.openDirectTcpip("127.0.0.1", echo.localPort).use { stream ->
                stream.output.write(7)
                stream.output.flush()
                stream.input.read() shouldBe 7
            }

            val store = MemoryKnownHosts()
            val viaJump =
                engine
                    .connect(
                        inner.target(TestSshServer.password(), jump = bastion.target(TestSshServer.password())),
                        verifier(store),
                    ).track()
            viaJump.exec("echo via-jump").stdout shouldBe "via-jump\n"
            store.hosts.size shouldBe 2 // both hops verified
        }

    @Test
    fun `server shutdown is reported as disconnected`() =
        runBlocking<Unit> {
            val srv = server()
            val conn = engine.connect(srv.target(TestSshServer.password()), verifier()).track()
            srv.close()
            withTimeout(10.seconds) { conn.state.first { it == ConnectionState.DISCONNECTED } }
            shouldThrow<ConnectionFailedException> { conn.exec("true") }
            shouldThrow<ConnectionFailedException> { conn.openShell(PtySpec()) }
            shouldThrow<ConnectionFailedException> { conn.openDirectTcpip("127.0.0.1", 1) }
            conn.close()
        }

    @Test
    fun `keepalive can be disabled`() =
        runBlocking<Unit> {
            val srv = server()
            engine
                .connect(srv.target(TestSshServer.password()), verifier(), ConnectOptions(keepAlive = Duration.ZERO))
                .use { it.exec("true").isSuccess shouldBe true }
        }

    private fun readUntilEof(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8)
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(5.seconds) {
            while (!condition()) kotlinx.coroutines.delay(20)
        }
    }
}
