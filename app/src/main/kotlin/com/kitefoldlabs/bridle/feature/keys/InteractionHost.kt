package com.kitefoldlabs.bridle.feature.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.connect.Interaction
import com.kitefoldlabs.bridle.core.ssh.HostKeyChangedException
import com.kitefoldlabs.bridle.feature.hosts.ConfirmDialog

/*
 * Callbacks built here are @DontMemoize: they only forward to the view model, so re-creating them
 * on recomposition is cheap, and remembering them would add compiler-generated branches (one per
 * captured parameter) that no test can tell apart.
 */

/**
 * Mount once at the app root: shows the connection layer's questions (trust a new host key,
 * type a password) and the "host key changed" warning, whichever screen started the connection.
 */
@Composable
fun InteractionHost() {
    val viewModel: InteractionViewModel = hiltViewModel()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val keyChanged by viewModel.keyChanged.collectAsStateWithLifecycle()
    InteractionDialogs(pending, keyChanged, viewModel)
}

/** The dialogs for [pending] and [keyChanged]; stateless apart from the text being typed. */
@Composable
fun InteractionDialogs(
    pending: Interaction?,
    keyChanged: HostKeyChangedException?,
    actions: InteractionActions,
) {
    if (pending is Interaction.TrustHost) TrustHostDialog(pending, actions)
    // A new question (even for the same host) starts with an empty field.
    if (pending is Interaction.Password) key(pending) { PasswordDialog(pending, actions) }
    if (keyChanged != null) HostKeyChangedWarning(keyChanged, actions)
}

@Composable
private fun TrustHostDialog(
    question: Interaction.TrustHost,
    actions: InteractionActions,
) {
    val prompt = question.prompt
    AlertDialog(
        onDismissRequest = @DontMemoize { actions.trust(question, false) },
        title = { Text(stringResource(R.string.trust_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.trust_text, endpoint(prompt.host, prompt.port)))
                Text(prompt.keyType, style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(
                        prompt.fingerprintSha256,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                Text(stringResource(R.string.trust_how_to_check), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { actions.trust(question, true) },
            ) { Text(stringResource(R.string.trust_accept)) }
        },
        dismissButton = {
            TextButton(
                onClick = { actions.trust(question, false) },
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PasswordDialog(
    question: Interaction.Password,
    actions: InteractionActions,
) {
    val password = rememberTextFieldState()
    val submit = @DontMemoize { actions.password(question, password.takeChars()) }
    AlertDialog(
        onDismissRequest = @DontMemoize { actions.password(question, null) },
        title = { Text(stringResource(R.string.password_title, question.username, question.hostLabel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.password_text))
                OutlinedSecureTextField(
                    state = password,
                    label = { Text(stringResource(R.string.password_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    onKeyboardAction = { submit() },
                )
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.password_submit)) } },
        dismissButton = {
            TextButton(
                onClick = { actions.password(question, null) },
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Full-screen, hard-to-miss warning: the host's key isn't the one that was trusted. */
@Composable
private fun HostKeyChangedWarning(
    error: HostKeyChangedException,
    actions: InteractionActions,
) {
    var confirmForget by remember(error) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = @DontMemoize { actions.dismissKeyChanged() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(48.dp))
                Text(
                    stringResource(R.string.host_key_changed_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.host_key_changed_text, endpoint(error.host, error.port)))
                FingerprintBlock(stringResource(R.string.host_key_changed_old), error.expectedFingerprints)
                FingerprintBlock(
                    stringResource(R.string.host_key_changed_new, error.keyType),
                    listOf(error.actualFingerprint),
                )
                Text(stringResource(R.string.host_key_changed_advice), style = MaterialTheme.typography.bodySmall)
                Button(onClick = actions::dismissKeyChanged, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.host_key_changed_keep_blocked))
                }
                OutlinedButton(
                    onClick = { confirmForget = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.host_key_changed_forget)) }
            }
        }
    }
    if (confirmForget) {
        ConfirmDialog(
            title = stringResource(R.string.host_key_changed_forget),
            text = stringResource(R.string.host_key_changed_forget_confirm, endpoint(error.host, error.port)),
            confirmLabel = stringResource(R.string.known_hosts_forget),
            onConfirm =
                @DontMemoize {
                    confirmForget = false
                    actions.forgetOldKey()
                },
            onDismiss = { confirmForget = false },
        )
    }
}

@Composable
private fun FingerprintBlock(
    title: String,
    fingerprints: List<String>,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    SelectionContainer {
        Column {
            fingerprints.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}
