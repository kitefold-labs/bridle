package com.kitefoldlabs.bridle.service

import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.session.TerminalSession
import com.kitefoldlabs.bridle.core.session.TerminalSink
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.tmux.TmuxController
import com.kitefoldlabs.bridle.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An open terminal tab. [attachment] carries the UI's emulator so it outlives the screen; if it is
 * [AutoCloseable] it is closed with the tab.
 */
data class TerminalTab(
    val id: String,
    val hostId: EntityId,
    val title: String,
    val session: TerminalSession,
    val attachment: Any?,
)

/**
 * Owns everything that must outlive the UI: shared host connections and open terminal tabs. The
 * [SessionService] keeps the process in the foreground while [hasActiveWork] is true.
 */
@Singleton
class SessionCoordinator
    @Inject
    constructor(
        val connections: ConnectionManager,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private val mutableTabs = MutableStateFlow<List<TerminalTab>>(emptyList())
        val tabs: StateFlow<List<TerminalTab>> = mutableTabs.asStateFlow()

        /** True while any tab is open (drives the foreground service). */
        val hasActiveWork: Boolean get() = mutableTabs.value.isNotEmpty()

        /**
         * Opens a tab on [hostId]. The [sink] (emulator) is owned by the tab from now on.
         * [onCreated] runs before the session starts, so the emulator's replies to the remote
         * side's first queries (tmux and Claude Code ask for terminal attributes) can be wired to
         * it without losing any.
         *
         * @throws IllegalArgumentException if [attach] names an invalid tmux session.
         */
        suspend fun openTab(
            hostId: EntityId,
            title: String,
            attach: AttachSpec,
            sink: TerminalSink,
            size: PtySpec = PtySpec(),
            attachment: Any? = null,
            onCreated: (TerminalSession) -> Unit = {},
        ): TerminalTab {
            val lease = connections.acquire(hostId)
            val session =
                try {
                    TerminalSession(lease, attach, sink, scope, size)
                } catch (e: IllegalArgumentException) {
                    lease.close()
                    throw e
                }
            onCreated(session)
            val tab = TerminalTab(UUID.randomUUID().toString(), hostId, title, session, attachment)
            mutableTabs.update { it + tab }
            session.start()
            return tab
        }

        fun tab(id: String): TerminalTab? = mutableTabs.value.firstOrNull { it.id == id }

        fun closeTab(id: String) {
            val tab = tab(id) ?: return
            mutableTabs.update { tabs -> tabs.filterNot { it.id == id } }
            tab.close()
        }

        /** Closes every tab and connection ("Disconnect all"). */
        suspend fun disconnectAll() {
            val all = mutableTabs.value
            mutableTabs.value = emptyList()
            all.forEach { it.close() }
            connections.closeAll()
        }

        private fun TerminalTab.close() {
            session.close()
            (attachment as? AutoCloseable)?.close()
        }

        /**
         * Runs [block] with a tmux controller on [hostId]'s shared connection (e.g. a notification
         * action answering a Claude permission prompt).
         */
        suspend fun <T> withTmux(
            hostId: EntityId,
            block: suspend (TmuxController) -> T,
        ): T =
            connections.acquire(hostId).use { lease ->
                block(TmuxController(lease.awaitConnection()))
            }
    }
