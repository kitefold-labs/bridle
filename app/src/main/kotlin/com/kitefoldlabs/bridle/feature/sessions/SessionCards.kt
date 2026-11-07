package com.kitefoldlabs.bridle.feature.sessions

import android.text.format.DateUtils
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.tmux.ClaudeState
import com.kitefoldlabs.bridle.core.tmux.ClaudeStatus
import com.kitefoldlabs.bridle.core.tmux.SessionSummary
import com.kitefoldlabs.bridle.core.tmux.StatusSource
import com.kitefoldlabs.bridle.ui.theme.MonoFamily

/** True if the session runs Claude Code (by its process, or because the helper reports on it). */
internal val SessionSummary.isClaude: Boolean get() = claudeRunning || claude != null

/**
 * One tmux session: name, Claude badge and status, last activity, a preview of its last lines,
 * and Attach / Restart / Stop, plus quick replies for Claude sessions.
 */
@Composable
internal fun SessionCard(
    summary: SessionSummary,
    preview: String?,
    hasProfile: Boolean,
    now: Long,
    actions: SessionsActions,
) {
    val name = summary.session.name
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (summary.isClaude) ClaudeBadge()
            }
            Text(activityLine(summary, now), style = MaterialTheme.typography.bodySmall)
            summary.claude?.let { ClaudeStatusChip(it) }
            if (!preview.isNullOrEmpty()) PanePreview(preview)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { actions.attach(name) }) { Text(stringResource(R.string.session_attach)) }
                if (summary.isClaude || hasProfile) {
                    RestartMenu(onRestart = { fresh -> actions.restartSession(summary, fresh) })
                }
                OutlinedButton(onClick = { actions.requestStop(name) }) { Text(stringResource(R.string.session_stop)) }
            }
            if (summary.isClaude) QuickReplies(name, actions)
        }
    }
}

@Composable
private fun activityLine(
    summary: SessionSummary,
    now: Long,
): String {
    val session = summary.session
    val ago =
        DateUtils
            .getRelativeTimeSpanString(
                session.lastActivityEpochSeconds * MILLIS_PER_SECOND,
                now,
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
    val parts =
        listOfNotNull(
            stringResource(R.string.session_last_active, ago),
            pluralStringResource(R.plurals.session_windows, session.windows, session.windows),
            if (session.attachedClients > 0) stringResource(R.string.session_attached) else null,
        )
    return parts.joinToString(" · ")
}

@Composable
private fun ClaudeBadge() {
    val description = stringResource(R.string.claude_badge_description)
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            stringResource(R.string.claude_badge),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Claude's state and where it came from: "hooks" (bridle-helper) or "guess" (the screen). */
@Composable
internal fun ClaudeStatusChip(status: ClaudeStatus) {
    val state = stringResource(stateLabel(status.state))
    val hooks = status.source == StatusSource.HOOKS
    val source = stringResource(if (hooks) R.string.claude_source_hooks else R.string.claude_source_guess)
    val description = stringResource(if (hooks) R.string.claude_status_hooks else R.string.claude_status_guess, state)
    val (container, content) = stateColors(status.state)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            color = container,
            contentColor = content,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        ) {
            Text(
                "$state · $source",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

internal fun stateLabel(state: ClaudeState): Int =
    when (state) {
        ClaudeState.WORKING -> R.string.claude_state_working
        ClaudeState.WAITING_PERMISSION -> R.string.claude_state_permission
        ClaudeState.WAITING_INPUT -> R.string.claude_state_input
        ClaudeState.IDLE -> R.string.claude_state_idle
        ClaudeState.EXITED -> R.string.claude_state_exited
        ClaudeState.UNKNOWN -> R.string.claude_state_unknown
    }

@Composable
private fun stateColors(state: ClaudeState): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        ClaudeState.WAITING_PERMISSION -> scheme.errorContainer to scheme.onErrorContainer
        ClaudeState.WAITING_INPUT, ClaudeState.IDLE -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ClaudeState.WORKING -> scheme.secondaryContainer to scheme.onSecondaryContainer
        ClaudeState.EXITED, ClaudeState.UNKNOWN -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
}

@Composable
private fun PanePreview(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            fontFamily = MonoFamily,
            style = MaterialTheme.typography.bodySmall,
            maxLines = ClaudeStatusHub.PREVIEW_KEEP,
            modifier = Modifier.padding(8.dp),
        )
    }
}

/** Restart with the profile's restart command (continue), or fresh. */
@Composable
private fun RestartMenu(onRestart: (fresh: Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.session_restart)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_restart_continue)) },
                onClick = {
                    open = false
                    onRestart(false)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_restart_fresh)) },
                onClick = {
                    open = false
                    onRestart(true)
                },
            )
        }
    }
}

@Composable
private fun QuickReplies(
    session: String,
    actions: SessionsActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.session_quick_reply), style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickReply.entries.forEach { reply ->
                val description = stringResource(reply.description)
                AssistChip(
                    onClick = { actions.quickReply(session, reply) },
                    label = { Text(stringResource(reply.label)) },
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
        }
    }
}

/** A Launch Profile: what it runs and where, whether it is running, and its actions. */
@Composable
internal fun ProfileCard(
    profile: LaunchProfile,
    running: Boolean,
    actions: SessionsActions,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    stringResource(if (running) R.string.profile_running else R.string.profile_not_running),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                stringResource(R.string.profile_summary, profile.sessionName, profile.workingDir),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(profile.command, fontFamily = MonoFamily, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { actions.startProfile(profile) }) {
                    Text(stringResource(if (running) R.string.session_attach else R.string.profile_start))
                }
                RestartMenu(onRestart = { fresh -> actions.restartProfile(profile, fresh) })
                Box(Modifier.weight(1f))
                IconButton(onClick = { actions.editProfile(profile) }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.profile_edit, profile.name))
                }
                IconButton(onClick = { actions.requestDeleteProfile(profile) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.profile_delete, profile.name),
                    )
                }
            }
        }
    }
}

private const val MILLIS_PER_SECOND = 1000L
