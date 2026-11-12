package com.kitefoldlabs.bridle.feature.sessions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.data.LaunchProfileRepository
import com.kitefoldlabs.bridle.core.data.settings.NotificationEvent
import com.kitefoldlabs.bridle.core.data.settings.SettingsRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.ClaudeStatusEvent
import com.kitefoldlabs.bridle.core.session.ClaudeStatusMonitor
import com.kitefoldlabs.bridle.core.session.HostConnectionState
import com.kitefoldlabs.bridle.core.session.PollMode
import com.kitefoldlabs.bridle.core.ssh.RemoteCommandException
import com.kitefoldlabs.bridle.core.tmux.ClaudeState
import com.kitefoldlabs.bridle.core.tmux.HostProbe
import com.kitefoldlabs.bridle.core.tmux.SessionSummary
import com.kitefoldlabs.bridle.core.tmux.TmuxController
import com.kitefoldlabs.bridle.di.ApplicationScope
import com.kitefoldlabs.bridle.feature.terminal.TerminalNavigator
import com.kitefoldlabs.bridle.service.ClaudeNotifier
import com.kitefoldlabs.bridle.service.SessionCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Whether the user can see the app (any activity started), which sets how often hosts are polled. */
class AppVisibility(
    val isVisible: StateFlow<Boolean>,
) {
    companion object {
        /** Follows [lifecycle], normally [ProcessLifecycleOwner]'s. */
        fun of(
            lifecycle: Lifecycle,
            scope: CoroutineScope,
        ): AppVisibility =
            AppVisibility(
                lifecycle.currentStateFlow
                    .map { it.isAtLeast(Lifecycle.State.STARTED) }
                    .stateIn(scope, SharingStarted.Eagerly, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)),
            )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SessionsModule {
    @Provides
    @Singleton
    fun visibility(
        @ApplicationScope scope: CoroutineScope,
    ): AppVisibility = AppVisibility.of(ProcessLifecycleOwner.get().lifecycle, scope)
}

/** What the sessions screen shows for one host. */
data class HostStatus(
    val connection: HostConnectionState = HostConnectionState.Idle,
    /** What the host offers (tmux, claude, bridle-helper, shell integration); null until probed. */
    val probe: HostProbe? = null,
    val sessions: List<SessionSummary> = emptyList(),
    /** The last few non-blank lines of each session's active pane, while the sessions screen is open. */
    val previews: Map<String, String> = emptyMap(),
    /** True once the host has been read at least once. */
    val loaded: Boolean = false,
    /** Why the last refresh failed, if it did. */
    val error: String? = null,
)

/**
 * Watches Claude Code on hosts: one [ClaudeStatusMonitor] per host, for as long as the host has an
 * open terminal tab ([followOpenTabs], run by the session service) or the sessions screen is
 * looking at it ([observe]). Hosts are polled every [PollMode.FOREGROUND] interval while the app
 * is visible and every [PollMode.BACKGROUND] interval otherwise, over the host's shared
 * connection.
 *
 * Status transitions that pass [com.kitefoldlabs.bridle.core.session.NotificationPolicy] (the
 * monitor applies it) post a [ClaudeNotifier] notification when the user's settings allow that
 * kind of event, unless the session is on screen in a terminal tab. There is no push: nothing is
 * watched once the connections close.
 */
@Singleton
class ClaudeStatusHub
    @Inject
    constructor(
        private val coordinator: SessionCoordinator,
        private val hosts: HostRepository,
        private val profiles: LaunchProfileRepository,
        private val settings: SettingsRepository,
        private val notifier: ClaudeNotifier,
        private val navigator: TerminalNavigator,
        private val visibility: AppVisibility,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private val lock = Any()
        private val runners = mutableMapOf<EntityId, Runner>()

        /**
         * The live status of [hostId]. While collected, the host stays connected and monitored, and
         * pane previews are fetched.
         */
        fun observe(hostId: EntityId): Flow<HostStatus> =
            flow {
                val runner = watch(hostId, ui = true)
                try {
                    emitAll(runner.status)
                } finally {
                    unwatch(runner, ui = true)
                }
            }

        /** Re-probes and polls [hostId] now (after an action, or when the user asks). */
        fun refresh(hostId: EntityId) {
            synchronized(lock) { runners[hostId] }?.refresh?.trySend(Unit)
        }

        /** Monitors every host that has an open terminal tab, until cancelled. */
        suspend fun followOpenTabs() {
            var watched = emptyMap<EntityId, Runner>()
            try {
                coordinator.tabs
                    .map { tabs -> tabs.map { it.hostId }.toSet() }
                    .distinctUntilChanged()
                    .collect { ids ->
                        watched.filterKeys { it !in ids }.values.forEach { unwatch(it, ui = false) }
                        watched = ids.associateWith { id -> watched[id] ?: watch(id, ui = false) }
                    }
            } finally {
                watched.values.forEach { unwatch(it, ui = false) }
            }
        }

        /** Posts the notification for [event] if the settings allow it and the session is not on screen. */
        internal suspend fun notify(event: ClaudeStatusEvent) {
            val kind = notificationEvent(event.to) ?: return
            if (!settings.settings.first().notifies(kind) || isOnScreen(event)) return
            val label = hosts.get(event.hostId)?.label ?: return
            notifier.post(event, label)
        }

        private fun isOnScreen(event: ClaudeStatusEvent): Boolean {
            if (!visibility.isVisible.value) return false
            val tab = navigator.visibleTabId.value?.let(coordinator::tab) ?: return false
            return tab.hostId == event.hostId && (tab.session.attach as? AttachSpec.Tmux)?.session == event.session
        }

        private fun watch(
            hostId: EntityId,
            ui: Boolean,
        ): Runner =
            synchronized(lock) {
                val runner = runners.getOrPut(hostId) { Runner(hostId).also { it.start() } }
                runner.watchers++
                if (ui) runner.uiWatchers++
                runner
            }

        private fun unwatch(
            runner: Runner,
            ui: Boolean,
        ) {
            synchronized(lock) {
                runner.watchers--
                if (ui) runner.uiWatchers--
                if (runner.watchers == 0) {
                    runner.stop()
                    runners.remove(runner.hostId)
                }
            }
        }

        private inner class Runner(
            val hostId: EntityId,
        ) {
            val status = MutableStateFlow(HostStatus())
            val refresh = Channel<Unit>(Channel.CONFLATED)
            var watchers = 0

            @Volatile var uiWatchers = 0

            /** bridle-helper is installed and the settings allow using it. */
            @Volatile private var helperActive = false

            @Volatile private var claudeSessions = emptySet<String>()
            private val job: Job = scope.launch(start = CoroutineStart.LAZY) { run() }

            fun start() {
                job.start()
            }

            fun stop() {
                job.cancel()
            }

            private suspend fun run() {
                val lease = coordinator.connections.acquire(hostId)
                try {
                    lease.state.collectLatest { state ->
                        status.update { it.copy(connection = state) }
                        if (state is HostConnectionState.Connected) monitor(TmuxController(state.connection))
                    }
                } finally {
                    lease.close()
                }
            }

            private suspend fun monitor(tmux: TmuxController) =
                coroutineScope {
                    val monitor =
                        ClaudeStatusMonitor(
                            hostId = hostId,
                            tmux = tmux,
                            useHelper = { helperActive },
                            claudeHint = { it in claudeSessions },
                        )
                    launch { monitor.events.collect { notify(it) } }
                    var reprobe = true
                    var failures = 0
                    while (true) {
                        failures = if (poll(tmux, monitor, reprobe)) 0 else failures + 1
                        reprobe = awaitNextRound(failures)
                    }
                }

            /** One round; false if it failed. */
            private suspend fun poll(
                tmux: TmuxController,
                monitor: ClaudeStatusMonitor,
                reprobe: Boolean,
            ): Boolean =
                try {
                    val useHelper = settings.settings.first().useHelperByDefault
                    claudeSessions =
                        profiles
                            .observeForHost(hostId)
                            .first()
                            .filter { CLAUDE in it.command }
                            .mapTo(mutableSetOf()) { it.sessionName }
                    val known = status.value.probe
                    val probe = if (reprobe || known == null) tmux.probe() else known
                    status.update { it.copy(probe = probe) }
                    helperActive = useHelper && probe.helperVersion != null
                    if (probe.tmuxSupported) {
                        monitor.pollOnce()
                        val sessions = monitor.sessions.value
                        val previews = if (uiWatchers > 0) previews(tmux, sessions) else status.value.previews
                        status.update { it.copy(sessions = sessions, previews = previews, loaded = true, error = null) }
                    } else {
                        status.update {
                            it.copy(
                                sessions = emptyList(),
                                previews = emptyMap(),
                                loaded = true,
                                error = null,
                            )
                        }
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    status.update { it.copy(loaded = true, error = e.describe()) }
                    false
                }

            /** The last lines of up to [MAX_PREVIEWS] sessions, fetched in one round trip. */
            private suspend fun previews(
                tmux: TmuxController,
                sessions: List<SessionSummary>,
            ): Map<String, String> {
                val names = sessions.take(MAX_PREVIEWS).map { it.session.name }
                val screens =
                    try {
                        tmux.previews(names, PREVIEW_LINES)
                    } catch (_: RemoteCommandException) {
                        emptyMap() // tmux stopped since it listed them: no previews this round
                    }
                // A session Bridle can't target (or that ended since it was listed) has no preview.
                return names.associateWith { lastLines(screens[it].orEmpty()) }
            }

            /**
             * Waits out the poll interval, ending early when a refresh is asked for (returns true:
             * re-probe) or the app is shown or hidden. A refresh is only taken when it ends the wait,
             * so none is lost. After [failures] failed rounds in a row the wait doubles each time, up
             * to [MAX_BACKOFF], so a host that keeps failing (tmux gone, a stuck server) is not hit
             * every 5 seconds.
             */
            private suspend fun awaitNextRound(failures: Int): Boolean =
                coroutineScope {
                    val visible = visibility.isVisible.value
                    val interval =
                        backoff(if (visible) PollMode.FOREGROUND.interval else PollMode.BACKGROUND.interval, failures)
                    val flipped = async { visibility.isVisible.first { it != visible } }
                    try {
                        withTimeoutOrNull(interval) {
                            select {
                                refresh.onReceive { true }
                                flipped.onAwait { false }
                            }
                        } ?: false
                    } finally {
                        flipped.cancel()
                    }
                }
        }

        companion object {
            /** Pane lines fetched for a preview, and how many non-blank ones are kept. */
            const val PREVIEW_LINES: Int = 12
            const val PREVIEW_KEEP: Int = 3

            /** Previews are fetched for at most this many sessions per round. */
            const val MAX_PREVIEWS: Int = 12

            private const val CLAUDE = "claude"

            /** The longest wait between rounds while a host keeps failing. */
            val MAX_BACKOFF: Duration = 5.minutes

            private const val MAX_DOUBLINGS = 6

            /** [interval] doubled for each of [failures] (up to [MAX_BACKOFF]). */
            fun backoff(
                interval: Duration,
                failures: Int,
            ): Duration = (interval * (1 shl failures.coerceIn(0, MAX_DOUBLINGS))).coerceAtMost(MAX_BACKOFF)

            /** The settings toggle that governs notifications about [state], if any. */
            fun notificationEvent(state: ClaudeState): NotificationEvent? =
                when (state) {
                    ClaudeState.WAITING_PERMISSION -> NotificationEvent.PERMISSION_REQUEST
                    ClaudeState.WAITING_INPUT, ClaudeState.IDLE -> NotificationEvent.WAITING_FOR_INPUT
                    ClaudeState.EXITED -> NotificationEvent.SESSION_EXITED
                    ClaudeState.WORKING, ClaudeState.UNKNOWN -> null
                }

            /** The last [PREVIEW_KEEP] non-blank lines of [screen], right-trimmed. */
            fun lastLines(screen: String): String =
                screen
                    .lineSequence()
                    .map { it.trimEnd() }
                    .filter { it.isNotEmpty() }
                    .toList()
                    .takeLast(PREVIEW_KEEP)
                    .joinToString("\n")
        }
    }

/** A short description of [this] for the UI: its message, else its type. */
internal fun Throwable.describe(): String = message ?: javaClass.simpleName
