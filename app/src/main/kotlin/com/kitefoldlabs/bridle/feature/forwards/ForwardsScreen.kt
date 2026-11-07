package com.kitefoldlabs.bridle.feature.forwards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.ui.theme.CreateFab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardsScreen(
    viewModel: ForwardsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<PortForward?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.forwards_title)) },
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
            CreateFab(onClick = viewModel::create) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.forwards_add))
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.forwards_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (state.loaded && state.forwards.isEmpty()) {
                item { Text(stringResource(R.string.forwards_empty), modifier = Modifier.padding(16.dp)) }
            }
            items(state.forwards, key = { it.id }) { forward ->
                ForwardRow(forward, state.statuses[forward.id], viewModel, onDelete = { deleting = forward })
            }
        }
    }
    state.draft?.let { ForwardEditorDialog(it, viewModel) }
    deleting?.let { forward ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.forwards_delete_title, forwardLabel(forward))) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(forward)
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

@Composable
private fun forwardLabel(forward: PortForward): String =
    when (forward.type) {
        PortForwardType.LOCAL ->
            stringResource(
                R.string.forwards_local_label,
                forward.bindPort,
                "${forward.destHost}",
                "${forward.destPort}",
            )
        PortForwardType.DYNAMIC -> stringResource(R.string.forwards_dynamic_label, forward.bindPort)
    }

@Composable
private fun statusText(status: ForwardStatus?): String {
    if (status == null) return stringResource(R.string.forwards_stopped)
    return when (status.phase) {
        ForwardStatus.Phase.STARTING -> stringResource(R.string.forwards_starting)
        ForwardStatus.Phase.RUNNING ->
            pluralStringResource(R.plurals.forwards_running, status.connections, status.connections)
        ForwardStatus.Phase.FAILED -> stringResource(R.string.forwards_failed, status.error.orEmpty())
    }
}

@Composable
private fun ForwardRow(
    forward: PortForward,
    status: ForwardStatus?,
    viewModel: ForwardsViewModel,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val label = forwardLabel(forward)
    val running = status != null
    val toggle = stringResource(R.string.forwards_toggle, label)
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            val auto = if (forward.autoStart) " · " + stringResource(R.string.forwards_auto_start_short) else ""
            Text(statusText(status) + auto)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = running,
                    onCheckedChange = null,
                    modifier =
                        Modifier
                            .minimumInteractiveComponentSize()
                            .toggleable(running, role = Role.Switch) { viewModel.setRunning(forward, it) }
                            .semantics { contentDescription = toggle },
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.forwards_actions, label),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = {
                            menu = false
                            viewModel.edit(forward)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                            menu = false
                            onDelete()
                        })
                    }
                }
            }
        },
    )
}

@Composable
private fun ForwardEditorDialog(
    draft: ForwardDraft,
    viewModel: ForwardsViewModel,
) {
    val local = draft.type == PortForwardType.LOCAL
    AlertDialog(
        onDismissRequest = @DontMemoize { viewModel.cancelDraft() },
        title = { Text(stringResource(if (draft.id == 0L) R.string.forwards_add else R.string.forwards_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TypePicker(draft.type) { type -> viewModel.updateDraft { it.copy(type = type, errors = emptySet()) } }
                val help = if (local) R.string.forwards_local_help else R.string.forwards_dynamic_help
                Text(stringResource(help), style = MaterialTheme.typography.bodySmall)
                PortField(draft.bindPort, R.string.forwards_bind_port, ForwardField.BIND_PORT in draft.errors) { port ->
                    viewModel.updateDraft { it.copy(bindPort = port, errors = it.errors - ForwardField.BIND_PORT) }
                }
                if (local) DestinationFields(draft, viewModel)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(draft.autoStart, role = Role.Checkbox) { auto ->
                            viewModel.updateDraft { it.copy(autoStart = auto) }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = draft.autoStart, onCheckedChange = null)
                    Text(stringResource(R.string.forwards_auto_start), modifier = Modifier.padding(start = 8.dp))
                }
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
private fun TypePicker(
    selected: PortForwardType,
    onSelect: (PortForwardType) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PortForwardType.entries.forEachIndexed { index, type ->
            val label = if (type == PortForwardType.LOCAL) R.string.forwards_local else R.string.forwards_dynamic
            SegmentedButton(
                selected = selected == type,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, PortForwardType.entries.size),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun DestinationFields(
    draft: ForwardDraft,
    viewModel: ForwardsViewModel,
) {
    OutlinedTextField(
        value = draft.destHost,
        onValueChange = @DontMemoize { host ->
            viewModel.updateDraft {
                it.copy(
                    destHost = host,
                    errors =
                        it.errors - ForwardField.DEST_HOST,
                )
            }
        },
        label = { Text(stringResource(R.string.forwards_dest_host)) },
        isError = ForwardField.DEST_HOST in draft.errors,
        supportingText = { Text(stringResource(R.string.forwards_dest_host_help)) },
        singleLine = true,
    )
    val portError = ForwardField.DEST_PORT in draft.errors
    PortField(draft.destPort, R.string.forwards_dest_port, portError) @DontMemoize { port ->
        viewModel.updateDraft { it.copy(destPort = port, errors = it.errors - ForwardField.DEST_PORT) }
    }
}

@Composable
private fun PortField(
    value: String,
    label: Int,
    error: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter(Char::isDigit).take(MAX_PORT_DIGITS)) },
        label = { Text(stringResource(label)) },
        isError = error,
        supportingText = if (error) ({ Text(stringResource(R.string.forwards_port_invalid)) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private const val MAX_PORT_DIGITS = 5
