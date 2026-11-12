package com.kitefoldlabs.bridle.feature.terminal

import android.content.Intent
import com.kitefoldlabs.bridle.connect.UnknownHostException
import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.TerminalSession
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.terminalui.CommandLineSuggestions
import com.kitefoldlabs.bridle.core.terminalui.EngineSink
import com.kitefoldlabs.bridle.core.terminalui.PathCompleter
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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens terminal tabs. This is what other features call ("Connect" on a host, "Attach" on a tmux
 * session, a notification tap): it creates the emulator, wires it to the session in both
 * directions, starts the foreground service and brings the terminal screen up. Then, in the
 * background, it runs [autoStart] for the host (its auto-start port forwards).
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
        private val autoStart: HostAutoStart,
        @param:ApplicationScope private val scope: CoroutineScope,
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
            // The completer lists files over SFTP on the tab's connection, once the session exists.
            var session: TerminalSession? = null
            val completer = PathCompleter({ session?.openSftp() ?: throw IOException("Not connected") })
            val attachment = TerminalAttachment(engine, EngineSink(engine), CommandLineSuggestions(engine, completer))
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
                        onCreated = { created ->
                            session = created
                            engine.sendRemoteInputTo(created.asTerminalConnection())
                            attachment.suggestions?.follow(created)
                        },
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
            startWithHost(hostId)
            return tab
        }

        /** Runs [autoStart] without holding up the terminal, which is open already and must stay so. */
        private fun startWithHost(hostId: EntityId) {
            scope.launch {
                try {
                    autoStart.start(hostId) { coordinator.tabs.value.any { it.hostId == hostId } }
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Exception,
                ) {
                    // Nothing to undo: the terminal works without its forwards, and the Port
                    // forwards screen shows them stopped. Cancellation (nothing follows) ends here too.
                }
            }
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

        /**
         * Opens a terminal on [hostId] from the Hosts list (a tap or "Connect"), attaching to the
         * host's default tmux session if it has one. A host deleted in the meantime opens nothing.
         */
        fun openHost(hostId: EntityId): Job =
            scope.launch {
                try {
                    launcher.openTerminal(hostId)
                } catch (_: IllegalArgumentException) {
                    // Deleted host (UnknownHostException) or an invalid default session name.
                }
            }
    }
