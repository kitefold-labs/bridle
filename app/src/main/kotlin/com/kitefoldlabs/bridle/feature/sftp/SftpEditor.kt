package com.kitefoldlabs.bridle.feature.sftp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.ui.theme.MonoFamily

/** Full-screen editor for a small remote text file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SftpEditor(
    editor: EditorState,
    viewModel: SftpViewModel,
    snackbar: SnackbarHostState,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val close = @DontMemoize { if (editor.dirty) confirmDiscard = true else viewModel.closeEditor() }
    BackHandler(onBack = close)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(editor.name + if (editor.dirty) " •" else "") },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.sftp_close_editor))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.save() },
                        enabled = editor.editable && editor.dirty && !editor.saving,
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.sftp_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (editor.binary) BinaryWarning(editor.editable, viewModel::editAnyway)
            TextField(
                value = editor.text,
                onValueChange = viewModel::editText,
                readOnly = !editor.editable,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                label = { Text(editor.path) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    if (editor.conflict) ConflictDialog(viewModel)
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.sftp_discard_title)) },
            text = { Text(stringResource(R.string.sftp_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.closeEditor()
                }) { Text(stringResource(R.string.sftp_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.sftp_keep_editing)) }
            },
        )
    }
}

@Composable
private fun BinaryWarning(
    editable: Boolean,
    onEditAnyway: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.sftp_binary_warning), color = MaterialTheme.colorScheme.onErrorContainer)
            if (!editable) {
                TextButton(onClick = onEditAnyway) { Text(stringResource(R.string.sftp_edit_anyway)) }
            }
        }
    }
}

@Composable
private fun ConflictDialog(viewModel: SftpViewModel) {
    AlertDialog(
        onDismissRequest = @DontMemoize { viewModel.dismissConflict() },
        title = { Text(stringResource(R.string.sftp_conflict_title)) },
        text = { Text(stringResource(R.string.sftp_conflict_message)) },
        confirmButton = {
            TextButton(onClick = { viewModel.save(overwrite = true) }) { Text(stringResource(R.string.sftp_overwrite)) }
        },
        dismissButton = {
            Column {
                TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.sftp_reload)) }
                TextButton(onClick = viewModel::dismissConflict) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
