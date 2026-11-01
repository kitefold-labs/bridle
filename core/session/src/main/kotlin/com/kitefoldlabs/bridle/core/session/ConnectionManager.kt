package com.kitefoldlabs.bridle.core.session

import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.ssh.AuthenticationFailedException
import com.kitefoldlabs.bridle.core.ssh.ConnectionState
import com.kitefoldlabs.bridle.core.ssh.HostKeyException
import com.kitefoldlabs.bridle.core.ssh.ReconnectPolicy
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Opens an authenticated connection to a host (resolving keys, jump hosts and host-key trust). */
public fun interface HostConnector {
    public suspend fun connect(hostId: EntityId): SshConnection
}

/** Connection status of one host, as shown in the UI. */
public sealed interface HostConnectionState {
    public data object Idle : HostConnectionState

    public data object Connecting : HostConnectionState

    public data class Connected(
        val connection: SshConnection,
    ) : HostConnectionState

    /** Waiting before reconnect attempt [attempt]; [delay] is how long. */
    public data class Reconnecting(
        val attempt: Int,
        val delay: Duration,
        val lastError: String?,
    ) : HostConnectionState

    /** Gave up (or a non-retryable error such as a changed host key or rejected credentials). */
    public data class Failed(
        val error: Throwable,
        val retryable: Boolean,
    ) : HostConnectionState
}

/** A claim on a host's shared connection. Close it when the tab/screen/transfer is done. */
public interface ConnectionLease : AutoCloseable {
    public val hostId: EntityId
    public val state: StateFlow<HostConnectionState>

    /** Suspends until connected (or throws the terminal failure). */
    public suspend fun awaitConnection(): SshConnection
}

/**
 * Shares one SSH connection per host across terminal tabs, SFTP and status polling (like
 * OpenSSH ControlMaster), reconnects with [ReconnectPolicy] while anything still holds a lease,
 * and closes the connection [idleGrace] after the last lease is released.
 */
