package com.kitefoldlabs.bridle.core.session

import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.ssh.ConnectionState
import com.kitefoldlabs.bridle.core.ssh.DuplexStream
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.ssh.ShellChannel
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** A shell whose output the test feeds and whose input the test inspects. */
class FakeShell : ShellChannel {
    private val chunks = LinkedBlockingQueue<Any>()
    val written = ByteArrayOutputStream()
    val sizes = mutableListOf<Pair<Int, Int>>()

    @Volatile var open = true
    override var exitStatus: Int? = null
    var failResize = false

    fun emit(text: String) = chunks.put(text.toByteArray())

    fun eof(status: Int? = 0) {
        exitStatus = status
        chunks.put(EOF)
    }

    fun breakStream() = chunks.put(BROKEN)

    override val input: InputStream =
        object : InputStream() {
            private var pending: ByteArray = ByteArray(0)
            private var pos = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (pos >= pending.size) {
                    when (val next = chunks.poll(5, TimeUnit.SECONDS)) {
                        EOF, null -> return -1
                        BROKEN -> throw IOException("connection reset")
                        else -> {
                            pending = next as ByteArray
                            pos = 0
                        }
                    }
                }
                val n = minOf(len, pending.size - pos)
                System.arraycopy(pending, pos, b, off, n)
                pos += n
                return n
            }
        }

    override val output: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) {
                if (!open) throw IOException("closed")
                synchronized(written) { written.write(b) }
            }
        }

    override val isOpen: Boolean get() = open

    override suspend fun resize(
        columns: Int,
        rows: Int,
    ) {
        if (failResize) throw IOException("channel closing")
        sizes += columns to rows
    }

    override fun close() {
        open = false
        chunks.put(EOF)
    }

    fun writtenText(): String = synchronized(written) { written.toString(Charsets.UTF_8) }

    private companion object {
        val EOF = Any()
        val BROKEN = Any()
    }
}

class FakeConnection : SshConnection {
    override val state = MutableStateFlow(ConnectionState.CONNECTED)
    val shells = mutableListOf<FakeShell>()
    val commands = mutableListOf<String?>()
    var failOpen = false
    var closed = false
    val execs = mutableListOf<String>()

    /** What [exec] does: returns a result, throws, or (a dead link) never answers. */
    var onExec: suspend () -> ExecResult = { ExecResult(0, "", "") }

    override suspend fun exec(
        command: String,
        timeout: Duration,
    ): ExecResult {
        execs += command
        return onExec()
    }

    override suspend fun runScript(
        script: String,
        timeout: Duration,
    ) = ExecResult(0, "", "")

    override suspend fun openShell(
        pty: PtySpec,
        command: String?,
    ): ShellChannel {
        if (failOpen) throw IOException("channel open failed")
        commands += command
        return FakeShell().also { shells += it }
    }

    override suspend fun openDirectTcpip(
        host: String,
        port: Int,
    ): DuplexStream = throw UnsupportedOperationException()

    fun drop() {
        state.value = ConnectionState.DISCONNECTED
    }

    override fun close() {
        closed = true
        state.value = ConnectionState.DISCONNECTED
    }
}

/** Hands out scripted results per connect attempt. */
class ScriptedConnector(
    vararg results: Any,
) : HostConnector {
    private val queue = ArrayDeque(results.toList())
    val attempts = mutableListOf<EntityId>()
    val made = mutableListOf<FakeConnection>()

    override suspend fun connect(hostId: EntityId): SshConnection {
        attempts += hostId
        return when (val next = queue.removeFirstOrNull() ?: FakeConnection()) {
            is Throwable -> throw next
            is FakeConnection -> next.also { made += it }
            else -> error("bad script")
        }
    }
}
