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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
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

        /**
         * Starts [forward] (no-op if it is already running), unless [wanted] says no: it is asked
         * before and again once the forward holds its host's lease. From then on "Disconnect all"
         * stops the forward (its connection goes [HostConnectionState.Idle]), so an auto-start
         * racing "Disconnect all" can't bring the host back up.
         */
        fun start(
            forward: PortForward,
            wanted: () -> Boolean = { true },
        ) {
            if (jobs.containsKey(forward.id) || !wanted()) return
            // Lazy, so a start that loses the race below (two terminals opening on the host at
            // once) is cancelled before it takes a port or a connection lease.
            val job = scope.launch(start = CoroutineStart.LAZY) { run(forward, wanted, coroutineContext.job) }
            if (jobs.putIfAbsent(forward.id, job) != null) {
                job.cancel()
                return
            }
            set(forward.id, ForwardStatus(ForwardStatus.Phase.STARTING), job)
            job.start()
        }

        /** Stops the forward with [id] and closes its listening socket. */
        fun stop(id: EntityId) {
            jobs.remove(id)?.cancel()
            mutable.update { it - id }
        }

        /**
         * Stops [forward] if it is running, waits until its listening socket is closed, then
         * starts it again: a plain stop-then-start could find the old socket still bound.
         */
        suspend fun restart(forward: PortForward) {
            jobs.remove(forward.id)?.cancelAndJoin()
            mutable.update { it - forward.id }
            start(forward)
        }

        private suspend fun run(
            forward: PortForward,
            wanted: () -> Boolean,
            job: Job,
        ) {
            connections.acquire(forward.hostId).use { lease ->
                if (!wanted()) {
                    stop(forward.id, job)
                    return
                }
                lease.state.collectLatest { state ->
                    when (state) {
                        is HostConnectionState.Connected -> serve(forward, state.connection, job)
                        is HostConnectionState.Failed ->
                            set(forward.id, failed(state.error.localizedMessage), job)
                        HostConnectionState.Idle -> stop(forward.id, job) // "Disconnect all"
                        else -> set(forward.id, ForwardStatus(ForwardStatus.Phase.STARTING), job)
                    }
                }
            }
        }

        /** [stop], called by [job] on itself: a no-op once the forward has been handed to another job. */
        private fun stop(
            id: EntityId,
            job: Job,
        ) {
            if (jobs.remove(id, job)) {
                job.cancel()
                mutable.update { it - id }
            }
        }

        /**
         * Starts the forwards of [hostId] marked "start automatically" (those already running are
         * left alone) while [wanted] holds (see [start]). Runs whenever a terminal opens on the
         * host ([ForwardsModule]), wanted while the host still has a terminal tab.
         */
        suspend fun startAutoForwards(
            hostId: EntityId,
            wanted: () -> Boolean = { true },
        ) {
            repository
                .observeForHost(hostId)
                .first()
                .filter { it.autoStart }
                .forEach { start(it, wanted) }
        }

        private suspend fun serve(
            forward: PortForward,
            connection: SshConnection,
            job: Job,
        ) {
            val handle =
                try {
                    open(forward, connection)
                } catch (e: IOException) {
                    set(forward.id, failed(e.localizedMessage), job)
                    return
                } catch (e: IllegalArgumentException) {
                    set(forward.id, failed(e.localizedMessage), job)
                    return
                }
            try {
                handle.activeConnections.collect { count ->
                    set(forward.id, ForwardStatus(ForwardStatus.Phase.RUNNING, handle.localPort, count), job)
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
            job: Job,
        ) {
            // A forward stopped or handed to another job while [job] winds down must not reappear.
            if (jobs[id] === job) mutable.update { it + (id to status) }
        }
    }

/** A host's auto-start forwards come up with its terminal: the terminal launcher runs this hook. */
@Module
@InstallIn(SingletonComponent::class)
object ForwardsModule {
    @Provides
    fun hostAutoStart(forwards: ForwardManager): HostAutoStart =
        HostAutoStart { hostId, wanted -> forwards.startAutoForwards(hostId, wanted) }
}
