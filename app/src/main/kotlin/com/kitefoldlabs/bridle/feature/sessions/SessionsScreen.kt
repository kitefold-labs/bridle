package com.kitefoldlabs.bridle.feature.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.session.HostConnectionState

/** The SESSIONS destination, wired to [SessionsViewModel]. */
@Composable
fun SessionsRoute(viewModel: SessionsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SessionsScreen(state, viewModel)
}

/**
 * Per host: open terminals, the connection, what the host offers, its Launch Profiles and its tmux
 * sessions with Claude status. The profile editor replaces the list while open.
 */
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    actions: SessionsActions,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val editor = state.editor
    if (editor != null) {
        ProfileEditor(editor, actions, modifier)
        return
    }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(resources.getString(message.text, message.arg))
        actions.messageShown()
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, modifier = modifier) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.hosts.isEmpty()) {
                item { Text(stringResource(R.string.sessions_no_hosts)) }
            } else {
                hostContent(state, actions, now)
            }
        }
    }
    state.dialog?.let { SessionsDialogContent(it, actions) }
}

private fun LazyListScope.hostContent(
    state: SessionsUiState,
    actions: SessionsActions,
    now: Long,
) {
    item {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.hosts, key = { it.id }) { host ->
                FilterChip(
                    selected = host.id == state.selectedHostId,
                    onClick = { actions.selectHost(host.id) },
                    label = { Text(host.label) },
                )
            }
        }
    }
    item { NotificationPermissionCard(state.settings.notificationsEnabled) }
    if (state.openTabs > 0) item { OpenTerminalsCard(state.openTabs, actions::showTerminals) }
    item { ConnectionStatus(state.status, state.busy, actions) }
    state.status.probe?.let { probe ->
        item { HostCapabilityCard(probe, state.settings.useHelperByDefault, actions) }
    }
    item {
        SectionHeader(R.string.sessions_profiles) {
            IconButton(onClick = actions::newProfile) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sessions_new_profile))
            }
        }
    }
    if (state.profiles.isEmpty()) item { Text(stringResource(R.string.sessions_no_profiles)) }
    val running =
        state.status.sessions
            .map { it.session.name }
            .toSet()
    items(state.profiles, key = { "profile-${it.id}" }) { profile ->
        ProfileCard(profile, running = profile.sessionName in running, actions = actions)
    }
    item {
        SectionHeader(R.string.sessions_tmux_sessions) {
            IconButton(onClick = actions::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.sessions_refresh))
            }
        }
    }
    val withProfile = state.profiles.map { it.sessionName }.toSet()
    when {
        !state.status.loaded -> item { Text(stringResource(R.string.sessions_loading)) }
        state.status.sessions.isEmpty() -> item { Text(stringResource(R.string.sessions_none)) }
        else ->
            items(state.status.sessions, key = { "session-${it.session.name}" }) { summary ->
                val name = summary.session.name
                SessionCard(summary, state.status.previews[name], name in withProfile, now, actions)
            }
    }
}

@Composable
private fun SectionHeader(
    title: Int,
    action: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun OpenTerminalsCard(
    count: Int,
    onShow: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
            Text(
                pluralStringResource(R.plurals.sessions_open_terminals, count, count),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onShow) { Text(stringResource(R.string.sessions_show)) }
        }
    }
}

/** Connecting, reconnecting or failed (with Retry), the last read error, and progress while busy. */
@Composable
private fun ConnectionStatus(
    status: HostStatus,
    busy: Boolean,
    actions: SessionsActions,
) {
    val connection = status.connection
    val error = status.error
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    when {
        connection is HostConnectionState.Failed ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.sessions_failed,
                        connection.error.message ?: connection.error.javaClass.simpleName,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions::reconnect) { Text(stringResource(R.string.sessions_retry)) }
            }
        connection is HostConnectionState.Reconnecting ->
            Text(stringResource(R.string.sessions_reconnecting, connection.attempt))
        connection !is HostConnectionState.Connected -> Text(stringResource(R.string.sessions_connecting))
        error != null ->
            Text(
                stringResource(R.string.sessions_read_error, error),
                color = MaterialTheme.colorScheme.error,
            )
    }
}
