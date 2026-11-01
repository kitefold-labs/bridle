package com.kitefoldlabs.bridle.core.ssh

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** A running port forward. Listens on loopback only, so other devices on the network can't use it. */
public interface PortForwardHandle : AutoCloseable {
    /** The local port actually bound (useful when 0 was requested). */
    public val localPort: Int

    /** Number of currently open forwarded connections. */
    public val activeConnections: StateFlow<Int>
}

/**
 * Starts local (`-L`) and dynamic SOCKS5 (`-D`) forwards over an [SshConnection].
 *
 * Every forwarded connection blocks two threads (one per direction), so a forward serves at most
 * [maxConnections] at a time and closes any beyond that, and the default dispatcher is a view of
 * [Dispatchers.IO] with its own thread budget: a flood of local connections can't starve the rest
 * of the app's I/O. A SOCKS client must finish its handshake within [handshakeTimeoutMillis].
 */
public class PortForwarder(
    private val connection: SshConnection,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2 * DEFAULT_MAX_CONNECTIONS + 1),
    private val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    private val handshakeTimeoutMillis: Int = HANDSHAKE_TIMEOUT_MS,
) {
    /** Forwards `127.0.0.1:[bindPort]` to [destHost]:[destPort] as seen from the server. */
    public fun local(
        bindPort: Int,
        destHost: String,
        destPort: Int,
    ): PortForwardHandle {
        require(destPort in 1..MAX_PORT) { "Invalid destination port" }
        return Listener(bind(bindPort), io, maxConnections) { client ->
            pump(client, connection.openDirectTcpip(destHost, destPort))
        }
    }

    /**
     * A SOCKS5 proxy on `127.0.0.1:[bindPort]` (no authentication, CONNECT only; RFC 1928).
     * Point the phone's browser or another app at it to reach anything the server can reach.
     */
    public fun dynamic(bindPort: Int): PortForwardHandle =
        Listener(bind(bindPort), io, maxConnections) { client ->
            // A client that connects and then says nothing would otherwise hold a slot forever.
            client.soTimeout = handshakeTimeoutMillis
            val request = Socks5.handshake(client.getInputStream(), client.getOutputStream())
            if (request != null) {
                val remote =
                    try {
                        connection.openDirectTcpip(request.host, request.port)
                    } catch (e: ConnectionFailedException) {
                        Socks5.reply(client.getOutputStream(), Socks5.HOST_UNREACHABLE)
                        throw e
                    }
                Socks5.reply(client.getOutputStream(), Socks5.SUCCEEDED)
                client.soTimeout = 0
                pump(client, remote)
            }
        }

    private fun bind(port: Int): ServerSocket {
        require(port in 0..MAX_PORT) { "Invalid local port" }
        return ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        }
    }

    /** Copies both directions until either side closes, then closes both. */
    private suspend fun pump(
        client: Socket,
        remote: DuplexStream,
    ) {
        val scope = CoroutineScope(io + ignoreErrors)
        val up = scope.launch { copyQuietly(client.getInputStream(), remote.output) }
        val down = scope.launch { copyQuietly(remote.input, client.getOutputStream()) }
        try {
            // When one direction ends, tear down both so the other unblocks.
            kotlinx.coroutines.selects.select {
                up.onJoin {}
                down.onJoin {}
            }
        } finally {
            closeQuietly(client)
            closeQuietly(remote)
            up.join()
            down.join()
        }
    }

    private class Listener(
        private val server: ServerSocket,
        io: CoroutineDispatcher,
        private val maxConnections: Int,
        private val handle: suspend (Socket) -> Unit,
    ) : PortForwardHandle {
        // A failure in one forwarded connection must neither stop the forward nor reach the
        // default handler, which would crash an Android app.
        private val scope = CoroutineScope(SupervisorJob() + io + ignoreErrors)
        private val count = AtomicInteger()
        private val active = MutableStateFlow(0)
        override val localPort: Int = server.localPort
        override val activeConnections: StateFlow<Int> = active.asStateFlow()

        init {
            scope.launch {
                while (true) {
                    val client =
                        try {
                            server.accept()
                        } catch (_: IOException) {
                            break // closed
                        }
                    if (count.incrementAndGet() > maxConnections) {
                        count.decrementAndGet()
                        closeQuietly(client)
                    } else {
                        active.value = count.get()
                        scope.launch { serve(client) }
                    }
                }
            }
        }

        private suspend fun serve(client: Socket) {
            try {
                client.tcpNoDelay = true
                handle(client)
            } catch (_: IOException) {
                // A single failed connection doesn't stop the forward.
            } catch (_: ConnectionFailedException) {
                // Destination unreachable from the server.
            } finally {
                closeQuietly(client)
                active.value = count.decrementAndGet()
            }
        }

        /** Stops accepting and drops every forwarded connection. Never blocks. */
        override fun close() {
            closeQuietly(server)
            scope.cancel()
        }
    }

    public companion object {
        /** Concurrent connections per forward; a browser needs a few dozen at most. */
        public const val DEFAULT_MAX_CONNECTIONS: Int = 64

        /** How long a SOCKS client may take to send its greeting and request, in milliseconds. */
        public const val HANDSHAKE_TIMEOUT_MS: Int = 10_000
        private const val MAX_PORT = 65_535

        /** Errors in one forwarded connection are that connection's end, never an app crash. */
        private val ignoreErrors = CoroutineExceptionHandler { _, _ -> }
    }
}

