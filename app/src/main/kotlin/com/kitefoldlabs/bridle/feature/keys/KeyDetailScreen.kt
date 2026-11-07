package com.kitefoldlabs.bridle.feature.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.ssh.KeyInstallResult
import com.kitefoldlabs.bridle.feature.hosts.BackButton
import com.kitefoldlabs.bridle.feature.hosts.Choice
import com.kitefoldlabs.bridle.feature.hosts.ChoiceField
import com.kitefoldlabs.bridle.feature.hosts.ConfirmDialog
import com.kitefoldlabs.bridle.ui.theme.MonoFamily

/*
 * Callbacks built here are @DontMemoize: they only forward to the view model, so re-creating them
 * on recomposition is cheap, and remembering them would add compiler-generated branches (one per
 * captured parameter) that no test can tell apart.
 */

@Composable
internal fun KeyDetailRoute(
    viewModel: KeyDetailViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    KeyDetailScreen(state, viewModel, onBack)
}

/** One key: its public key with copy and share, "Install on host", and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyDetailScreen(
    state: KeyDetailUiState,
    actions: KeyDetailActions,
    onBack: () -> Unit,
) {
    LaunchedEffect(state.gone, block = @DontMemoize { if (state.gone) onBack() })
    val identity = state.identity
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text(identity?.alias.orEmpty()) },
                actions = {
                    IconButton(onClick = actions::requestDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                },
            )
        },
    ) { padding ->
        if (identity != null) {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KeySummary(identity, state.fingerprint)
                PublicKeyCard(identity.publicKeyOpenSsh)
                InstallSection(state, actions)
            }
        }
    }
    if (state.confirmDelete && identity != null) {
        ConfirmDialog(
            title = stringResource(R.string.key_delete_title, identity.alias),
            text =
                stringResource(
                    if (identity.source.isHardware()) R.string.key_delete_hardware else R.string.key_delete_software,
                ),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = @DontMemoize { actions.confirmDelete() },
            onDismiss = @DontMemoize { actions.cancelDelete() },
        )
    }
    val install = state.install
    if (install is InstallState.Done) InstallResultDialog(install, @DontMemoize { actions.dismissInstall() })
}

private fun KeySource.isHardware() = this == KeySource.KEYSTORE

@Composable
private fun KeySummary(
    identity: Identity,
    fingerprint: String?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyBadge(identity.source.isHardware())
        Text(stringResource(identity.algorithm.label()))
    }
    Text(
        stringResource(
            if (identity.source.isHardware()) R.string.key_hardware_explained else R.string.key_software_explained,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(stringResource(R.string.key_fingerprint), style = MaterialTheme.typography.titleSmall)
    SelectionContainer {
        Text(
            fingerprint ?: stringResource(R.string.keys_fingerprint_unknown),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
        )
    }
}

@Composable
private fun PublicKeyCard(publicKey: String) {
    val context = LocalContext.current
    val label = stringResource(R.string.key_public_key)
    val chooserTitle = stringResource(R.string.key_share_title)
    Text(label, style = MaterialTheme.typography.titleSmall)
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                publicKey,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { copyToClipboard(context, label, publicKey) }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Text(stringResource(R.string.action_copy), Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = { sharePublicKey(context, publicKey, chooserTitle) }) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Text(stringResource(R.string.action_share), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun InstallSection(
    state: KeyDetailUiState,
    actions: KeyDetailActions,
) {
    Text(stringResource(R.string.key_install_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.key_install_text), style = MaterialTheme.typography.bodySmall)
    val install = state.install
    if (install is InstallState.Running) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.key_install_running, install.hostLabel),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    if (state.hosts.isEmpty()) {
        Text(stringResource(R.string.key_install_no_hosts))
        return
    }
    var hostId by rememberSaveable { mutableStateOf<EntityId?>(null) }
    ChoiceField(
        label = stringResource(R.string.key_install_host),
        selected = hostId,
        choices = state.hosts.map { Choice<EntityId?>(it.id, it.label) },
        onSelect = { hostId = it },
    )
    Button(onClick = @DontMemoize { hostId?.let(actions::install) }, enabled = hostId != null) {
        Text(stringResource(R.string.key_install_button))
    }
}

@Composable
private fun InstallResultDialog(
    done: InstallState.Done,
    onDismiss: () -> Unit,
) {
    val text = stringResource(done.outcome.message(), done.hostLabel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_install_title)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

/** What to tell the user about an install on a host (the message takes the host's name). */
@StringRes
internal fun InstallOutcome.message(): Int =
    when (this) {
        is InstallOutcome.Installed ->
            if (result == KeyInstallResult.ADDED) R.string.key_install_added else R.string.key_install_present
        is InstallOutcome.Failed -> reason.message()
    }

private fun InstallFailure.message(): Int =
    when (this) {
        InstallFailure.AUTH_FAILED -> R.string.key_install_error_auth
        InstallFailure.UNREACHABLE -> R.string.key_install_error_unreachable
        InstallFailure.HOST_NOT_TRUSTED -> R.string.key_install_error_not_trusted
        InstallFailure.HOST_KEY_CHANGED -> R.string.key_install_error_key_changed
        InstallFailure.REMOTE_FAILED -> R.string.key_install_error_remote
        InstallFailure.HOST_MISSING -> R.string.key_install_error_missing
        InstallFailure.OTHER -> R.string.key_install_error_other
    }

/** Copies a public key; public keys aren't secret, so the clip isn't marked sensitive. */
internal fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Opens the share sheet with [publicKey] as plain text. */
internal fun sharePublicKey(
    context: Context,
    publicKey: String,
    chooserTitle: String,
) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, publicKey)
    context.startActivity(Intent.createChooser(send, chooserTitle))
}
