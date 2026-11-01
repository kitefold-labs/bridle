package com.kitefoldlabs.bridle.core.ssh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration

class AuthorizedKeysTest {
    @TempDir
    lateinit var home: Path

    private val line = OpenSshKeys.authorizedKeyLine(TestKeys.ed25519().public, "bridle@phone")

    @Test
    fun `script creates file with strict permissions and is idempotent`() {
        runLocally(AuthorizedKeys.installScript(line)) shouldContain AuthorizedKeys.ADDED_MARKER
        runLocally(AuthorizedKeys.installScript(line)) shouldContain AuthorizedKeys.PRESENT_MARKER
        val file = home.resolve(".ssh/authorized_keys")
        file.readText() shouldBe "$line\n"
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parent)) shouldBe "rwx------"
    }

    @Test
    fun `same key with other comment or options counts as present`() {
        val (type, data) = line.split(" ")
        Files.createDirectories(home.resolve(".ssh"))
        home.resolve(".ssh/authorized_keys").writeText("from=\"10.0.0.0/8\" $type $data laptop")
        runLocally(AuthorizedKeys.installScript(line)) shouldContain AuthorizedKeys.PRESENT_MARKER
    }

    @Test
    fun `missing trailing newline is repaired before appending`() {
        Files.createDirectories(home.resolve(".ssh"))
        val other = OpenSshKeys.authorizedKeyLine(TestKeys.ec().public, "other")
        home.resolve(".ssh/authorized_keys").writeText(other)
        runLocally(AuthorizedKeys.installScript(line)) shouldContain AuthorizedKeys.ADDED_MARKER
        home.resolve(".ssh/authorized_keys").readText() shouldBe "$other\n$line\n"
    }

    @Test
    fun `only well formed single keys are accepted`() {
        shouldThrow<IllegalArgumentException> { AuthorizedKeys.installScript("$line\n$line") }
        shouldThrow<IllegalArgumentException> { AuthorizedKeys.installScript("ssh-ed25519 AAAA; rm -rf ~") }
    }

    @Test
    fun `install maps results`() =
        runTest {
            AuthorizedKeys.install(fake(ExecResult(0, "BRIDLE_KEY_ADDED\n", "")), line) shouldBe KeyInstallResult.ADDED
            AuthorizedKeys.install(fake(ExecResult(0, "BRIDLE_KEY_PRESENT\n", "")), line) shouldBe
                KeyInstallResult.ALREADY_PRESENT
            shouldThrow<RemoteCommandException> { AuthorizedKeys.install(fake(ExecResult(1, "", "denied")), line) }
                .message shouldContain "denied"
            shouldThrow<RemoteCommandException> { AuthorizedKeys.install(fake(ExecResult(0, "??", "")), line) }
        }

    private fun runLocally(script: String): String {
        val pb = ProcessBuilder("/bin/sh", "-s").redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        val p = pb.start()
        p.outputStream.use { it.write(script.toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor() shouldBe 0
        return out
    }

    private fun fake(result: ExecResult) =
        object : SshConnection {
            override val state = MutableStateFlow(ConnectionState.CONNECTED)

            override suspend fun exec(
                command: String,
                timeout: Duration,
            ) = result

            override suspend fun runScript(
                script: String,
                timeout: Duration,
            ) = result

            override suspend fun openShell(
                pty: PtySpec,
                command: String?,
            ) = throw UnsupportedOperationException()

            override suspend fun openDirectTcpip(
                host: String,
                port: Int,
            ) = throw UnsupportedOperationException()

            override fun close() = Unit
        }
}
