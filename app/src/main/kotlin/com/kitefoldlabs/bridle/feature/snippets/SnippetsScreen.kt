package com.kitefoldlabs.bridle.feature.snippets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.Snippet
import com.kitefoldlabs.bridle.core.model.SnippetRunMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(
    viewModel: SnippetsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Snippet?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice?.let { stringResource(noticeText(it)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.notice) @DontMemoize {
        if (notice != null) {
            viewModel.consumeNotice()
            // Not in this effect: consuming the notice restarts it, which would dismiss the snackbar.
            scope.launch { snackbar.showSnackbar(notice) }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.snippets_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::create) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.snippets_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (state.loaded && state.snippets.isEmpty()) {
                item {
                    Text(stringResource(R.string.snippets_empty), modifier = Modifier.padding(16.dp))
                }
            }
            items(state.snippets, key = { it.id }) { snippet ->
                SnippetRow(snippet, viewModel, onDelete = { deleting = snippet })
            }
        }
    }
    state.draft?.let { SnippetEditorDialog(it, viewModel) }
    state.run?.let { SnippetRunDialog(it, viewModel) }
    deleting?.let { snippet ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.snippets_delete_title, snippet.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(snippet)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleting = null },
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun noticeText(notice: SnippetNotice): Int =
    when (notice) {
        SnippetNotice.PASTED -> R.string.snippets_pasted
        SnippetNotice.NO_TERMINAL -> R.string.snippets_no_terminal
        SnippetNotice.SAVED -> R.string.snippets_saved
        SnippetNotice.DELETED -> R.string.snippets_deleted
    }

@Composable
private fun SnippetRow(
    snippet: Snippet,
    viewModel: SnippetsViewModel,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(snippet.name) },
        overlineContent = snippet.packageName?.let { { Text(it) } },
        supportingContent = {
            Text(snippet.body.lineSequence().first(), fontFamily = FontFamily.Monospace, maxLines = 1)
        },
        trailingContent = {
            Row {
                IconButton(onClick = { viewModel.startRun(snippet) }) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.snippets_run, snippet.name),
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.snippets_actions, snippet.name),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = {
                            menu = false
                            viewModel.edit(snippet)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                            menu = false
                            onDelete()
                        })
                    }
                }
            }
        },
        modifier = Modifier.clickable(role = Role.Button) @DontMemoize { viewModel.startRun(snippet) },
    )
}

@Composable
private fun SnippetEditorDialog(
    draft: SnippetDraft,
    viewModel: SnippetsViewModel,
) {
    AlertDialog(
        onDismissRequest = @DontMemoize { viewModel.cancelDraft() },
        title = { Text(stringResource(if (draft.id == 0L) R.string.snippets_add else R.string.snippets_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { name -> viewModel.updateDraft { it.copy(name = name, nameMissing = false) } },
                    label = { Text(stringResource(R.string.snippets_name)) },
                    isError = draft.nameMissing,
                    supportingText =
                        if (draft.nameMissing) {
                            (
                                {
                                    Text(
                                        stringResource(R.string.snippets_name_required),
                                    )
                                }
                            )
                        } else {
                            null
                        },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.body,
                    onValueChange = { body -> viewModel.updateDraft { it.copy(body = body) } },
                    label = { Text(stringResource(R.string.snippets_command)) },
                    supportingText = { Text(stringResource(R.string.snippets_variables_hint)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 3,
                )
                if (draft.variables.isNotEmpty()) {
                    Text(
                        stringResource(R.string.snippets_variables, draft.variables.joinToString { it.name }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = draft.packageName,
                    onValueChange = { pkg -> viewModel.updateDraft { it.copy(packageName = pkg) } },
                    label = { Text(stringResource(R.string.snippets_package)) },
                    singleLine = true,
                )
                Text(stringResource(R.string.snippets_default_action), style = MaterialTheme.typography.labelLarge)
                RunModePicker(draft.runMode) { mode -> viewModel.updateDraft { it.copy(runMode = mode) } }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveDraft) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            TextButton(
                onClick = viewModel::cancelDraft,
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunModePicker(
    selected: SnippetRunMode,
    onSelect: (SnippetRunMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SnippetRunMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, SnippetRunMode.entries.size),
            ) { Text(stringResource(runModeLabel(mode))) }
        }
    }
}

private fun runModeLabel(mode: SnippetRunMode): Int =
    when (mode) {
        SnippetRunMode.PASTE -> R.string.snippets_mode_paste
        SnippetRunMode.EXEC -> R.string.snippets_mode_exec
    }

@Composable
private fun SnippetRunDialog(
    run: SnippetRun,
    viewModel: SnippetsViewModel,
) {
    val exec = run.snippet.runMode == SnippetRunMode.EXEC
    AlertDialog(
        onDismissRequest = @DontMemoize { viewModel.closeRun() },
        title = { Text(run.snippet.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                run.snippet.variables.forEach { variable ->
                    OutlinedTextField(
                        value = run.values.getValue(variable.name),
                        onValueChange = { viewModel.setValue(variable.name, it) },
                        label = { Text(variable.name) },
                        singleLine = true,
                    )
                }
                Text(stringResource(R.string.snippets_preview), style = MaterialTheme.typography.labelLarge)
                Text(
                    run.command,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
                ExecOutput(run)
            }
        },
        confirmButton = {
            TextButton(onClick = if (exec) viewModel::exec else viewModel::paste, enabled = !run.running) {
                Text(stringResource(if (exec) R.string.snippets_mode_exec else R.string.snippets_mode_paste))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = if (exec) viewModel::paste else viewModel::exec, enabled = !run.running) {
                    Text(stringResource(if (exec) R.string.snippets_mode_paste else R.string.snippets_mode_exec))
                }
                TextButton(onClick = viewModel::closeRun) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

@Composable
private fun ExecOutput(run: SnippetRun) {
    if (run.running) LinearProgressIndicator(Modifier.fillMaxWidth())
    run.error?.let { Text(stringResource(R.string.snippets_run_failed, it), color = MaterialTheme.colorScheme.error) }
    val result = run.result ?: return
    Text(
        stringResource(R.string.snippets_exit_status, result.exitStatus?.toString() ?: "?"),
        style = MaterialTheme.typography.labelLarge,
    )
    val output = (result.stdout + result.stderr).ifEmpty { stringResource(R.string.snippets_no_output) }
    Text(
        output,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
    )
}
