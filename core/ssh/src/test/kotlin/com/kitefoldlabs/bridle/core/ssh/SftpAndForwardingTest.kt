package com.kitefoldlabs.bridle.core.ssh

import app.cash.turbine.test
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class SftpAndForwardingTest {
    @TempDir
    lateinit var home: Path

    private lateinit var server: TestSshServer
    private lateinit var conn: SshConnection

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            server = TestSshServer(home)
            conn =
                SshjEngine(Dispatchers.IO).connect(
                    server.target(TestSshServer.password()),
                    TofuHostKeyVerifier(MemoryKnownHosts(), AcceptingPrompter()),
                )
        }

    @AfterEach
    fun tearDown() {
        conn.close()
        server.close()
    }

    @Test
    fun `browse, create, rename, chmod and delete`() =
        runBlocking<Unit> {
            Files.createDirectories(home.resolve("src/app"))
            home.resolve("src/app/main.kt").writeText("fun main() {}")
            home.resolve(".hidden").writeText("x")
            conn.openSftp().use { sftp ->
                val root = sftp.canonicalize("")
                root shouldBe "/"
                sftp.list("/").map { it.name } shouldContainExactlyInAnyOrder listOf("src", ".hidden")
                sftp.list("/").first { it.name == ".hidden" }.isHidden shouldBe true
                val file = sftp.list("/src/app").single()
                file.type shouldBe RemoteFile.Type.FILE
                file.size shouldBe 13
                file.modifiedEpochSeconds shouldNotBe 0L

                sftp.mkdir("/src/new dir")
                sftp.stat("/src/new dir")!!.isDirectory shouldBe true
                sftp.stat("/missing") shouldBe null
                sftp.rename("/src/app/main.kt", "/src/app/App.kt")
                sftp.chmod("/src/app/App.kt", 0b110_100_000)
                (sftp.stat("/src/app/App.kt")!!.permissions and 0b111_111_111) shouldBe 0b110_100_000
                shouldThrow<IllegalArgumentException> { sftp.chmod("/src/app/App.kt", 0x10000) }

                sftp.delete("/src/app/App.kt")
                sftp.delete("/nothing-here") // no-op
                shouldThrow<IOException> { sftp.delete("/src") } // not empty
                sftp.delete("/src", recursive = true)
                sftp.stat("/src") shouldBe null
            }
        }

    @Test
    fun `recursive delete removes symlinks without following them`() =
        runBlocking<Unit> {
            Files.createDirectories(home.resolve("victim"))
            home.resolve("victim/keep.txt").writeText("precious")
            Files.createDirectories(home.resolve("tree/sub"))
            home.resolve("tree/sub/a.txt").writeText("a")
            Files.createSymbolicLink(home.resolve("tree/dirlink"), Path.of("../victim"))
            Files.createSymbolicLink(home.resolve("tree/sub/filelink"), Path.of("../../victim/keep.txt"))
            Files.createSymbolicLink(home.resolve("toplink"), Path.of("victim"))
            conn.openSftp().use { sftp ->
                sftp.delete("/tree", recursive = true)
                sftp.delete("/toplink", recursive = true)
            }
            Files.exists(home.resolve("tree"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
            Files.exists(home.resolve("toplink"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
            home.resolve("victim/keep.txt").readText() shouldBe "precious"
        }

    @Test
    fun `overwriting keeps the target's permissions`() =
        runBlocking<Unit> {
            val secret = home.resolve("secret.env").also { it.writeText("TOKEN=1") }
            Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"))
            val script = home.resolve("run.sh").also { it.writeText("#!/bin/sh") }
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"))
            conn.openSftp().use { sftp ->
                val (_, opened) = sftp.readText("/secret.env")
                sftp.writeText("/secret.env", "TOKEN=2", opened)
                sftp.upload("echo hi".byteInputStream(), "/run.sh", overwrite = true)
            }
            secret.readText() shouldBe "TOKEN=2"
            PosixFilePermissions.toString(Files.getPosixFilePermissions(secret)) shouldBe "rw-------"
            PosixFilePermissions.toString(Files.getPosixFilePermissions(script)) shouldBe "rwxr-x---"
        }

    @Test
    fun `a cancelled upload leaves no temp file behind`() =
        runBlocking<Unit> {
            val reading = CompletableDeferred<Unit>()
            val endless =
                object : InputStream() {
                    override fun read(): Int = 0

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        reading.complete(Unit)
                        Thread.sleep(1)
                        return len
                    }
                }
            conn.openSftp().use { sftp ->
                val job = launch(Dispatchers.IO) { sftp.upload(endless, "/never.bin") }
                reading.await()
                job.cancelAndJoin()
            }
            Files.list(home).use { files -> files.map { it.fileName.toString() }.toList() } shouldBe emptyList()
        }

    @Test
    fun `transfers are atomic, report progress and can overwrite`() =
        runBlocking<Unit> {
            val data = Random(7).nextBytes(1_500_000)
            conn.openSftp().use { sftp ->
                val updates = mutableListOf<Pair<Long, Long?>>()
                sftp.upload(data.inputStream(), "/blob.bin", size = data.size.toLong()) { t, total ->
                    updates +=
                        t to total
                }
                updates.last() shouldBe (data.size.toLong() to data.size.toLong())
                (updates.size > 2) shouldBe true
                Files.list(home).use { files ->
                    files.filter { it.toString().endsWith(".bridle-part") }.count()
                } shouldBe
                    0L

                shouldThrow<IOException> { sftp.upload(byteArrayOf(1).inputStream(), "/blob.bin") }
                sftp.upload(byteArrayOf(1, 2, 3).inputStream(), "/blob.bin", overwrite = true) shouldBe 3

                val failing =
                    object : InputStream() {
                        var n = 0

                        override fun read(): Int = if (n++ < 10) 1 else throw IOException("phone storage vanished")
                    }
                shouldThrow<IOException> { sftp.upload(failing, "/partial.bin") }
                Files.exists(home.resolve("partial.bin")) shouldBe false
                Files.list(home).use { files ->
                    files.filter { it.toString().endsWith(".bridle-part") }.count()
                } shouldBe
                    0L

                sftp.upload(data.inputStream(), "/big.bin")
                val out = ByteArrayOutputStream()
                sftp.download("/big.bin", out) shouldBe data.size.toLong()
                out.toByteArray().contentEquals(data) shouldBe true
            }
        }

    @Test
    fun `downloads are cancellable`() =
        runBlocking<Unit> {
            home.resolve("big.bin").toFile().writeBytes(ByteArray(4_000_000))
            conn.openSftp().use { sftp ->
                val job =
                    async {
                        sftp.download("/big.bin", ByteArrayOutputStream()) { t, _ ->
                            if (t >
                                500_000
                            ) {
                                throw CancellationException("stop")
                            }
                        }
                    }
                shouldThrow<CancellationException> { job.await() }
            }
        }

    @Test
    fun `editor round trip with conflict detection and size limit`() =
        runBlocking<Unit> {
            home.resolve("notes.md").writeText("# Notes\n")
            conn.openSftp().use { sftp ->
                val (text, opened) = sftp.readText("/notes.md")
                text shouldBe "# Notes\n"
                val saved = sftp.writeText("/notes.md", "# Notes\n- item\n", opened)
                home.resolve("notes.md").readText() shouldBe "# Notes\n- item\n"
                // Someone else edits the file on the host…
                home.resolve("notes.md").writeText("changed elsewhere, longer content")
                shouldThrow<RemoteFileChangedException> { sftp.writeText("/notes.md", "mine", saved) }
                shouldThrow<RemoteFileChangedException> {
                    sftp.writeText("/gone.md", "x", saved.copy(path = "/gone.md"))
                }
                sftp.writeText("/new.md", "fresh", opened = null).size shouldBe 5
                shouldThrow<RemoteFileTooLargeException> { sftp.readText("/notes.md", limit = 4) }.size shouldBe 33
                shouldThrow<IOException> { sftp.readText("/nope.md") }
                // Not a regular file (like a device or FIFO, whose size of 0 says nothing).
                Files.createDirectories(home.resolve("folder"))
                shouldThrow<IOException> { sftp.readText("/folder") }.message shouldBe "/folder is not a regular file"
            }
        }

    @Test
    fun `path helpers`() {
        Sftp.join("/home/me", "a.txt") shouldBe "/home/me/a.txt"
        Sftp.join("/", "a") shouldBe "/a"
        shouldThrow<IllegalArgumentException> { Sftp.join("/", "a/b") }
        shouldThrow<IllegalArgumentException> { Sftp.join("/", "..") }
        shouldThrow<IllegalArgumentException> { Sftp.join("/", "") }
        shouldThrow<IllegalArgumentException> { Sftp.join("/", ".") }
        Sftp.parent("/home/me/a.txt") shouldBe "/home/me"
        Sftp.parent("/home/") shouldBe "/"
        Sftp.parent("/") shouldBe "/"
    }

    @Test
    fun `default interface method reports missing sftp`() =
        runBlocking<Unit> {
            val bare =
                object : SshConnection by conn {
                    override suspend fun openSftp(): SftpSession = super<SshConnection>.openSftp()
                }
            shouldThrow<UnsupportedOperationException> { bare.openSftp() }
        }

    @Test
    fun `local forward reaches a service behind the server`() =
        runBlocking<Unit> {
            val service = echoServer()
            PortForwarder(conn).local(0, "127.0.0.1", service.localPort).use { fwd ->
                fwd.activeConnections.test {
                    awaitItem() shouldBe 0
                    Socket(InetAddress.getLoopbackAddress(), fwd.localPort).use { s ->
                        s.getOutputStream().write("ping\n".toByteArray())
                        s.getInputStream().bufferedReader().readLine() shouldBe "ping"
                        awaitItem() shouldBe 1
                    }
                    withTimeout(5.seconds) { awaitItem() shouldBe 0 }
                }
            }
            shouldThrow<IllegalArgumentException> { PortForwarder(conn).local(0, "h", 0) }
            shouldThrow<IllegalArgumentException> { PortForwarder(conn).local(70_000, "h", 1) }
            service.close()
        }

    @Test
    fun `socks5 proxy supports domain, ipv4 and rejects the rest`() =
        runBlocking<Unit> {
            val service = echoServer()
            PortForwarder(conn).dynamic(0).use { socks ->
                // Domain name request.
                val domain = byteArrayOf(3, 9) + "localhost".toByteArray()
                socksConnect(socks.localPort, domain, service.localPort).use { s ->
                    s.getOutputStream().write("hi\n".toByteArray())
                    s.getInputStream().bufferedReader().readLine() shouldBe "hi"
                }
                // IPv4 request.
                socksConnect(socks.localPort, byteArrayOf(1, 127, 0, 0, 1), service.localPort).use { s ->
                    s.getOutputStream().write("v4\n".toByteArray())
                    s.getInputStream().bufferedReader().readLine() shouldBe "v4"
                }
                // Unreachable destination → host unreachable reply.
                val closed = ServerSocket(0).use { it.localPort }
                socksReply(socks.localPort, byteArrayOf(1, 127, 0, 0, 1), closed, command = 1) shouldBe 4
                // BIND command → not supported; unknown address type → not supported.
                socksReply(socks.localPort, byteArrayOf(1, 127, 0, 0, 1), 80, command = 2) shouldBe 7
                socksReply(socks.localPort, byteArrayOf(9), 80, command = 1) shouldBe 8
                // Only username/password auth offered → no acceptable methods.
                Socket(InetAddress.getLoopbackAddress(), socks.localPort).use { s ->
                    s.getOutputStream().write(byteArrayOf(5, 1, 2))
                    DataInputStream(s.getInputStream()).run {
                        readUnsignedByte() shouldBe 5
                        readUnsignedByte() shouldBe
                            0xFF
                    }
                }
                // Wrong protocol version → connection simply closed.
                Socket(InetAddress.getLoopbackAddress(), socks.localPort).use { s ->
                    s.getOutputStream().write(byteArrayOf(4, 1, 0))
                    s.getInputStream().read() shouldBe -1
                }
                Socket(InetAddress.getLoopbackAddress(), socks.localPort).use { s ->
                    s.getOutputStream().write(byteArrayOf(5, 1, 0, 4, 1, 0, 1))
                    s.getInputStream().read() shouldBe 5
                }
            }
            service.close()
        }

    @Test
    fun `forwards cap connections, time out silent socks clients and close without blocking`() =
        runBlocking<Unit> {
            PortForwarder(conn, maxConnections = 1, handshakeTimeoutMillis = 300).dynamic(0).use { socks ->
                Socket(InetAddress.getLoopbackAddress(), socks.localPort).use { idle ->
                    withTimeout(5.seconds) { socks.activeConnections.first { it == 1 } }
                    // Over the cap: closed straight away, the first one is unaffected.
                    Socket(InetAddress.getLoopbackAddress(), socks.localPort).use { extra ->
                        extra.soTimeout = 5_000
                        extra.getInputStream().read() shouldBe -1
                    }
                    // The idle client never sends a greeting: dropped after the handshake timeout.
                    idle.soTimeout = 5_000
                    idle.getInputStream().read() shouldBe -1
                    withTimeout(5.seconds) { socks.activeConnections.first { it == 0 } }
                }
            }
            val service = echoServer()
            val fwd = PortForwarder(conn).local(0, "127.0.0.1", service.localPort)
            val s = Socket(InetAddress.getLoopbackAddress(), fwd.localPort)
            s.getOutputStream().write("x\n".toByteArray())
            s.getInputStream().bufferedReader().readLine() shouldBe "x"
            withTimeout(5.seconds) { fwd.close() } // drops the open connection too
            s.soTimeout = 5_000
            s.getInputStream().read() shouldBe -1
            s.close()
            service.close()
        }

    @Test
    fun `an unexpected error in one connection neither crashes nor stops the forward`() =
        runBlocking<Unit> {
            val crashes = mutableListOf<Throwable>()
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(crashes) { crashes += e } }
            try {
                val broken = mockk<SshConnection>()
                coEvery { broken.openDirectTcpip(any(), any()) } throws IllegalStateException("sshj bug")
                PortForwarder(broken).local(0, "h", 1).use { fwd ->
                    repeat(2) {
                        Socket(InetAddress.getLoopbackAddress(), fwd.localPort).use { s ->
                            s.soTimeout = 5_000
                            s.getInputStream().read() shouldBe -1
                        }
                    }
                }
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previous)
            }
            synchronized(crashes) { crashes shouldBe emptyList() }
        }

    @Test
    fun `socks5 handles ipv6 addresses`() {
        val bytes = byteArrayOf(5, 1, 0, 5, 1, 0, 4) + ByteArray(15) + byteArrayOf(1) + byteArrayOf(0, 80)
        val request = Socks5.handshake(bytes.inputStream(), ByteArrayOutputStream())!!
        request.host shouldBe "0:0:0:0:0:0:0:1"
        request.port shouldBe 80
    }

    private fun echoServer(): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s =
                    try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                thread(isDaemon = true) { s.use { it.getInputStream().copyTo(it.getOutputStream()) } }
            }
        }
        return server
    }

    private fun socksConnect(
        proxyPort: Int,
        address: ByteArray,
        port: Int,
    ): Socket {
        val s = Socket(InetAddress.getLoopbackAddress(), proxyPort)
        s.getOutputStream().write(byteArrayOf(5, 1, 0))
        val input = DataInputStream(s.getInputStream())
        input.readUnsignedByte() shouldBe 5
        input.readUnsignedByte() shouldBe 0
        s.getOutputStream().write(byteArrayOf(5, 1, 0) + address + byteArrayOf((port shr 8).toByte(), port.toByte()))
        val reply = ByteArray(10).also { input.readFully(it) }
        reply[1].toInt() shouldBe 0
        return s
    }

    private fun socksReply(
        proxyPort: Int,
        address: ByteArray,
        port: Int,
        command: Int,
    ): Int =
        Socket(InetAddress.getLoopbackAddress(), proxyPort).use { s ->
            s.getOutputStream().write(byteArrayOf(5, 1, 0))
            val input = DataInputStream(s.getInputStream())
            input.readUnsignedByte()
            input.readUnsignedByte()
            s.getOutputStream().write(
                byteArrayOf(5, command.toByte(), 0) + address + byteArrayOf((port shr 8).toByte(), port.toByte()),
            )
            input.readUnsignedByte() shouldBe 5
            input.readUnsignedByte()
        }
}
