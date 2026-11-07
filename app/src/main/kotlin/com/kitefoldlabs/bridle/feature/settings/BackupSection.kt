package com.kitefoldlabs.bridle.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.backup.BackupCrypto
import com.kitefoldlabs.bridle.ui.theme.MonoFamily

/** The Backup section of settings: export and import rows, and their dialogs. */
@Composable
internal fun BackupSection(viewModel: BackupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) @DontMemoize { uri ->
            viewModel.exportTo(uri)
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) @DontMemoize { uri ->
            viewModel.importFrom(uri)
        }
    LaunchedEffect(state.pickExportFile) @DontMemoize {
        if (state.pickExportFile) {
            viewModel.exportPickerShown()
            save.launch(state.suggestedName)
        }
    }
    Column {
        SectionHeader(R.string.settings_backup)
        ClickRow(
            stringResource(R.string.backup_export),
            @DontMemoize { viewModel.startExport() },
            stringResource(R.string.backup_export_summary),
        )
        ClickRow(stringResource(R.string.backup_import), {
            open.launch(arrayOf(MIME_ANY))
        }, stringResource(R.string.backup_import_summary))
        if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (state.exportDialog) ExportDialog(state.passphraseProblem, viewModel)
    if (state.importDialog) ImportPassphraseDialog(state, viewModel)
    state.review?.let { ImportReviewDialog(it, state.working, viewModel) }
    val problem = state.importProblem
    if (!state.importDialog && problem != null) {
        MessageDialog(
            stringResource(R.string.backup_import_failed),
            stringResource(importProblemText(problem)),
            @DontMemoize { viewModel.dismissResult() },
        )
    }
    state.result?.let { ResultDialog(it, @DontMemoize { viewModel.dismissResult() }) }
}

@Composable
private fun ExportDialog(
    problem: PassphraseProblem?,
    viewModel: BackupViewModel,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = @DontMemoize { viewModel.cancelExport() },
        title = { Text(stringResource(R.string.backup_export)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_export_message, BackupCrypto.MIN_PASSPHRASE_LENGTH))
                PassphraseField(passphrase, R.string.backup_passphrase, problem == PassphraseProblem.TOO_SHORT) {
                    passphrase =
                        it
                }
                PassphraseField(confirm, R.string.backup_passphrase_confirm, problem == PassphraseProblem.MISMATCH) {
                    confirm =
                        it
                }
                problem?.let {
                    val text =
                        when (it) {
                            PassphraseProblem.TOO_SHORT ->
                                stringResource(R.string.backup_passphrase_too_short, BackupCrypto.MIN_PASSPHRASE_LENGTH)
                            PassphraseProblem.MISMATCH -> stringResource(R.string.backup_passphrase_mismatch)
                        }
                    Text(text, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.submitExportPassphrase(passphrase.toCharArray(), confirm.toCharArray())
            }) { Text(stringResource(R.string.backup_choose_file)) }
        },
        dismissButton = {
            TextButton(
                onClick = viewModel::cancelExport,
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ImportPassphraseDialog(
    state: BackupUiState,
    viewModel: BackupViewModel,
) {
    var passphrase by remember { mutableStateOf("") }
    val problem = state.importProblem
    AlertDialog(
        onDismissRequest = @DontMemoize { viewModel.cancelImport() },
        title = { Text(stringResource(R.string.backup_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PassphraseField(passphrase, R.string.backup_passphrase, problem != null) { passphrase = it }
                if (problem !=
                    null
                ) {
                    Text(stringResource(importProblemText(problem)), color = MaterialTheme.colorScheme.error)
                }
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.submitImportPassphrase(passphrase.toCharArray()) },
                enabled = !state.working,
            ) {
                Text(stringResource(R.string.backup_decrypt))
            }
        },
        dismissButton = {
            TextButton(
                onClick = viewModel::cancelImport,
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PassphraseField(
    value: String,
    label: Int,
    error: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = error,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
}

@Composable
internal fun ImportReviewDialog(
    review: ImportReview,
    working: Boolean,
    viewModel: BackupViewModel,
) {
    val plan = review.plan
    AlertDialog(
        onDismissRequest = @DontMemoize { viewModel.cancelImport() },
        title = { Text(stringResource(R.string.backup_review_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_review_add), style = MaterialTheme.typography.labelLarge)
                listOf(
                    R.string.backup_count_hosts to plan.hosts.size,
                    R.string.backup_count_groups to plan.groups.size,
                    R.string.backup_count_profiles to plan.launchProfiles.size,
                    R.string.backup_count_snippets to plan.snippets.size,
                    R.string.backup_count_forwards to plan.portForwards.size,
                    R.string.backup_count_known_hosts to plan.knownHosts.size,
                ).forEach { (label, count) -> Text(stringResource(label, count)) }
                if (review.duplicates > 0) {
                    Text(pluralStringResource(R.plurals.backup_review_duplicates, review.duplicates, review.duplicates))
                }
                review.conflicts.forEach { conflict ->
                    Text(
                        stringResource(
                            R.string.backup_review_conflict,
                            conflict.label,
                            conflict.existingFingerprint,
                            conflict.backupFingerprint,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = MonoFamily,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                review.missingKeys.forEach { missing ->
                    Text(
                        stringResource(
                            R.string.backup_review_missing_key,
                            missing.label,
                            missing.usedBy.joinToString(),
                        ),
                    )
                }
                review.skipped.forEach { issue -> Text(stringResource(R.string.backup_review_skipped, issue.label)) }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::applyImport, enabled = !working && !plan.isEmpty) {
                Text(stringResource(R.string.backup_import_apply))
            }
        },
        dismissButton = {
            TextButton(
                onClick = viewModel::cancelImport,
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ResultDialog(
    result: BackupResult,
    onDismiss: () -> Unit,
) {
    val (title, text) =
        when (result) {
            BackupResult.Exported ->
                stringResource(R.string.backup_exported) to
                    stringResource(R.string.backup_exported_message)
            is BackupResult.Imported ->
                stringResource(R.string.backup_imported) to
                    pluralStringResource(R.plurals.backup_imported_message, result.total, result.total, result.hosts)
            is BackupResult.Failed -> stringResource(R.string.backup_failed) to result.detail
            BackupResult.ExportInterrupted ->
                stringResource(R.string.backup_failed) to stringResource(R.string.backup_export_interrupted)
            BackupResult.NotEnoughMemory ->
                stringResource(R.string.backup_failed) to stringResource(R.string.backup_not_enough_memory)
        }
    MessageDialog(title, text, onDismiss)
}

@Composable
private fun MessageDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

private fun importProblemText(problem: ImportProblem): Int =
    when (problem) {
        ImportProblem.WRONG_PASSPHRASE -> R.string.backup_wrong_passphrase
        ImportProblem.NEWER_VERSION -> R.string.backup_newer_version
        ImportProblem.NOT_A_BACKUP -> R.string.backup_not_a_backup
        ImportProblem.TOO_LARGE -> R.string.backup_too_large
        ImportProblem.UNREADABLE -> R.string.backup_unreadable
        ImportProblem.NOT_ENOUGH_MEMORY -> R.string.backup_not_enough_memory
    }

private const val BACKUP_MIME = "application/octet-stream"
private const val MIME_ANY = "*/*"
