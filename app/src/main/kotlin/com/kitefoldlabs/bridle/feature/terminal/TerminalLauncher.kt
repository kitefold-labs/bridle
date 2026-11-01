package com.kitefoldlabs.bridle.feature.terminal

import android.content.Intent
import com.kitefoldlabs.bridle.connect.UnknownHostException
import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.terminalui.EngineSink
import com.kitefoldlabs.bridle.core.terminalui.asTerminalConnection
import com.kitefoldlabs.bridle.core.terminalui.sendRemoteInputTo
import com.kitefoldlabs.bridle.di.ApplicationScope
import com.kitefoldlabs.bridle.service.SessionCoordinator
import com.kitefoldlabs.bridle.service.SessionLinks
import com.kitefoldlabs.bridle.service.TerminalTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens terminal tabs. This is what other features call ("Connect" on a host, "Attach" on a tmux
 * session, a notification tap): it creates the emulator, wires it to the session in both
 * directions, starts the foreground service and brings the terminal screen up.
 */
@Singleton
class TerminalLauncher
    @Inject
    constructor(
        private val coordinator: SessionCoordinator,
        private val hosts: HostRepository,
        private val engines: TerminalEngineFactory,
        private val service: SessionServiceStarter,
        private val navigator: TerminalNavigator,
    ) {
        private val attaching = Mutex()

        /**
         * Opens a tab on [hostId] and shows it. [attach] defaults to the host's default tmux
         * session when it has one, and a plain login shell otherwise.
         *
         * @throws UnknownHostException if the host no longer exists.
         * @throws IllegalArgumentException if [attach] names an invalid tmux session.
         */
        suspend fun openTerminal(
            hostId: EntityId,
            attach: AttachSpec? = null,
        ): TerminalTab {
            val host = hosts.get(hostId) ?: throw UnknownHostException(hostId)
            val spec = attach ?: host.defaultTmuxSession?.let { AttachSpec.Tmux(it) } ?: AttachSpec.Shell
            return open(hostId, titleFor(host, spec), spec)
        }

        /**
         * Shows the live tab attached to tmux [session] on [hostId], or opens one (creating the
         * session in [workingDir] if it does not exist).
         */
        suspend fun attachTmux(
            hostId: EntityId,
            session: String,
            workingDir: String = "~",
        ): TerminalTab =
            // Serialised, so a double tap (or a notification tap racing an Attach) opens one tab.
            attaching.withLock {
                val existing =
                    coordinator.tabs.value.firstOrNull { tab ->
                        tab.hostId == hostId &&
                            (tab.session.attach as? AttachSpec.Tmux)?.session == session &&
                            tab.session.state.value !is TerminalState.Ended
                    }
                if (existing != null) {
                    navigator.show(existing.id)
                    existing
                } else {
                    openTerminal(hostId, AttachSpec.Tmux(session, workingDir))
                }
            }

        /**
         * Replaces tab [tabId] (typically one that ended) with a fresh tab attached the same way.
         * Returns null if the tab is already gone.
         *
         * The new tab opens before the old one closes, so the host's shared connection keeps a
         * lease throughout (no idle teardown) and the foreground service never sees zero tabs and
         * stops in between.
         */
        suspend fun reconnect(tabId: String): TerminalTab? {
            val tab = coordinator.tab(tabId) ?: return null
            val replacement = open(tab.hostId, tab.title, tab.session.attach)
            coordinator.closeTab(tabId)
            return replacement
        }

        fun close(tabId: String) {
            coordinator.closeTab(tabId)
        }

        private suspend fun open(
            hostId: EntityId,
            title: String,
            spec: AttachSpec,
        ): TerminalTab {
            val engine = engines.create()
            val attachment = TerminalAttachment(engine, EngineSink(engine))
            val grid = engine.size.value
            val tab =
                try {
                    coordinator.openTab(
                        hostId = hostId,
                        title = title,
                        attach = spec,
                        sink = attachment.sink,
                        size = PtySpec(grid.columns, grid.rows),
                        attachment = attachment,
                        onCreated = { session -> engine.sendRemoteInputTo(session.asTerminalConnection()) },
                    )
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable,
                ) {
                    // Any failure, cancellation included: the emulator holds native memory.
                    attachment.close()
                    throw e
                }
            service.ensureRunning()
            navigator.show(tab.id)
            return tab
        }

        private fun titleFor(
            host: Host,
            spec: AttachSpec,
        ): String =
            when (spec) {
                AttachSpec.Shell -> host.label
                is AttachSpec.Tmux -> "${spec.session} · ${host.label}"
            }
    }

/**
 * Opens the session a Claude notification is about when the user taps it: MainActivity passes
 * its intent here (kept out of the activity, whose bytecode Hilt rewrites).
 */
@Singleton
class TerminalDeepLinks
    @Inject
    constructor(
        private val launcher: TerminalLauncher,
        private val links: SessionLinks,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        /**
         * Attaches to the host and tmux session named by [intent], if it is a genuine notification
         * tap ([SessionLinks.consume]). Returns the work started, or null if there is none.
         */
        fun handle(intent: Intent?): Job? {
            val link = links.consume(intent) ?: return null
            return scope.launch {
                try {
                    launcher.attachTmux(link.hostId, link.session)
                } catch (_: IllegalArgumentException) {
                    // The host was deleted or the name is not a valid session: nothing to open.
                }
            }
        }
    }
