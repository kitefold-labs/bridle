package com.kitefoldlabs.bridle.feature.hosts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.model.Validators

/*
 * Callbacks built here are @DontMemoize: they only forward to the view model, so re-creating them
 * on recomposition is cheap, and remembering them would add compiler-generated branches (one per
 * captured parameter) that no test can tell apart.
 */

@Composable
internal fun HostEditorRoute(
    viewModel: HostEditorViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HostEditorScreen(state, viewModel, onDone)
}

/** Adds or edits a host. Calls [onDone] on back, and once the host is saved or deleted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditorScreen(
    state: HostEditorUiState,
    actions: HostEditorActions,
    onDone: () -> Unit,
) {
    LaunchedEffect(state.finished, block = @DontMemoize { if (state.finished) onDone() })
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onDone) },
                title = {
                    Text(
                        stringResource(if (state.isNew) R.string.host_editor_add else R.string.host_editor_edit),
                    )
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = actions::requestDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                    TextButton(onClick = actions::save, enabled = !state.saving && !state.loading) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.notFound -> Text(stringResource(R.string.host_editor_not_found), Modifier.padding(24.dp))
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> EditorForm(state, actions)
            }
        }
    }
    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.hosts_delete_title, state.form.label),
            text = stringResource(R.string.hosts_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = @DontMemoize { actions.confirmDelete() },
            onDismiss = @DontMemoize { actions.cancelDelete() },
        )
    }
}

@Composable
private fun EditorForm(
    state: HostEditorUiState,
    actions: HostEditorActions,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.missingReference) {
            Text(stringResource(R.string.host_editor_missing_reference), color = MaterialTheme.colorScheme.error)
        }
        TextInput.CONNECTION.forEach { FormField(it, state, actions) }
        Heading(R.string.host_editor_section_auth)
        ChoiceField(
            label = stringResource(R.string.host_editor_identity),
            selected = state.form.identityId,
            choices =
                listOf(Choice<EntityId?>(null, stringResource(R.string.host_editor_identity_none))) +
                    state.identities.map { Choice<EntityId?>(it.id, it.alias) },
            onSelect = @DontMemoize { id -> actions.edit(null) { it.copy(identityId = id) } },
        )
        Heading(R.string.host_editor_section_session)
        TextInput.SESSION.forEach { FormField(it, state, actions) }
        ChoiceField(
            label = stringResource(R.string.host_editor_jump),
            selected = state.form.jumpHostId,
            choices =
                listOf(Choice<EntityId?>(null, stringResource(R.string.host_editor_jump_none))) +
                    state.jumpCandidates.map { Choice<EntityId?>(it.id, it.label) },
            onSelect = @DontMemoize { id -> actions.edit(null) { it.copy(jumpHostId = id) } },
        )
        FormField(TextInput.KEEPALIVE, state, actions)
        Heading(R.string.host_editor_section_organize)
        OrganizeFields(state, actions)
    }
}

@Composable
private fun Heading(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp).semantics { heading() },
    )
}

@Composable
private fun OrganizeFields(
    state: HostEditorUiState,
    actions: HostEditorActions,
) {
    val form = state.form
    var newGroup by rememberSaveable { mutableStateOf(false) }
    ChoiceField(
        label = stringResource(R.string.host_editor_group),
        selected = form.groupId,
        choices =
            listOf(Choice<EntityId?>(null, stringResource(R.string.host_editor_group_none))) +
                state.groups.map { Choice<EntityId?>(it.id, it.name) } +
                Choice<EntityId?>(NEW_GROUP, stringResource(R.string.host_editor_group_new)),
        onSelect =
            @DontMemoize { id ->
                if (id == NEW_GROUP) newGroup = true else actions.edit(null) { it.copy(groupId = id) }
            },
    )
    if (newGroup) {
        NewGroupDialog(onCreate = @DontMemoize { actions.createGroup(it) }, onDismiss = { newGroup = false })
    }
    ChoiceField(
        label = stringResource(R.string.host_editor_color),
        selected = form.color,
        choices = HostColors.map { (color, name) -> Choice(color, stringResource(name)) },
        onSelect = @DontMemoize { color -> actions.edit(null) { it.copy(color = color) } },
    )
    FormField(TextInput.TAGS, state, actions)
    ListItem(
        headlineContent = { Text(stringResource(R.string.host_editor_favorite)) },
        trailingContent = { Switch(checked = form.favorite, onCheckedChange = null) },
        modifier =
            Modifier.heightIn(min = MinTouchTarget).toggleable(
                value = form.favorite,
                role = Role.Switch,
                onValueChange = @DontMemoize { checked -> actions.edit(null) { it.copy(favorite = checked) } },
            ),
    )
}

@Composable
private fun NewGroupDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = rememberTextFieldState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.host_editor_group_new)) },
        text = {
            OutlinedTextField(
                state = name,
                label = { Text(stringResource(R.string.host_editor_group_name)) },
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        },
        confirmButton = {
            TextButton(
                enabled = Validators.label(name.text.toString().trim()) == Validation.Valid,
                onClick = {
                    onCreate(name.text.toString())
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The editor's text fields: label, error field (if any), keyboard, and how each reads and writes the form. */
private enum class TextInput(
    @param:StringRes val label: Int,
    val field: HostField?,
    val get: (HostForm) -> String,
    val set: (HostForm, String) -> HostForm,
    val keyboard: KeyboardType = KeyboardType.Text,
    @param:StringRes val help: Int? = null,
    val singleLine: Boolean = true,
) {
    LABEL(R.string.host_editor_label, HostField.LABEL, { it.label }, { f, v -> f.copy(label = v) }),
    HOSTNAME(
        R.string.host_editor_hostname,
        HostField.HOSTNAME,
        { it.hostname },
        { f, v -> f.copy(hostname = v) },
        keyboard = KeyboardType.Uri,
        help = R.string.host_editor_hostname_help,
    ),
    PORT(R.string.host_editor_port, HostField.PORT, { it.port }, { f, v -> f.copy(port = v) }, KeyboardType.Number),
    USERNAME(R.string.host_editor_username, HostField.USERNAME, { it.username }, { f, v -> f.copy(username = v) }),
    TMUX(
        R.string.host_editor_tmux,
        HostField.TMUX_SESSION,
        { it.tmuxSession },
        { f, v -> f.copy(tmuxSession = v) },
        help = R.string.host_editor_tmux_help,
    ),
    STARTUP(R.string.host_editor_startup, null, { it.startupCommand }, { f, v -> f.copy(startupCommand = v) }),
    ENV(
        R.string.host_editor_env,
        HostField.ENV,
        { it.env },
        { f, v -> f.copy(env = v) },
        help = R.string.host_editor_env_help,
        singleLine = false,
    ),
    KEEPALIVE(
        R.string.host_editor_keepalive,
        HostField.KEEPALIVE,
        { it.keepAlive },
        { f, v -> f.copy(keepAlive = v) },
        KeyboardType.Number,
        R.string.host_editor_keepalive_help,
    ),
    TAGS(R.string.host_editor_tags, null, { it.tags }, {
        f,
        v,
        ->
        f.copy(tags = v)
    }, help = R.string.host_editor_tags_help),
    ;

    companion object {
        val CONNECTION: List<TextInput> = listOf(LABEL, HOSTNAME, PORT, USERNAME)
        val SESSION: List<TextInput> = listOf(TMUX, STARTUP, ENV)
    }
}

@Composable
private fun FormField(
    input: TextInput,
    state: HostEditorUiState,
    actions: HostEditorActions,
) {
    val error = input.field?.let { state.errors[it] }
    val supporting = error?.message() ?: input.help
    OutlinedTextField(
        value = input.get(state.form),
        onValueChange = @DontMemoize { value -> actions.edit(input.field) { input.set(it, value) } },
        label = { Text(stringResource(input.label)) },
        isError = error != null,
        supportingText = supporting?.let { { Text(stringResource(it)) } },
        singleLine = input.singleLine,
        minLines = if (input.singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = input.keyboard, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Sentinel for the "New group…" choice. */
private const val NEW_GROUP: EntityId = -1L

/** Host colours offered in the editor, with their names for TalkBack. */
private val HostColors: List<Pair<Int?, Int>> =
    listOf(
        null to R.string.color_none,
        0xFFE53935.toInt() to R.string.color_red,
        0xFFFB8C00.toInt() to R.string.color_orange,
        0xFF43A047.toInt() to R.string.color_green,
        0xFF1E88E5.toInt() to R.string.color_blue,
        0xFF8E24AA.toInt() to R.string.color_purple,
    )
