package com.kitefoldlabs.bridle.feature.hosts

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.Validation

/** Minimum height of tappable rows (Material's 48dp touch target). */
internal val MinTouchTarget = 48.dp

/** The user-facing text for a validation failure. */
@StringRes
internal fun Validation.Reason.message(): Int =
    when (this) {
        Validation.Reason.BLANK -> R.string.validation_blank
        Validation.Reason.TOO_LONG -> R.string.validation_too_long
        Validation.Reason.ILLEGAL_CHARACTERS -> R.string.validation_illegal_characters
        Validation.Reason.OUT_OF_RANGE -> R.string.validation_out_of_range
    }

/** A navigation icon that goes back, labelled for TalkBack. */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
    }
}

/** One option of a [ChoiceField]. */
internal data class Choice<T>(
    val value: T,
    val label: String,
)

/**
 * A read-only field showing the current choice; tapping it opens a dialog listing [choices] as
 * radio buttons. Used instead of dropdowns so long lists (hosts, keys) scroll comfortably.
 */
@Composable
internal fun <T> ChoiceField(
    label: String,
    selected: T,
    choices: List<Choice<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = choices.firstOrNull { it.value == selected }?.label.orEmpty()
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(current) },
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clickable(role = Role.Button, onClickLabel = label) { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                LazyColumn {
                    items(choices) { choice ->
                        ListItem(
                            leadingContent = { RadioButton(selected = choice.value == selected, onClick = null) },
                            headlineContent = { Text(choice.label) },
                            modifier =
                                Modifier.selectable(
                                    selected = choice.value == selected,
                                    role = Role.RadioButton,
                                ) {
                                    open = false
                                    onSelect(choice.value)
                                },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A yes/no confirmation for destructive actions. */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
