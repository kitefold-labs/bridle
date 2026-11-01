package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kitefoldlabs.bridle.core.session.TerminalState

/**
 * A strip above the terminal saying what the connection is doing: connecting, reattaching
 * after a drop (tmux tabs), or ended, with [onReconnect]. Shows nothing while the terminal is
 * open. Changes are announced by TalkBack.
 */
@Composable
public fun TerminalStatusBanner(
    state: TerminalState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    when (state) {
        TerminalState.Open -> Unit
        TerminalState.Connecting ->
            Banner(scheme.secondaryContainer, scheme.onSecondaryContainer, modifier, busy = true) {
                Text(stringResource(R.string.bridle_terminal_connecting))
            }
        TerminalState.Reattaching ->
            Banner(scheme.tertiaryContainer, scheme.onTertiaryContainer, modifier, busy = true) {
                Text(stringResource(R.string.bridle_terminal_reattaching))
            }
        is TerminalState.Ended ->
            Banner(scheme.errorContainer, scheme.onErrorContainer, modifier, busy = false) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bridle_terminal_ended, state.reason))
                    state.exitStatus?.let {
                        Text(
                            stringResource(R.string.bridle_terminal_exit_status, it),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                TextButton(onClick = onReconnect) { Text(stringResource(R.string.bridle_terminal_reconnect)) }
            }
    }
}

@Composable
private fun Banner(
    container: Color,
    content: Color,
    modifier: Modifier,
    busy: Boolean,
    body: @Composable RowScope.() -> Unit,
) {
    Surface(
        color = container,
        contentColor = content,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = content, strokeWidth = 2.dp)
            body()
        }
    }
}
