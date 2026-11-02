package com.kitefoldlabs.bridle.feature.sftp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.ssh.RemoteFile

/** What a file-row dialog is acting on. */
internal sealed interface SftpDialog {
    data object NewFolder : SftpDialog

    data class Rename(
        val file: RemoteFile,
    ) : SftpDialog

    data class Delete(
        val file: RemoteFile,
    ) : SftpDialog

    data class Permissions(
        val file: RemoteFile,
    ) : SftpDialog

    data object Transfers : SftpDialog
}

@Composable
internal fun SftpDialogs(
    dialog: SftpDialog,
    viewModel: SftpViewModel,
    transfers: List<Transfer>,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        SftpDialog.NewFolder ->
            TextInputDialog(
                R.string.sftp_new_folder,
                R.string.sftp_name,
                "",
                onDismiss,
            ) @DontMemoize { viewModel.mkdir(it) }
        is SftpDialog.Rename ->
            TextInputDialog(R.string.sftp_rename, R.string.sftp_name, dialog.file.name, onDismiss) @DontMemoize {
                viewModel.rename(dialog.file, it)
            }
        is SftpDialog.Permissions ->
            TextInputDialog(
                title = R.string.sftp_permissions,
                label = R.string.sftp_permissions_octal,
                initial = SftpListing.octal(dialog.file.permissions),
                onDismiss = onDismiss,
                keyboardType = KeyboardType.Number,
            ) @DontMemoize { viewModel.chmod(dialog.file, it) }
        is SftpDialog.Delete -> DeleteDialog(dialog, viewModel, onDismiss)
        SftpDialog.Transfers -> TransfersDialog(transfers, viewModel, onDismiss)
    }
}

@Composable
private fun DeleteDialog(
    dialog: SftpDialog.Delete,
    viewModel: SftpViewModel,
    onDismiss: () -> Unit,
) {
    val file = dialog.file
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sftp_delete_title, file.name)) },
        text = {
            Text(
                stringResource(
                    if (file.isDirectory) R.string.sftp_delete_folder_message else R.string.sftp_delete_file_message,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                viewModel.delete(file)
            }) { Text(stringResource(R.string.sftp_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TransfersDialog(
    transfers: List<Transfer>,
    viewModel: SftpViewModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sftp_transfers)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(transfers, key = { it.id }) { transfer -> TransferRow(transfer, viewModel::cancelTransfer) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = {
            TextButton(onClick = viewModel::clearTransfers) { Text(stringResource(R.string.sftp_clear_finished)) }
        },
    )
}

@Composable
private fun TransferRow(
    transfer: Transfer,
    onCancel: (Long) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val arrow = if (transfer.direction == TransferDirection.UPLOAD) "↑" else "↓"
            Text("$arrow ${transfer.name}", style = MaterialTheme.typography.bodyLarge)
            Text(transferStatus(transfer), style = MaterialTheme.typography.bodySmall)
            if (transfer.status == TransferStatus.RUNNING) {
                val fraction = transfer.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (transfer.isActive) {
            IconButton(onClick = @DontMemoize { onCancel(transfer.id) }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.sftp_cancel_transfer, transfer.name),
                )
            }
        }
    }
}

@Composable
private fun transferStatus(transfer: Transfer): String =
    when (transfer.status) {
        TransferStatus.QUEUED -> stringResource(R.string.sftp_transfer_queued)
        TransferStatus.RUNNING -> SftpListing.formatSize(transfer.transferred)
        TransferStatus.DONE -> stringResource(R.string.sftp_transfer_done)
        TransferStatus.CANCELLED -> stringResource(R.string.sftp_transfer_cancelled)
        TransferStatus.FAILED -> stringResource(R.string.sftp_failed, transfer.error.orEmpty())
    }

/** A dialog with one text field; [onConfirm] gets the text. */
@Composable
internal fun TextInputDialog(
    title: Int,
    label: Int,
    initial: String,
    onDismiss: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm(text)
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
