package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.kitefoldlabs.bridle.core.terminal.ComposeHistory
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout

/**
 * A full terminal screen laid out for one-handed portrait use: the status banner on top, the
 * terminal filling the middle, and everything the thumb needs at the bottom: the compose box
 * (when open), the key bar, and buttons for the compose box and the soft keyboard. Asks before
 * a paste or send that would run lines immediately.
 *
 * Pads itself for the system bars and the soft keyboard (`safeDrawing` insets), so use it in an
 * edge-to-edge activity.
 */
@Composable
public fun TerminalScreen(
    state: TerminalScreenState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    keyBarLayout: KeyBarLayout = KeyBarLayout.DEFAULT,
    composeEnterSends: Boolean = false,
) {
    val terminalState by state.terminalState.collectAsState()
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TerminalStatusBanner(terminalState, onReconnect)
        TerminalPane(state, Modifier.weight(1f).fillMaxWidth())
        if (state.isComposeOpen) ComposeBox(state, enterSends = composeEnterSends)
        Row(Modifier.fillMaxWidth()) {
            KeyBar(keyBarLayout, state.sticky, state::onKeyBarAction, Modifier.weight(1f))
            ScreenButtons(state, rows = keyBarLayout.rows.size)
        }
    }
    state.pendingSend?.let { PasteConfirmationDialog(it, state::confirmPendingSend, state::dismissPendingSend) }
}

/** Compose-box and keyboard toggles, as tall as the key bar so they stay in thumb reach. */
@Composable
private fun ScreenButtons(
    state: TerminalScreenState,
    rows: Int,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.height(KeyBarRowHeight * rows)) {
        Column {
            val composeLabel =
                stringResource(
                    when {
                        state.isComposeOpen -> R.string.bridle_toolbar_close_compose
                        else -> R.string.bridle_toolbar_open_compose
                    },
                )
            IconToggleButton(
                checked = state.isComposeOpen,
                onCheckedChange = { open -> if (open) state.openCompose() else state.closeCompose() },
            ) {
                Icon(painterResource(R.drawable.bridle_ic_edit), contentDescription = composeLabel)
            }
            val keyboardLabel =
                stringResource(
                    when {
                        state.isKeyboardVisible -> R.string.bridle_toolbar_hide_keyboard
                        else -> R.string.bridle_toolbar_show_keyboard
                    },
                )
            IconButton(onClick = state::toggleKeyboard) {
                Icon(painterResource(R.drawable.bridle_ic_keyboard), contentDescription = keyboardLabel)
            }
        }
    }
}

/**
 * A [TerminalScreenState] tied to the composition. Prefer creating one in a ViewModel with
 * `viewModelScope` so the font size and compose text survive rotation.
 */
@Composable
public fun rememberTerminalScreenState(
    engine: TerminalEngine,
    connection: TerminalConnection,
    history: ComposeHistory,
    fontBounds: FontSizeBounds = FontSizeBounds(),
): TerminalScreenState {
    val scope = rememberCoroutineScope()
    return remember(engine, connection, history, fontBounds) {
        TerminalScreenState(engine, connection, scope, history, fontBounds = fontBounds)
    }
}
