package com.kitefoldlabs.bridle.feature.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.LaunchMode
import com.kitefoldlabs.bridle.core.model.RestartMode
import com.kitefoldlabs.bridle.core.model.Validation

/**
 * Creates or edits a Launch Profile. Every field shows its own validation error.
 *
 * The callbacks here capture the form, which changes on every edit, so remembering them would
 * only add work: they are marked `@DontMemoize`.
 */
@Composable
internal fun ProfileEditor(
    form: ProfileForm,
    actions: SessionsActions,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = @DontMemoize { actions.cancelEditor() })
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = @DontMemoize { actions.cancelEditor() }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dialog_cancel))
            }
            Text(
                stringResource(if (form.isNew) R.string.profile_new_title else R.string.profile_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = @DontMemoize { actions.saveEditor() }, enabled = form.isValid) {
                Text(stringResource(R.string.profile_save))
            }
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FormField(form, ProfileField.NAME, R.string.profile_name, actions)
            FormField(form, ProfileField.SESSION_NAME, R.string.profile_session, actions)
            FormField(form, ProfileField.WORKING_DIR, R.string.profile_working_dir, actions)
            FormField(form, ProfileField.COMMAND, R.string.profile_command, actions)
            FormField(form, ProfileField.RESTART_COMMAND, R.string.profile_restart_command, actions)
            Choices(
                R.string.profile_restart_mode,
                RestartMode.entries,
                form.restartMode,
                ::restartModeLabel,
                @DontMemoize { actions.updateEditor(form.withRestartMode(it)) },
            )
            Choices(
                R.string.profile_launch_mode,
                LaunchMode.entries,
                form.mode,
                ::launchModeLabel,
                @DontMemoize { actions.updateEditor(form.withMode(it)) },
            )
            Text(stringResource(R.string.profile_env), style = MaterialTheme.typography.titleSmall)
            val envErrors = form.envErrors
            form.env.forEachIndexed { index, row -> EnvRow(form, index, row, envErrors[index], actions) }
            TextButton(onClick = @DontMemoize { actions.updateEditor(form.withNewEnv()) }) {
                Text(stringResource(R.string.profile_env_add))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth().toggleable(
                        form.pinned,
                        role = Role.Switch,
                        onValueChange = @DontMemoize { actions.updateEditor(form.withPinned(it)) },
                    ),
            ) {
                Text(stringResource(R.string.profile_pinned), modifier = Modifier.weight(1f))
                Switch(checked = form.pinned, onCheckedChange = null)
            }
        }
    }
}

/** A text field for [field], with its validation error or (for the restart command) a hint. */
@Composable
private fun FormField(
    form: ProfileForm,
    field: ProfileField,
    label: Int,
    actions: SessionsActions,
) {
    val error = form.errors[field]
    val supporting =
        when {
            error != null -> fieldError(field, error)
            field == ProfileField.RESTART_COMMAND -> R.string.profile_restart_command_hint
            else -> null
        }
    OutlinedTextField(
        value = form.text(field),
        onValueChange = @DontMemoize { actions.updateEditor(form.withText(field, it)) },
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = supporting?.let { text -> { Text(stringResource(text)) } },
        singleLine = true,
        keyboardOptions = PlainText,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun <T> Choices(
    title: Int,
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = option == selected, role = Role.RadioButton) { onSelect(option) }
                        .padding(vertical = 12.dp),
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Text(stringResource(label(option)), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** One environment variable: name (validated), value, and a remove button. */
@Composable
private fun EnvRow(
    form: ProfileForm,
    index: Int,
    row: EnvVar,
    error: EnvError?,
    actions: SessionsActions,
) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = row.name,
            onValueChange = @DontMemoize { actions.updateEditor(form.withEnv(index, row.copy(name = it))) },
            label = { Text(stringResource(R.string.profile_env_name)) },
            isError = error != null,
            supportingText = error?.let { problem -> { Text(stringResource(envError(problem))) } },
            singleLine = true,
            keyboardOptions = PlainText.copy(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.weight(1f),
        )
        // Values are often API keys: masked until asked, and kept out of the keyboard's dictionary.
        var shown by remember(index) { mutableStateOf(false) }
        OutlinedTextField(
            value = row.value,
            onValueChange = @DontMemoize { actions.updateEditor(form.withEnv(index, row.copy(value = it))) },
            label = { Text(stringResource(R.string.profile_env_value)) },
            singleLine = true,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { shown = !shown }) {
                    Icon(
                        if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription =
                            stringResource(
                                if (shown) R.string.profile_env_value_hide else R.string.profile_env_value_show,
                            ),
                    )
                }
            },
            keyboardOptions = PlainText.copy(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = @DontMemoize { actions.updateEditor(form.withoutEnv(index)) }) {
            Icon(Icons.Filled.RemoveCircleOutline, contentDescription = stringResource(R.string.profile_env_remove))
        }
    }
}

/** Commands and paths: no autocorrect or capitalisation. */
private val PlainText = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

internal fun fieldError(
    field: ProfileField,
    reason: Validation.Reason,
): Int =
    when (reason) {
        Validation.Reason.BLANK -> R.string.profile_error_required
        Validation.Reason.TOO_LONG -> R.string.profile_error_too_long
        // Only names and session names are checked for characters (and nothing for range).
        else ->
            if (field ==
                ProfileField.SESSION_NAME
            ) {
                R.string.profile_error_session_chars
            } else {
                R.string.profile_error_control_chars
            }
    }

internal fun envError(error: EnvError): Int =
    when (error) {
        EnvError.BLANK -> R.string.profile_error_required
        EnvError.ILLEGAL_CHARACTERS -> R.string.profile_error_env_chars
        EnvError.DUPLICATE -> R.string.profile_error_env_duplicate
    }

internal fun restartModeLabel(mode: RestartMode): Int =
    when (mode) {
        RestartMode.RESPAWN_CONTINUE -> R.string.restart_mode_continue
        RestartMode.RESPAWN_FRESH -> R.string.restart_mode_fresh
        RestartMode.SEND_KEYS -> R.string.restart_mode_keys
    }

internal fun launchModeLabel(mode: LaunchMode): Int =
    when (mode) {
        LaunchMode.SHELL_HOSTED -> R.string.launch_mode_shell
        LaunchMode.DIRECT -> R.string.launch_mode_direct
    }