public class ConnectionManager(
    private val connector: HostConnector,
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val idleGrace: Duration = 60.seconds,
) {
    private val mutex = Mutex()

    // Concurrent: [state] reads it without the mutex.
    private val entries = ConcurrentHashMap<EntityId, Entry>()

    /** Acquires a lease on [hostId], connecting if needed. */
    public suspend fun acquire(hostId: EntityId): ConnectionLease =
        mutex.withLock {
            val entry = entries.getOrPut(hostId) { Entry(hostId) }
            entry.leases++
            entry.closeJob?.cancel()
            entry.closeJob = null
            if (entry.state.value is HostConnectionState.Idle || entry.state.value is HostConnectionState.Failed) {
                entry.start()
            }
            Lease(entry)
        }

    /** Current state for [hostId] (Idle if unknown). */
    public fun state(hostId: EntityId): StateFlow<HostConnectionState> =
        entries[hostId]?.state ?: MutableStateFlow(HostConnectionState.Idle).asStateFlow()

    /** Hosts with at least one active lease. */
    public suspend fun activeHosts(): Set<EntityId> = mutex.withLock { entries.filterValues { it.leases > 0 }.keys }

    /**
     * The phone switched networks (or came back online): retry immediately instead of waiting
     * out the backoff, since the old TCP connection is almost certainly dead.
     */
    public suspend fun onNetworkChanged() {
        mutex.withLock {
            for (entry in entries.values) {
                if (entry.leases == 0) continue
                when (entry.state.value) {
                    is HostConnectionState.Reconnecting -> entry.start()
                    is HostConnectionState.Failed ->
                        if ((entry.state.value as HostConnectionState.Failed).retryable) entry.start()
                    else -> Unit
                }
            }
        }
    }

    /** Explicit "Reconnect" button. */
    public suspend fun reconnect(hostId: EntityId) {
        mutex.withLock { entries[hostId]?.takeIf { it.leases > 0 }?.start() }
    }

    /** Closes everything (app shutdown / "Disconnect all"). Outstanding leases become inert. */
    public suspend fun closeAll() {
        mutex.withLock {
            entries.values.forEach { it.shutdown() }
            entries.clear()
        }
    }

    private inner class Lease(
        private val entry: Entry,
    ) : ConnectionLease {
        private val closed = AtomicBoolean(false)
        override val hostId: EntityId get() = entry.hostId
        override val state: StateFlow<HostConnectionState> get() = entry.state

        override suspend fun awaitConnection(): SshConnection =
            // Idle only follows start() after a shutdown (closeAll): don't wait forever for it.
            when (val settled = entry.state.first { it.isSettled() }) {
                is HostConnectionState.Connected -> settled.connection
                is HostConnectionState.Failed -> throw settled.error
                else -> throw ConnectionClosedException()
            }

        override fun close() {
            if (closed.compareAndSet(false, true)) scope.launch { release(entry) }
        }
    }

    private suspend fun release(entry: Entry) {
        mutex.withLock {
            entry.leases--
            // A lease that outlived closeAll() must not schedule a close that would remove the
            // host's *new* entry from the map.
            if (entry.leases > 0 || entry.isShutDown) return
            entry.closeJob =
                scope.launch {
                    delay(idleGrace)
                    mutex.withLock {
                        if (entry.leases == 0 && !entry.isShutDown) {
                            entry.shutdown()
                            entries.remove(entry.hostId, entry)
                        }
                    }
                }
        }
    }

    private inner class Entry(
        val hostId: EntityId,
    ) {
        val state = MutableStateFlow<HostConnectionState>(HostConnectionState.Idle)
        var leases = 0
        var closeJob: Job? = null
        var isShutDown = false
            private set
        private var job: Job? = null

        /**
         * Bumped whenever a new connect loop takes over (or the entry shuts down). A cancelled loop
         * may still be running on another thread; [publish] stops it from overwriting the state.
         */
        private var generation = 0

        /** (Re)starts the connect loop, dropping the current connection if any. Caller holds the mutex. */
        fun start() {
            job?.cancel()
            val current = takeOver(HostConnectionState.Connecting)
            job = scope.launch { connectLoop(current) }
        }

        fun shutdown() {
            isShutDown = true
            job?.cancel()
            closeJob?.cancel()
            takeOver(HostConnectionState.Idle)
        }

        /** Starts a new generation in [next], closing a connection the previous one published. */
        private fun takeOver(next: HostConnectionState): Int =
            synchronized(this) {
                generation++
                // An explicit reconnect is for a link that looks hung: never keep (and leak) it.
                (state.value as? HostConnectionState.Connected)?.connection?.close()
                // Set synchronously so waiters never observe a stale Failed/Connected state.
                state.value = next
                generation
            }

        /** Sets [value] if loop [owner] is still the current one. */
        private fun publish(
            owner: Int,
            value: HostConnectionState,
        ): Boolean =
            synchronized(this) {
                (owner == generation).also { current -> if (current) state.value = value }
            }

        private suspend fun connectLoop(owner: Int) {
            var attempt = 0
            var lastError: String? = null
            while (true) {
                val wait = policy.nextDelay(attempt)
                if (wait == null) {
                    publish(owner, HostConnectionState.Failed(GaveUpException(lastError), retryable = true))
                    return
                }
                if (attempt > 0) {
                    publish(owner, HostConnectionState.Reconnecting(attempt, wait, lastError))
                    delay(wait)
                }
                attempt++
                publish(owner, HostConnectionState.Connecting)
                val connection =
                    try {
                        connector.connect(hostId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: HostKeyException) {
                        publish(owner, HostConnectionState.Failed(e, retryable = false))
                        return
                    } catch (e: AuthenticationFailedException) {
                        publish(owner, HostConnectionState.Failed(e, retryable = false))
                        return
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        lastError = e.message ?: e.javaClass.simpleName
                        continue
                    }
                try {
                    // A loop that was superseded while connecting closes what it got (finally).
                    if (!publish(owner, HostConnectionState.Connected(connection))) return
                    connection.state.first { it == ConnectionState.DISCONNECTED }
                } finally {
                    connection.close()
                }
                lastError = "Connection lost"
                attempt = 1 // first retry after a drop waits a little (the network may be flapping)
            }
        }
    }

    private fun HostConnectionState.isSettled(): Boolean =
        this is HostConnectionState.Connected || this is HostConnectionState.Failed || this is HostConnectionState.Idle
}

/** Every reconnect attempt failed. */
public class GaveUpException(
    lastError: String?,
) : Exception("Could not reconnect" + (lastError?.let { ": $it" } ?: ""))

/** The connection was closed for good ([ConnectionManager.closeAll]) while a lease was waiting for it. */
public class ConnectionClosedException : Exception("Disconnected")
