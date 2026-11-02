package com.kitefoldlabs.bridle.feature.terminal

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.data.ComposeHistoryRepository
import com.kitefoldlabs.bridle.core.data.settings.Settings
import com.kitefoldlabs.bridle.core.data.settings.SettingsRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.terminal.BackspaceMode
import com.kitefoldlabs.bridle.core.terminal.ComposeHistory
import com.kitefoldlabs.bridle.core.terminal.InputDispatcher
import com.kitefoldlabs.bridle.core.terminal.KeyEncoder
import com.kitefoldlabs.bridle.core.terminalui.FontSizeBounds
import com.kitefoldlabs.bridle.core.terminalui.TerminalScreenState
import com.kitefoldlabs.bridle.core.terminalui.asTerminalConnection
import com.kitefoldlabs.bridle.di.ApplicationScope
import com.kitefoldlabs.bridle.service.SessionCoordinator
import com.kitefoldlabs.bridle.service.TerminalTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the tab row can ask for. Implemented by [TerminalTabsViewModel]. */
interface TerminalTabsActions {
    fun select(tabId: String)

    fun close(tabId: String)

    fun reconnect(tabId: String)

    fun clearHistory(hostId: EntityId)
}

/** One entry in the tab row. */
data class TerminalTabItem(
    val id: String,
    val hostId: EntityId,
    val title: String,
    val state: TerminalState,
)

/**
 * The terminal screen: the open tabs, which one is selected, and a [TerminalScreenState] per tab.
 *
 * Sessions and emulators live in [SessionCoordinator] (they outlive this ViewModel); the screen
 * states live here so the compose box and zoom survive rotation. Settings apply live: the font
 * size when it changes, the backspace mode by rebuilding the screen states. Compose-box history
 * is shared by the tabs of a host and saved after every submission.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalTabsViewModel
    @Inject
    constructor(
        private val coordinator: SessionCoordinator,
        private val launcher: TerminalLauncher,
        private val navigator: TerminalNavigator,
        settingsRepository: SettingsRepository,
        private val historyRepository: ComposeHistoryRepository,
        @param:ApplicationScope private val appScope: CoroutineScope,
    ) : ViewModel(),
        TerminalTabsActions {
        private class TabScreen(
            val state: TerminalScreenState,
            val scope: CoroutineScope,
            val backspace: BackspaceMode,
        )

        private val tabScreens = mutableMapOf<String, TabScreen>()
        private val histories = mutableMapOf<EntityId, ComposeHistory>()
        private val savedHistories = mutableMapOf<EntityId, List<String>>()
        private var appliedFontSize: Int? = null
        private val mutableScreens = MutableStateFlow<Map<String, TerminalScreenState>>(emptyMap())

        /** Null until the settings are first read. */
        val settings: StateFlow<Settings?> =
            settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val tabs: StateFlow<List<TerminalTabItem>> =
            coordinator.tabs
                .flatMapLatest { tabs ->
                    if (tabs.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        val items =
                            tabs.map { tab ->
                                tab.session.state.map { TerminalTabItem(tab.id, tab.hostId, tab.title, it) }
                            }
                        combine(items) { it.toList() }
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** The selected tab: the one last picked or opened, else the newest. Null when none are open. */
        val selectedTabId: StateFlow<String?> =
            combine(coordinator.tabs, navigator.selectedTabId) { tabs, id ->
                tabs.firstOrNull { it.id == id }?.id ?: tabs.lastOrNull()?.id
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** Screen state per tab id. Tabs without an emulator (not opened by [TerminalLauncher]) have none. */
        val screens: StateFlow<Map<String, TerminalScreenState>> = mutableScreens.asStateFlow()

        init {
            viewModelScope.launch {
                combine(coordinator.tabs, settings.filterNotNull(), ::Pair).collect { (tabs, current) ->
                    syncScreens(tabs, current)
                }
            }
            // A paste that must be confirmed (a snippet): its screen asks once it exists.
            viewModelScope.launch {
                navigator.pendingPaste.filterNotNull().collectLatest { paste ->
                    val screen = mutableScreens.first { paste.tabId in it }.getValue(paste.tabId)
                    navigator.pasteTaken(paste)
                    screen.paste(paste.text)
                }
            }
        }

        override fun select(tabId: String) {
            navigator.select(tabId)
        }

        override fun close(tabId: String) {
            launcher.close(tabId)
        }

        /** Replaces an ended tab with a new one attached the same way. */
        override fun reconnect(tabId: String) {
            viewModelScope.launch { launcher.reconnect(tabId) }
        }

        /** The tab on screen (null when the screen is stopped), so notifications about it are skipped. */
        fun setVisibleTab(tabId: String?) {
            navigator.setVisibleTab(tabId)
        }

        /** Forgets the compose-box history of [hostId] (it may contain secrets). */
        override fun clearHistory(hostId: EntityId) {
            histories[hostId]?.clear()
            savedHistories[hostId] = emptyList()
            appScope.launch { historyRepository.clear(hostId) }
        }

        private suspend fun syncScreens(
            tabs: List<TerminalTab>,
            current: Settings,
        ) {
            val open = tabs.map { it.id }.toSet()
            // Closed tabs go, and so does every screen when the backspace mode changed (fixed per screen).
            tabScreens.entries.removeAll { (id, screen) ->
                val stale = id !in open || screen.backspace != current.backspaceMode
                if (stale) screen.scope.cancel()
                stale
            }
            for (tab in tabs) {
                val attachment = tab.attachment as? TerminalAttachment
                if (attachment != null &&
                    tab.id !in tabScreens
                ) {
                    tabScreens[tab.id] = createScreen(tab, attachment, current)
                }
            }
            if (appliedFontSize != current.terminalFontSizeSp) {
                appliedFontSize = current.terminalFontSizeSp
                tabScreens.values.forEach { it.state.setFontSize(current.terminalFontSizeSp.toFloat()) }
            }
            mutableScreens.value = tabScreens.mapValues { it.value.state }
        }

        private suspend fun createScreen(
            tab: TerminalTab,
            attachment: TerminalAttachment,
            current: Settings,
        ): TabScreen {
            val history = histories[tab.hostId] ?: loadHistory(tab.hostId)
            val scope = CoroutineScope(viewModelScope.coroutineContext + Job(viewModelScope.coroutineContext[Job]))
            val state =
                TerminalScreenState(
                    engine = attachment.engine,
                    connection = tab.session.asTerminalConnection(),
                    scope = scope,
                    history = history,
                    dispatcher = InputDispatcher(KeyEncoder(current.backspaceMode)),
                    fontBounds =
                        FontSizeBounds(
                            min = Settings.MIN_FONT_SIZE_SP.toFloat(),
                            max = Settings.MAX_FONT_SIZE_SP.toFloat(),
                            default = current.terminalFontSizeSp.toFloat(),
                        ),
                )
            // A submission clears the compose text: that is when history may have changed.
            scope.launch { snapshotFlow { state.composeText }.collect { persistHistory(tab.hostId) } }
            return TabScreen(state, scope, current.backspaceMode)
        }

        private suspend fun loadHistory(hostId: EntityId): ComposeHistory {
            val entries = historyRepository.get(hostId)
            savedHistories[hostId] = entries
            return ComposeHistory(entries).also { histories[hostId] = it }
        }

        private fun persistHistory(hostId: EntityId) {
            val entries = histories.getValue(hostId).entries
            if (savedHistories[hostId] == entries) return
            savedHistories[hostId] = entries
            appScope.launch { historyRepository.save(hostId, entries) }
        }
    }
