package com.kitefoldlabs.bridle.core.session

import com.kitefoldlabs.bridle.core.ssh.ConnectionState
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.ssh.SftpSession
import com.kitefoldlabs.bridle.core.ssh.ShellChannel
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.core.tmux.TmuxScripts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Receives terminal output. Implemented by the emulator adapter; called on an I/O thread. */
public fun interface TerminalSink {
    /** [buffer] is reused after the call returns; copy what you keep. */
    public fun onOutput(
        buffer: ByteArray,
        length: Int,
    )
}

/** What a tab attaches to. */
public sealed interface AttachSpec {
    /** A plain login shell. Lost on disconnect (the UI offers to reopen it). */
    public data object Shell : AttachSpec

    /** A tmux session (created if missing). Reattached automatically after reconnecting. */
    public data class Tmux(
        val session: String,
        val workingDir: String = "~",
    ) : AttachSpec
}

public sealed interface TerminalState {
    public data object Connecting : TerminalState

    public data object Open : TerminalState

    /** The connection dropped; a tmux tab will reattach when the host reconnects. */
    public data object Reattaching : TerminalState

    /** The remote side ended (shell exited, tmux detached) or a plain shell lost its connection. */
    public data class Ended(
        val exitStatus: Int?,
        val reason: String,
    ) : TerminalState
}

/**
 * One terminal tab: an SSH shell channel wired to an emulator [sink].
 *
 * Output is pumped through a single reusable buffer straight into the sink (no per-chunk
 * allocation). Input is queued and written in order by one writer. The session lives in the
 * service scope, so rotating the phone or switching tabs never interrupts it.
 */
public class TerminalSession(
    private val lease: ConnectionLease,
    public val attach: AttachSpec,
    private val sink: TerminalSink,
    private val scope: CoroutineScope,
    initialSize: PtySpec = PtySpec(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** How long a failed channel open waits for the connection to report a drop before giving up. */
    private val openFailureGrace: Duration = 3.seconds,
) : AutoCloseable {
    private val mutableState = MutableStateFlow<TerminalState>(TerminalState.Connecting)
    public val state: StateFlow<TerminalState> = mutableState.asStateFlow()

    @Volatile private var size: PtySpec = initialSize

    @Volatile private var channel: ShellChannel? = null
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    private var job: Job? = null

    /** Built (and so validated) up front: a bad session name fails here, not inside [scope]. */
    private val command: String? =
        when (attach) {
            AttachSpec.Shell -> null
            is AttachSpec.Tmux -> TmuxScripts.attachCommand(attach.session, attach.workingDir)
        }

    /** Starts following the host connection; call once. */
    public fun start() {
        check(job == null) { "already started" }
        job =
            scope.launch {
                launch { writer() }
                lease.state.collectLatest { hostState ->
                    when (hostState) {
                        is HostConnectionState.Connected -> runShell(hostState.connection)
                        is HostConnectionState.Failed ->
                            end(null, hostState.error.message ?: "Connection failed")
                        // Only after ConnectionManager.closeAll(): nothing will reconnect.
                        HostConnectionState.Idle -> end(null, "Disconnected")
                        else -> if (mutableState.value == TerminalState.Open) onDropped()
                    }
                }
            }
    }

    /**
     * Sees every input [send] queues, on the caller's thread, before it is written. Everything
     * typed into the tab goes through [send] (keyboard, key bar, paste, compose box, snippets),
     * so this sees all of it.
     */
    @Volatile
    public var onInput: ((ByteArray) -> Unit)? = null

    /** Sends keystrokes/paste bytes to the remote side. Ignored when not open. */
    public fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        onInput?.invoke(bytes)
        input.trySend(bytes)
    }

    /**
     * Opens an SFTP session on the host's current connection. Fails at once, without waiting,
     * while the host is not connected.
     *
     * @throws IOException if the host is not connected.
     */
    public suspend fun openSftp(): SftpSession {
        val connected = lease.state.value as? HostConnectionState.Connected ?: throw IOException("Not connected")
        return connected.connection.openSftp()
    }

    /** Tells the server the terminal size changed (rotation, font zoom, split screen). */
    public suspend fun resize(
        columns: Int,
        rows: Int,
    ) {
        val spec = PtySpec(columns, rows, size.term)
        size = spec
        val current = channel ?: return
        try {
            current.resize(spec.columns, spec.rows)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: Exception,
        ) {
            // The channel is going away; the new one is opened with the latest size.
        }
    }

    override fun close() {
        job?.cancel()
        channel?.close()
        input.close()
        lease.close()
        if (mutableState.value !is TerminalState.Ended) mutableState.value = TerminalState.Ended(null, "Closed")
    }

    private suspend fun runShell(connection: SshConnection) {
        if (mutableState.value is TerminalState.Ended) return
        val shell =
            try {
                connection.openShell(size, command)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Usually the connection dropped while opening, and the manager will report that
                // (cancelling this wait). If it stays up, the server refused the channel (MaxSessions,
                // no PTY allowed…): say so instead of showing "connecting" forever.
                val disconnected = ConnectionState.DISCONNECTED
                val dropped = withTimeoutOrNull(openFailureGrace) { connection.state.first { it == disconnected } }
                if (dropped == null) end(null, "Could not open a terminal: ${e.message ?: e.javaClass.simpleName}")
                return
            }
        channel = shell
        mutableState.value = TerminalState.Open
        val clean =
            try {
                pump(shell)
            } finally {
                // Also when collectLatest cancels us for a new host state: never leak the channel.
                channel = null
                shell.close()
            }
        when {
            clean -> end(shell.exitStatus, "Session ended")
            attach is AttachSpec.Shell -> end(null, "Connection lost")
            else -> mutableState.value = TerminalState.Reattaching
        }
    }

    /** Reads until EOF. Returns true for a clean remote close, false if the stream broke. */
    private suspend fun pump(shell: ShellChannel): Boolean =
        withContext(io) {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                runInterruptible {
                    val stream = shell.input
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (n > 0) sink.onOutput(buffer, n)
                    }
                }
                true
            } catch (_: IOException) {
                false
            }
        }

    private fun onDropped() {
        channel?.close()
        channel = null
        if (attach is AttachSpec.Shell) end(null, "Connection lost") else mutableState.value = TerminalState.Reattaching
    }

    private fun end(
        exitStatus: Int?,
        reason: String,
    ) {
        mutableState.value = TerminalState.Ended(exitStatus, reason)
        job?.cancel()
    }

    private suspend fun writer() {
        for (bytes in input) {
            val target = channel ?: continue // typed while reconnecting: dropped, like a real terminal
            withContext(io) {
                try {
                    target.output.write(bytes)
                    target.output.flush()
                } catch (_: IOException) {
                    // Reported through the connection state.
                }
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 32 * 1024
    }
}
