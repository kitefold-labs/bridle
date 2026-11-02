package com.kitefoldlabs.bridle.feature.forwards

import com.kitefoldlabs.bridle.core.data.PortForwardRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.session.HostConnectionState
import com.kitefoldlabs.bridle.core.ssh.PortForwardHandle
import com.kitefoldlabs.bridle.core.ssh.PortForwarder
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.di.ApplicationScope
import com.kitefoldlabs.bridle.feature.terminal.HostAutoStart
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A started forward. [localPort] and [connections] are known once it is listening. */
data class ForwardStatus(
    val phase: Phase,
    val localPort: Int? = null,
    val connections: Int = 0,
    val error: String? = null,
) {
    enum class Phase { STARTING, RUNNING, FAILED }
}

/**
 * Runs port forwards in the application scope, so they keep working while the user is in a
 * terminal or another app. Each running forward holds a lease on its host's shared connection and
 * is re-established on the new connection after a reconnect.
 */
@Singleton
class ForwardManager
    @Inject
    constructor(
        private val connections: ConnectionManager,
        private val repository: PortForwardRepository,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private val mutable = MutableStateFlow<Map<EntityId, ForwardStatus>>(emptyMap())
        private val jobs = ConcurrentHashMap<EntityId, Job>()

        /** Status of every started forward, by forward id. Stopped forwards are absent. */
        val statuses: StateFlow<Map<EntityId, ForwardStatus>> = mutable.asStateFlow()

        /** Starts [forward] (no-op if it is already running). */
        fun start(forward: PortForward) {
            if (jobs.containsKey(forward.id)) return
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    connections.acquire(forward.hostId).use { lease ->
                        lease.state.collectLatest { state ->
                            when (state) {
                                is HostConnectionState.Connected -> serve(forward, state.connection)
                                is HostConnectionState.Failed ->
                                    set(forward.id, failed(state.error.localizedMessage))
                                HostConnectionState.Idle -> stop(forward.id) // "Disconnect all"
                                else -> set(forward.id, ForwardStatus(ForwardStatus.Phase.STARTING))
                            }
                        }
                    }
                }
            jobs[forward.id] = job
            set(forward.id, ForwardStatus(ForwardStatus.Phase.STARTING))
            job.start()
        }

        /** Stops the forward with [id] and closes its listening socket. */
        fun stop(id: EntityId) {
            jobs.remove(id)?.cancel()
            mutable.update { it - id }
        }

        /**
         * Starts the forwards of [hostId] marked "start automatically" (those already running are
         * left alone). Runs whenever a terminal opens on the host ([ForwardsModule]).
         */
        suspend fun startAutoForwards(hostId: EntityId) {
            repository
                .observeForHost(hostId)
                .first()
                .filter { it.autoStart }
                .forEach(::start)
        }

        private suspend fun serve(
            forward: PortForward,
            connection: SshConnection,
        ) {
            val handle =
                try {
                    open(forward, connection)
                } catch (e: IOException) {
                    set(forward.id, failed(e.localizedMessage))
                    return
                } catch (e: IllegalArgumentException) {
                    set(forward.id, failed(e.localizedMessage))
                    return
                }
            try {
                handle.activeConnections.collect { count ->
                    set(forward.id, ForwardStatus(ForwardStatus.Phase.RUNNING, handle.localPort, count))
                }
            } finally {
                handle.close()
            }
        }

        private fun open(
            forward: PortForward,
            connection: SshConnection,
        ): PortForwardHandle {
            val forwarder = PortForwarder(connection)
            return when (forward.type) {
                PortForwardType.LOCAL ->
                    forwarder.local(forward.bindPort, forward.destHost.orEmpty(), forward.destPort ?: 0)
                PortForwardType.DYNAMIC -> forwarder.dynamic(forward.bindPort)
            }
        }

        private fun failed(message: String?) = ForwardStatus(ForwardStatus.Phase.FAILED, error = message)

        private fun set(
            id: EntityId,
            status: ForwardStatus,
        ) {
            // A forward stopped while its job winds down must not reappear.
            if (jobs.containsKey(id)) mutable.update { it + (id to status) }
        }
    }

/** A host's auto-start forwards come up with its terminal: the terminal launcher runs this hook. */
@Module
@InstallIn(SingletonComponent::class)
object ForwardsModule {
    @Provides
    fun hostAutoStart(forwards: ForwardManager): HostAutoStart = HostAutoStart { forwards.startAutoForwards(it) }
}
