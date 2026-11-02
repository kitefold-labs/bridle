package com.kitefoldlabs.bridle.feature.terminal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Text for tab [tabId] that its screen must confirm before pasting: it would run lines straight away. */
data class PendingPaste(
    val tabId: String,
    val text: String,
)

/**
 * Which terminal tab is selected, and requests to bring the terminal screen up. Lives outside the
 * UI so that a tab opened from the sessions screen or a notification is shown wherever the user
 * is, and so the status monitor knows which session is on screen.
 */
@Singleton
class TerminalNavigator
    @Inject
    constructor() {
        private val selected = MutableStateFlow<String?>(null)
        private val visible = MutableStateFlow<String?>(null)

        // Conflated: a request made before the UI is composed (a notification tap) still arrives.
        private val requests = Channel<Unit>(Channel.CONFLATED)
        private val pastes = MutableStateFlow<PendingPaste?>(null)

        /** The tab the user last picked or opened; may name a tab that has since closed. */
        val selectedTabId: StateFlow<String?> = selected.asStateFlow()

        /** The tab on screen right now, or null while the terminal screen is not visible. */
        val visibleTabId: StateFlow<String?> = visible.asStateFlow()

        /** Emits each time the terminal screen should be shown. Collect it from one place (the nav host). */
        val showRequests: Flow<Unit> = requests.receiveAsFlow()

        fun select(tabId: String) {
            selected.value = tabId
        }

        /** Selects [tabId] and asks for the terminal screen. */
        fun show(tabId: String) {
            select(tabId)
            showTerminals()
        }

        /** Asks for the terminal screen, keeping the current selection. */
        fun showTerminals() {
            requests.trySend(Unit)
        }

        fun setVisibleTab(tabId: String?) {
            visible.value = tabId
        }

        /** A paste waiting for its tab's screen, which asks before pasting it; null when there is none. */
        val pendingPaste: StateFlow<PendingPaste?> = pastes.asStateFlow()

        /**
         * Shows [tabId] and hands [text] to its screen to paste once the user confirms (see
         * [com.kitefoldlabs.bridle.core.terminalui.TerminalScreenState.paste]). Replaces a paste
         * still waiting.
         */
        fun requestPaste(
            tabId: String,
            text: String,
        ) {
            pastes.value = PendingPaste(tabId, text)
            show(tabId)
        }

        /** The screen took [paste]; a newer paste requested meanwhile stays. */
        fun pasteTaken(paste: PendingPaste) {
            pastes.compareAndSet(paste, null)
        }
    }
