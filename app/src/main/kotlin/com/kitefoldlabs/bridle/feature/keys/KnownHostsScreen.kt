package com.kitefoldlabs.bridle.feature.keys

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.KnownHost
import com.kitefoldlabs.bridle.feature.hosts.BackButton
import com.kitefoldlabs.bridle.feature.hosts.ConfirmDialog

/**
 * The trusted host keys screen, with its own view model. Public so Settings can show it too;
 * [onBack] closes it.
 */
@Composable
fun KnownHostsRoute(onBack: () -> Unit) {
    val viewModel: KnownHostsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    KnownHostsScreen(state, viewModel, onBack)
}

/** Lists trusted host keys with their fingerprints; each can be forgotten. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownHostsScreen(
    state: KnownHostsUiState,
    actions: KnownHostsActions,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text(stringResource(R.string.known_hosts_title)) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (!state.loading && state.hosts.isEmpty()) {
                Text(
                    stringResource(R.string.known_hosts_empty),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.hosts, key = { "${it.host}:${it.port}:${it.keyType}" }) { host ->
                        KnownHostRow(host, actions)
                    }
                }
            }
        }
    }
    state.pendingForget?.let { host ->
        ConfirmDialog(
            title = stringResource(R.string.known_hosts_forget_title, endpoint(host)),
            text = stringResource(R.string.known_hosts_forget_text),
            confirmLabel = stringResource(R.string.known_hosts_forget),
            onConfirm = @DontMemoize { actions.confirmForget() },
            onDismiss = @DontMemoize { actions.cancelForget() },
        )
    }
}

@Composable
private fun KnownHostRow(
    host: KnownHost,
    actions: KnownHostsActions,
) {
    ListItem(
        headlineContent = { Text(endpoint(host)) },
        supportingContent = {
            Column {
                Text(host.keyType, style = MaterialTheme.typography.bodySmall)
                Text(
                    host.fingerprintSha256,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        trailingContent = {
            IconButton(onClick = { actions.requestForget(host) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.known_hosts_forget_host, endpoint(host)),
                )
            }
        },
    )
}

/** `host` or `host:port` (IPv6 hosts in brackets when a port is shown). */
internal fun endpoint(host: KnownHost): String =
    when {
        host.port == DEFAULT_PORT -> host.host
        ':' in host.host -> "[${host.host}]:${host.port}"
        else -> "${host.host}:${host.port}"
    }

internal fun endpoint(
    hostname: String,
    port: Int,
): String = endpoint(KnownHost(hostname, port, "", "", ""))

private const val DEFAULT_PORT = 22
