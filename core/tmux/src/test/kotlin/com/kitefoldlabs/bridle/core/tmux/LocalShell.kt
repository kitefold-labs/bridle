package com.kitefoldlabs.bridle.core.tmux

import com.kitefoldlabs.bridle.core.ssh.ConnectionState
import com.kitefoldlabs.bridle.core.ssh.DuplexStream
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.ssh.ShellChannel
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.time.Duration

/**
 * An [SshConnection] that runs scripts with the local `/bin/sh`, in an isolated HOME and tmux
 * socket directory. Lets tests drive a *real* tmux server exactly as the app would over SSH.
 */
class LocalShell(
    val home: Path,
    private val extraPath: Path? = null,
) : SshConnection,
    AutoCloseable {
    private val tmuxDir: Path = Files.createDirectories(home.resolve(".tmux-sockets"))
    var scripts = mutableListOf<String>()

    override val state = MutableStateFlow(ConnectionState.CONNECTED)

    override suspend fun exec(
        command: String,
        timeout: Duration,
    ): ExecResult = start(listOf("/bin/sh", "-c", command), null, timeout)

    override suspend fun runScript(
        script: String,
        timeout: Duration,
    ): ExecResult {
        scripts += script
        return start(listOf("/bin/sh", "-s"), script, timeout)
    }

    private fun start(
        command: List<String>,
        stdin: String?,
        timeout: Duration,
    ): ExecResult {
        val pb = ProcessBuilder(command)
        val env = pb.environment()
        env["HOME"] = home.toString()
        env["TMUX_TMPDIR"] = tmuxDir.toString()
        env.remove("TMUX")
        env["SHELL"] = "/bin/sh"
        env["PATH"] = listOfNotNull(extraPath?.toString(), "/usr/bin", "/bin").joinToString(":")
        val p = pb.start()
        p.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) { "timed out" }
        return ExecResult(p.exitValue(), out, err)
    }

    override suspend fun openShell(
        pty: PtySpec,
        command: String?,
    ): ShellChannel = throw UnsupportedOperationException()

    override suspend fun openDirectTcpip(
        host: String,
        port: Int,
    ): DuplexStream = throw UnsupportedOperationException()

    override fun close() {
        val pb = ProcessBuilder("tmux", "kill-server")
        pb.environment()["TMUX_TMPDIR"] = tmuxDir.toString()
        pb.environment().remove("TMUX")
        pb.redirectErrorStream(true).start().waitFor()
    }

    companion object {
        /** A stand-in `claude` that echoes its arguments and waits for input like the real TUI. */
        fun fakeClaude(bin: Path): Path {
            Files.createDirectories(bin)
            val script = bin.resolve("claude")
            script.writeText(
                "#!/bin/sh\necho \"fake-claude started args=[\$*] model=[\${ANTHROPIC_MODEL:-}] pwd=\$(pwd)\"\n" +
                    "while read -r line; do echo \"got:\$line\"; done\n",
            )
            script.toFile().setExecutable(true)
            return bin
        }
    }
}
