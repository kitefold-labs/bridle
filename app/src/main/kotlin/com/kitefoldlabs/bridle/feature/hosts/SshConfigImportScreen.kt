package com.kitefoldlabs.bridle.feature.hosts

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R

/*
 * Callbacks built here are @DontMemoize: they only forward to the view model, so re-creating them
 * on recomposition is cheap, and remembering them would add compiler-generated branches (one per
 * captured parameter) that no test can tell apart.
 */

@Composable
internal fun SshConfigImportRoute(
    viewModel: SshConfigImportViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SshConfigImportScreen(state, viewModel, onDone)
}

/** Remembers a Storage Access Framework picker for any document; calls [onPicked] with its URI. */
@Composable
internal fun rememberDocumentPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
            onResult = @DontMemoize { uri -> if (uri != null) onPicked(uri) },
        )
    return { launcher.launch(arrayOf("*/*")) }
}

/** Paste or pick an `~/.ssh/config`, review what would be imported, then import it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshConfigImportScreen(
    state: SshImportUiState,
    actions: SshImportActions,
    onDone: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onDone) },
                title = { Text(stringResource(R.string.ssh_import_title)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val imported = state.imported
            val plan = state.plan
            when {
                imported != null -> ImportDone(imported, onDone)
                plan != null -> ImportReview(state, plan, actions)
                else -> ImportInput(state, actions)
            }
        }
    }
}

@Composable
private fun ImportInput(
    state: SshImportUiState,
    actions: SshImportActions,
) {
    val pick = rememberDocumentPicker(@DontMemoize { actions.loadFile(it) })
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.ssh_import_intro))
        OutlinedTextField(
            value = state.text,
            onValueChange = @DontMemoize { actions.setText(it) },
            label = { Text(stringResource(R.string.ssh_import_paste)) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        DefaultUsernameField(state, actions)
        state.error?.let { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error) }
        Warnings(state.warnings)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = pick) { Text(stringResource(R.string.action_choose_file)) }
            Button(onClick = @DontMemoize { actions.parse() }, enabled = state.text.isNotBlank() && !state.busy) {
                Text(stringResource(R.string.ssh_import_preview))
            }
        }
    }
}

@Composable
private fun DefaultUsernameField(
    state: SshImportUiState,
    actions: SshImportActions,
) {
    OutlinedTextField(
        value = state.defaultUsername,
        onValueChange = @DontMemoize { actions.setDefaultUsername(it) },
        label = { Text(stringResource(R.string.ssh_import_default_user)) },
        supportingText = { Text(stringResource(R.string.ssh_import_default_user_help)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The parser's notes, at most [MAX_WARNINGS] of them: a hostile config can produce thousands. */
@Composable
private fun Warnings(warnings: List<String>) {
    if (warnings.isEmpty()) return
    Text(stringResource(R.string.ssh_import_warnings), style = MaterialTheme.typography.titleSmall)
    warnings.take(MAX_WARNINGS).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    val more = warnings.size - MAX_WARNINGS
    if (more > 0) {
        Text(
            pluralStringResource(R.plurals.ssh_import_more_warnings, more, more),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal const val MAX_WARNINGS = 20

@Composable
private fun ImportReview(
    state: SshImportUiState,
    plan: ImportPlan,
    actions: SshImportActions,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        content = @DontMemoize { reviewItems(state, plan, actions) },
    )
}

private fun LazyListScope.reviewItems(
    state: SshImportUiState,
    plan: ImportPlan,
    actions: SshImportActions,
) {
    item { DefaultUsernameField(state, actions) }
    state.error?.let { error ->
        item { Text(stringResource(error.message()), color = MaterialTheme.colorScheme.error) }
    }
    items(plan.hosts.filter { it.fromConfig }, key = { it.key }) { planned ->
        PlannedRow(planned, planned.key in state.selected, actions)
    }
    item { Warnings(state.warnings) }
    item {
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = actions::edit) { Text(stringResource(R.string.ssh_import_edit)) }
            Button(onClick = actions::import, enabled = state.selected.isNotEmpty() && !state.busy) {
                Text(pluralStringResource(R.plurals.ssh_import_button, state.selected.size, state.selected.size))
            }
        }
    }
}

@Composable
private fun PlannedRow(
    planned: PlannedHost,
    selected: Boolean,
    actions: SshImportActions,
) {
    ListItem(
        leadingContent = { Checkbox(checked = selected, onCheckedChange = null, enabled = planned.importable) },
        headlineContent = { Text(planned.key) },
        supportingContent = {
            Column {
                Text(planned.host.displayTarget)
                planned.notes.forEach { note ->
                    Text(
                        stringResource(note.message()),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (planned.importable) LocalContentColor.current else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        modifier =
            Modifier.heightIn(min = MinTouchTarget).toggleable(
                value = selected,
                enabled = planned.importable,
                role = Role.Checkbox,
                onValueChange = @DontMemoize { actions.toggle(planned.key) },
            ),
    )
}

@Composable
private fun ImportDone(
    count: Int,
    onDone: () -> Unit,
) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            pluralStringResource(R.plurals.ssh_import_done, count, count),
            style = MaterialTheme.typography.titleMedium,
        )
        Button(onClick = onDone) { Text(stringResource(R.string.action_done)) }
    }
}

private fun SshImportError.message(): Int =
    when (this) {
        SshImportError.TOO_LARGE -> R.string.ssh_import_error_too_large
        SshImportError.READ_FAILED -> R.string.ssh_import_error_read
        SshImportError.NOTHING_FOUND -> R.string.ssh_import_error_nothing
        SshImportError.SAVE_FAILED -> R.string.ssh_import_error_save
    }

private fun ImportNote.message(): Int =
    when (this) {
        ImportNote.EXISTS -> R.string.ssh_import_note_exists
        ImportNote.NO_USERNAME -> R.string.ssh_import_note_no_user
        ImportNote.INVALID -> R.string.ssh_import_note_invalid
        ImportNote.IDENTITY_FILE_IGNORED -> R.string.ssh_import_note_identity_file
        ImportNote.JUMP_UNRESOLVED -> R.string.ssh_import_note_jump_unresolved
        ImportNote.JUMP_CHAIN_SHORTENED -> R.string.ssh_import_note_jump_shortened
        ImportNote.JUMP_LOOP -> R.string.ssh_import_note_jump_loop
    }
