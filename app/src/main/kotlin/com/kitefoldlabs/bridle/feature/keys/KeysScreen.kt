package com.kitefoldlabs.bridle.feature.keys

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm

@Composable
internal fun KeysRoute(
    viewModel: KeysViewModel,
    navigation: KeysNavigation,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    KeysScreen(state, navigation)
}

/** The user's SSH identities: hardware (Keystore) and imported software keys. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    state: KeysUiState,
    navigation: KeysNavigation,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.keys_title)) },
                actions = {
                    IconButton(onClick = navigation.onImport) {
                        Icon(Icons.Filled.FileUpload, contentDescription = stringResource(R.string.keys_import))
                    }
                    IconButton(onClick = navigation.onKnownHosts) {
                        Icon(Icons.Filled.VerifiedUser, contentDescription = stringResource(R.string.known_hosts_title))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = navigation.onGenerate) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.keys_generate))
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (!state.loading && state.keys.isEmpty()) {
                Text(
                    stringResource(R.string.keys_empty),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.keys, key = { it.identity.id }) { row -> KeyRowItem(row, navigation.onOpen) }
                }
            }
        }
    }
}

@Composable
private fun KeyRowItem(
    row: KeyRow,
    onOpen: (EntityId) -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(if (row.isHardware) Icons.Filled.Memory else Icons.Filled.Key, contentDescription = null)
        },
        headlineContent = { Text(row.identity.alias) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyBadge(row.isHardware)
                    Text(stringResource(row.identity.algorithm.label()), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    row.fingerprint ?: stringResource(R.string.keys_fingerprint_unknown),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        modifier =
            Modifier.clickable(
                onClickLabel = stringResource(R.string.keys_open),
                onClick = @DontMemoize { onOpen(row.identity.id) },
            ),
    )
}

/** "Hardware key" (Keystore, can't leave the device) or "Software key" (imported, encrypted). */
@Composable
internal fun KeyBadge(isHardware: Boolean) {
    val (text, color) =
        if (isHardware) {
            R.string.keys_badge_hardware to MaterialTheme.colorScheme.primaryContainer
        } else {
            R.string.keys_badge_software to MaterialTheme.colorScheme.tertiaryContainer
        }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Display name of an algorithm. */
@StringRes
internal fun KeyAlgorithm.label(): Int =
    when (this) {
        KeyAlgorithm.ECDSA_P256 -> R.string.algorithm_ecdsa_p256
        KeyAlgorithm.ECDSA_P384 -> R.string.algorithm_ecdsa_p384
        KeyAlgorithm.ECDSA_P521 -> R.string.algorithm_ecdsa_p521
        KeyAlgorithm.ED25519 -> R.string.algorithm_ed25519
        KeyAlgorithm.RSA -> R.string.algorithm_rsa
    }
