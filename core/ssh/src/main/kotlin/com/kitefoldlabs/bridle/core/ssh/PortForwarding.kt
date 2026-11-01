package com.kitefoldlabs.bridle.core.ssh

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/** A running port forward. Listens on loopback only, so other devices on the network can't use it. */
public interface PortForwardHandle : AutoCloseable {
    /** The local port actually bound (useful when 0 was requested). */
    public val localPort: Int

    /** Number of currently open forwarded connections. */
    public val activeConnections: StateFlow<Int>
}

/** Starts local (`-L`) and dynamic SOCKS5 (`-D`) forwards over an [SshConnection]. */
public class PortForwarder(
    private val connection: SshConnection,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Forwards `127.0.0.1:[bindPort]` to [destHost]:[destPort] as seen from the server. */
    public fun local(
        bindPort: Int,
        destHost: String,
        destPort: Int,
    ): PortForwardHandle {
        require(destPort in 1..MAX_PORT) { "Invalid destination port" }
        return Listener(bind(bindPort), io) { client ->
            pump(client, connection.openDirectTcpip(destHost, destPort))
        }
    }

    /**
     * A SOCKS5 proxy on `127.0.0.1:[bindPort]` (no authentication, CONNECT only; RFC 1928).
     * Point the phone's browser or another app at it to reach anything the server can reach.
     */
    public fun dynamic(bindPort: Int): PortForwardHandle =
        Listener(bind(bindPort), io) { client ->
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
        val scope = CoroutineScope(io)
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
        private val handle: suspend (Socket) -> Unit,
    ) : PortForwardHandle {
        private val scope = CoroutineScope(SupervisorJob() + io)
        private val count = AtomicInteger()
        private val active = MutableStateFlow(0)
        override val localPort: Int = server.localPort
        override val activeConnections: StateFlow<Int> = active.asStateFlow()
        private val acceptLoop: Job =
            scope.launch {
                while (true) {
                    val client =
                        try {
                            server.accept()
                        } catch (_: SocketException) {
                            break // closed
                        }
                    client.tcpNoDelay = true
                    scope.launch {
                        active.value = count.incrementAndGet()
                        try {
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
                }
            }

        override fun close() {
            closeQuietly(server)
            runBlocking { acceptLoop.join() }
            scope.cancel()
        }
    }

    private companion object {
        const val MAX_PORT = 65_535
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
