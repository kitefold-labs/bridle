package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Most lines the compose box grows to before it scrolls. */
private const val MAX_VISIBLE_LINES = 6

/**
 * The compose box for [state]: a full text field for writing multi-line prompts, sent with
 * [TerminalScreenState.submitCompose].
 */
@Composable
public fun ComposeBox(
    state: TerminalScreenState,
    modifier: Modifier = Modifier,
) {
    ComposeBox(
        text = state.composeText,
        onTextChange = state::updateComposeText,
        onSend = state::submitCompose,
        onHistoryPrevious = state::historyPrevious,
        onHistoryNext = state::historyNext,
        onClose = state::closeCompose,
        modifier = modifier,
    )
}

/**
 * A multi-line text field with Send (also Ctrl+Enter on a hardware keyboard), ↑/↓ through the
 * host's compose history and a close button. The field takes focus, and the soft keyboard,
 * when shown. When [text] changes from outside (history, or cleared after sending) the cursor
 * moves to its end.
 */
@Composable
public fun ComposeBox(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onHistoryPrevious: () -> Unit,
    onHistoryNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var edited by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val value = if (edited.text == text) edited else TextFieldValue(text, TextRange(text.length))
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                IconButton(onClick = onHistoryPrevious) {
                    Icon(
                        painterResource(R.drawable.bridle_ic_arrow_up),
                        contentDescription = stringResource(R.string.bridle_compose_history_previous),
                    )
                }
                IconButton(onClick = onHistoryNext) {
                    Icon(
                        painterResource(R.drawable.bridle_ic_arrow_down),
                        contentDescription = stringResource(R.string.bridle_compose_history_next),
                    )
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = {
                    edited = it
                    if (it.text != text) onTextChange(it.text)
                },
                label = { Text(stringResource(R.string.bridle_compose_label)) },
                maxLines = MAX_VISIBLE_LINES,
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            val send =
                                event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed
                            if (send) onSend()
                            send
                        },
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onClose) {
                    Icon(
                        painterResource(R.drawable.bridle_ic_close),
                        contentDescription = stringResource(R.string.bridle_toolbar_close_compose),
                    )
                }
                FilledIconButton(onClick = onSend, enabled = text.isNotBlank()) {
                    Icon(
                        painterResource(R.drawable.bridle_ic_send),
                        contentDescription = stringResource(R.string.bridle_compose_send),
                    )
                }
            }
        }
    }
}

/**
 * Asks before sending [pending], which would run each of its lines as it arrives because the
 * remote program has not turned on bracketed paste.
 */
@Composable
public fun PasteConfirmationDialog(
    pending: PendingSend,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(pluralStringResource(R.plurals.bridle_paste_confirm_title, pending.lineCount, pending.lineCount))
        },
        text = { Text(stringResource(R.string.bridle_paste_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.bridle_paste_confirm_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bridle_paste_confirm_cancel)) }
        },
    )
}
