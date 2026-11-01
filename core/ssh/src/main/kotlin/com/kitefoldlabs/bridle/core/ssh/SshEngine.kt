package com.kitefoldlabs.bridle.core.ssh

import com.kitefoldlabs.bridle.core.model.Host
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where to connect. [jump] is the ProxyJump hop (itself possibly jumping further). */
public data class ConnectTarget(
    val hostname: String,
    val port: Int = Host.DEFAULT_SSH_PORT,
    val username: String,
    val credentials: List<Credential>,
    val jump: ConnectTarget? = null,
) {
    init {
        require(credentials.isNotEmpty()) { "At least one credential is required" }
    }

    override fun toString(): String = "ConnectTarget($username@$hostname:$port, jump=${jump != null})"
}

/** Ways to authenticate. Secrets are supplied lazily and never stored by this module. */
public sealed interface Credential {
    public data class Key(
        val signer: SshKeySigner,
    ) : Credential {
        override fun toString(): String = "Credential.Key(${OpenSshKeys.typeName(signer.publicKey)})"
    }

    /** The password is requested only when the server asks for it; the array is wiped after use. */
    public class Password(
        public val supply: () -> CharArray,
    ) : Credential {
        override fun toString(): String = "Credential.Password(****)"
    }
}

public data class ConnectOptions(
    val connectTimeout: Duration = 15.seconds,
    val socketTimeout: Duration = Duration.ZERO,
    /** `keepalive@openssh.com` interval; [Duration.ZERO] disables keepalives. */
    val keepAlive: Duration = 30.seconds,
)

public enum class ConnectionState { CONNECTED, DISCONNECTED }

public data class PtySpec(
    val columns: Int = 80,
    val rows: Int = 24,
    val term: String = "xterm-256color",
) {
    init {
        require(columns in 1..MAX_DIMENSION && rows in 1..MAX_DIMENSION) { "Invalid terminal size ${columns}x$rows" }
    }

    public companion object {
        public const val MAX_DIMENSION: Int = 4_096
    }
}

public data class ExecResult(
    val exitStatus: Int?,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitStatus == 0
}

/** An authenticated SSH connection; channels are multiplexed over it. */
public interface SshConnection : Closeable {
    public val state: StateFlow<ConnectionState>

    /** Runs [command] (a single command line, already quoted) and collects its output. */
    public suspend fun exec(
        command: String,
        timeout: Duration = DEFAULT_EXEC_TIMEOUT,
    ): ExecResult

    /**
     * Runs a POSIX `sh` [script] fed through stdin (`sh -s`). Independent of the user's login
     * shell (bash, zsh or fish), so scripts behave the same everywhere.
     */
    public suspend fun runScript(
        script: String,
        timeout: Duration = DEFAULT_EXEC_TIMEOUT,
    ): ExecResult

    /** Opens an interactive shell with a PTY. If [command] is set, it runs instead of the login shell. */
    public suspend fun openShell(
        pty: PtySpec,
        command: String? = null,
    ): ShellChannel

    /** Opens a `direct-tcpip` stream to [host]:[port] as seen from the server. */
    public suspend fun openDirectTcpip(
        host: String,
        port: Int,
    ): DuplexStream

    public companion object {
        public val DEFAULT_EXEC_TIMEOUT: Duration = 30.seconds
    }
}

public interface DuplexStream : Closeable {
    public val input: InputStream
    public val output: OutputStream
}

/** An interactive shell. Bytes from the server arrive on [input]; keystrokes go to [output]. */
public interface ShellChannel : DuplexStream {
    public val isOpen: Boolean

    public suspend fun resize(
        columns: Int,
        rows: Int,
    )

    /** Exit status once the remote side has closed, else null. */
    public val exitStatus: Int?
}

public interface SshEngine {
    public suspend fun connect(
        target: ConnectTarget,
        hostKeys: HostKeyVerifier,
        options: ConnectOptions = ConnectOptions(),
    ): SshConnection
}

/** Raised when every offered credential was rejected. */
public class AuthenticationFailedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Raised for network-level failures (DNS, refused, timeout, dropped). */
public class ConnectionFailedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