private fun copyQuietly(
    input: InputStream,
    output: OutputStream,
) {
    val buffer = ByteArray(COPY_BUFFER)
    try {
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            output.flush()
        }
    } catch (_: IOException) {
        // The other side went away; the pump closes both ends.
    }
}

private const val COPY_BUFFER = 16 * 1024

/** Minimal SOCKS5 server side (RFC 1928): no-auth method, CONNECT, IPv4/IPv6/domain addresses. */
internal object Socks5 {
    const val VERSION = 5
    const val NO_AUTH = 0
    const val NO_ACCEPTABLE = 0xFF
    const val CONNECT = 1
    const val ATYP_IPV4 = 1
    const val ATYP_DOMAIN = 3
    const val ATYP_IPV6 = 4
    const val SUCCEEDED = 0
    const val HOST_UNREACHABLE = 4
    const val COMMAND_NOT_SUPPORTED = 7
    const val ADDRESS_NOT_SUPPORTED = 8
    private const val IPV4_LEN = 4
    private const val IPV6_LEN = 16
    private const val BYTE = 0xFF

    data class Request(
        val host: String,
        val port: Int,
    )

    /** Negotiates and reads the CONNECT request; replies with an error and returns null if unsupported. */
    fun handshake(
        input: InputStream,
        output: OutputStream,
    ): Request? {
        val data = DataInputStream(input)
        return if (negotiate(data, output)) readRequest(data, output) else null
    }

    /** Method negotiation: we only offer "no authentication" (the listener is loopback-only). */
    private fun negotiate(
        data: DataInputStream,
        output: OutputStream,
    ): Boolean {
        if (data.readUnsignedByte() != VERSION) return false
        val methods = ByteArray(data.readUnsignedByte()).also { data.readFully(it) }
        val acceptable = methods.any { it.toInt() == NO_AUTH }
        output.write(byteArrayOf(VERSION.toByte(), (if (acceptable) NO_AUTH else NO_ACCEPTABLE).toByte()))
        return acceptable
    }

    private fun readRequest(
        data: DataInputStream,
        output: OutputStream,
    ): Request? {
        if (data.readUnsignedByte() != VERSION) return null
        val command = data.readUnsignedByte()
        data.readUnsignedByte() // reserved
        val host = readAddress(data)
        val port = data.readUnsignedShort()
        return when {
            host == null -> null.also { reply(output, ADDRESS_NOT_SUPPORTED) }
            command != CONNECT -> null.also { reply(output, COMMAND_NOT_SUPPORTED) }
            else -> Request(host, port)
        }
    }

    private fun readAddress(data: DataInputStream): String? =
        when (data.readUnsignedByte()) {
            ATYP_IPV4 -> InetAddress.getByAddress(ByteArray(IPV4_LEN).also { data.readFully(it) }).hostAddress
            ATYP_IPV6 -> InetAddress.getByAddress(ByteArray(IPV6_LEN).also { data.readFully(it) }).hostAddress
            ATYP_DOMAIN -> String(ByteArray(data.readUnsignedByte()).also { data.readFully(it) }, Charsets.US_ASCII)
            else -> null
        }

    fun reply(
        output: OutputStream,
        status: Int,
    ) {
        // Bound address is reported as 0.0.0.0:0; clients don't need it for CONNECT.
        output.write(byteArrayOf(VERSION.toByte(), (status and BYTE).toByte(), 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        output.flush()
    }
}
