package com.kitefoldlabs.bridle.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout
import com.kitefoldlabs.bridle.core.terminalui.TerminalScreen
import com.kitefoldlabs.bridle.core.terminalui.TerminalScreenState
import com.kitefoldlabs.bridle.ui.nav.HostTool
import com.kitefoldlabs.bridle.ui.nav.HostTools
import com.kitefoldlabs.bridle.ui.nav.LocalNavController

/** The terminal destination: open tabs, wired to [TerminalTabsViewModel] and the user's settings. */
@Composable
fun TerminalTabsRoute(
    onBack: () -> Unit,
    viewModel: TerminalTabsViewModel = hiltViewModel(),
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val selected by viewModel.selectedTabId.collectAsStateWithLifecycle()
    val screens by viewModel.screens.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    TerminalWindowFlags(keepScreenOn = settings?.keepScreenOn == true)
    LifecycleStartEffect(selected) {
        viewModel.setVisibleTab(selected)
        onStopOrDispose { viewModel.setVisibleTab(null) }
    }
    val tools = HostTools(LocalNavController.current)
    TerminalTabsScreen(
        tabs = tabs,
        selectedTabId = selected,
        screens = screens,
        keyBarLayout = settings?.keyBarLayout ?: KeyBarLayout.DEFAULT,
        composeEnterSends = settings?.composeEnterSends ?: true,
        actions = viewModel,
        onBack = onBack,
        onOpenTool = @DontMemoize { hostId, tool -> tools.open(hostId, tool) },
    )
}

/**
 * Open terminal tabs in a scrollable tab row (each with a close button) above the selected tab's
 * [TerminalScreen]. A tab without a screen state yet (settings still loading) shows nothing. The
 * overflow menu opens [TERMINAL_TOOLS] on the selected tab's host through [onOpenTool].
 */
@Composable
fun TerminalTabsScreen(
    tabs: List<TerminalTabItem>,
    selectedTabId: String?,
    screens: Map<String, TerminalScreenState>,
    keyBarLayout: KeyBarLayout,
    actions: TerminalTabsActions,
    onBack: () -> Unit,
    onOpenTool: (EntityId, HostTool) -> Unit,
    modifier: Modifier = Modifier,
    composeEnterSends: Boolean = true,
) {
    val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)
    val current = tabs.getOrNull(selectedIndex)
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.terminal_back))
            }
            if (current == null) {
                Text(
                    stringResource(R.string.terminal_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            } else {
                SecondaryScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    edgePadding = 0.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    tabs.forEach { tab -> TerminalTabTitle(tab, tab.id == current.id, actions) }
                }
                TabMenu(
                    onOpenTool = { tool -> onOpenTool(current.hostId, tool) },
                    onClearHistory = { actions.clearHistory(current.hostId) },
                )
            }
        }
        if (current == null) {
            NoTerminals(onBack)
        } else {
            key(current.id) {
                val state = screens[current.id]
                if (state == null) {
                    Box(Modifier.fillMaxSize())
                } else {
                    TerminalScreen(
                        state,
                        onReconnect = { actions.reconnect(current.id) },
                        keyBarLayout = keyBarLayout,
                        composeEnterSends = composeEnterSends,
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalTabTitle(
    tab: TerminalTabItem,
    selected: Boolean,
    actions: TerminalTabsActions,
) {
    // The close button sits beside the tab, not inside it, so a tap on the tab never closes it.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Tab(selected = selected, onClick = { actions.select(tab.id) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            ) {
                when (tab.state) {
                    TerminalState.Open -> Unit
                    TerminalState.Connecting, TerminalState.Reattaching ->
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = stringResource(R.string.terminal_tab_connecting),
                            modifier = Modifier.size(16.dp),
                        )
                    is TerminalState.Ended ->
                        Icon(
                            Icons.Filled.LinkOff,
                            contentDescription = stringResource(R.string.terminal_tab_ended),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                }
                Text(
                    tab.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
            }
        }
        IconButton(onClick = { actions.close(tab.id) }) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.terminal_close_tab, tab.title))
        }
    }
}

/** The host tools the terminal's menu offers for the selected tab's host. */
internal val TERMINAL_TOOLS = listOf(HostTool.FILES, HostTool.SNIPPETS)

@Composable
private fun TabMenu(
    onOpenTool: (HostTool) -> Unit,
    onClearHistory: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.terminal_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TERMINAL_TOOLS.forEach { tool ->
                DropdownMenuItem(
                    text = { Text(stringResource(tool.label)) },
                    onClick = {
                        expanded = false
                        onOpenTool(tool)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_clear_history)) },
                onClick = {
                    expanded = false
                    onClearHistory()
                },
            )
        }
    }
}

@Composable
private fun NoTerminals(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.terminal_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.terminal_empty_body), textAlign = TextAlign.Center)
        Button(onClick = onBack) { Text(stringResource(R.string.terminal_back)) }
    }
}

/**
 * Keeps the screen on while the terminal is shown, as the settings ask; undone on leaving.
 *
 * FLAG_SECURE is deliberately not touched here: [com.kitefoldlabs.bridle.ui.SecureScreen] applies
 * the "secure screen" setting to the whole window (the sessions screen shows pane previews too),
 * and clearing the flag on leaving the terminal would switch that protection off app-wide.
 */
@Composable
fun TerminalWindowFlags(keepScreenOn: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
}
