package com.kitefoldlabs.bridle.feature.keys

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.feature.hosts.BackButton
import com.kitefoldlabs.bridle.feature.hosts.rememberDocumentPicker

@Composable
internal fun KeyImportRoute(
    viewModel: KeyImportViewModel,
    onBack: () -> Unit,
    onCreated: (EntityId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    KeyImportScreen(state, viewModel, onBack, onCreated)
}

/** Imports a private key from pasted text or a picked file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyImportScreen(
    state: KeyImportUiState,
    actions: KeyImportActions,
    onBack: () -> Unit,
    onCreated: (EntityId) -> Unit,
) {
    LaunchedEffect(state.createdId, block = @DontMemoize { state.createdId?.let(onCreated) })
    val pick = rememberDocumentPicker(@DontMemoize { actions.loadFile(it) })
    Scaffold(
        topBar = {
            TopAppBar(navigationIcon = { BackButton(onBack) }, title = { Text(stringResource(R.string.keys_import)) })
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.key_import_intro))
            LabelField(state.label, state.labelError, actions::setLabel)
            OutlinedTextField(
                value = state.keyText,
                onValueChange = actions::setKeyText,
                label = { Text(stringResource(R.string.key_import_paste)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            AuthSettingField(state.auth, actions::setAuth)
            state.error?.let {
                Text(stringResource(it.message(), state.unsupportedType), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = pick) { Text(stringResource(R.string.action_choose_file)) }
                Button(
                    onClick = actions::submit,
                    enabled = !state.busy,
                ) { Text(stringResource(R.string.key_import_button)) }
            }
        }
    }
    if (state.askPassphrase) PassphraseDialog(state.wrongPassphrase, actions)
}

@Composable
private fun PassphraseDialog(
    wrong: Boolean,
    actions: KeyImportActions,
) {
    val passphrase = rememberTextFieldState()
    AlertDialog(
        onDismissRequest = @DontMemoize { actions.cancelPassphrase() },
        title = { Text(stringResource(R.string.key_import_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.key_import_passphrase_text))
                OutlinedSecureTextField(
                    state = passphrase,
                    label = { Text(stringResource(R.string.key_import_passphrase)) },
                    isError = wrong,
                    supportingText =
                        if (wrong) {
                            { Text(stringResource(R.string.key_import_wrong_passphrase)) }
                        } else {
                            null
                        },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.submitPassphrase(passphrase.takeChars()) }) {
                Text(stringResource(R.string.key_import_button))
            }
        },
        dismissButton = {
            TextButton(
                onClick = actions::cancelPassphrase,
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Copies the field's text into a new array (never a String) and clears the field. */
internal fun TextFieldState.takeChars(): CharArray {
    val chars = CharArray(text.length) { text[it] }
    clearText()
    return chars
}

/** The message for an import error; only [KeyImportError.UNSUPPORTED_TYPE]'s uses its argument (the key type). */
@StringRes
internal fun KeyImportError.message(): Int =
    when (this) {
        KeyImportError.EMPTY -> R.string.key_import_error_empty
        KeyImportError.UNSUPPORTED_TYPE -> R.string.key_import_error_type
        KeyImportError.UNSUPPORTED_FORMAT -> R.string.key_import_error_format
        KeyImportError.MALFORMED -> R.string.key_import_error_malformed
        KeyImportError.VAULT_UNAVAILABLE -> R.string.key_import_error_vault
        KeyImportError.TOO_LARGE -> R.string.key_import_error_too_large
        KeyImportError.READ_FAILED -> R.string.key_import_error_read
    }
