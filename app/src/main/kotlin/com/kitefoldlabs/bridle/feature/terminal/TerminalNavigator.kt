package com.kitefoldlabs.bridle.feature.terminal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

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
    }
