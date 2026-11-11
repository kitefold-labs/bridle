package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Height of the suggestion strip, kept even when empty so the terminal doesn't resize. */
internal val SuggestionStripHeight: Dp = 36.dp

/**
 * A row of file-name suggestions for the path being typed; tapping one types the rest of it.
 * Scrolls sideways and starts at the left whenever the suggestions change. Like the key bar,
 * chips react to raw taps so they never take focus from the terminal's keyboard.
 */
@Composable
public fun SuggestionStrip(
    suggestions: List<PathSuggestion>,
    onAccept: (PathSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(suggestions) { scroll.scrollTo(0) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().height(SuggestionStripHeight),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(scroll).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            suggestions.forEach { SuggestionChip(it, onAccept) }
        }
    }
}

@Composable
private fun SuggestionChip(
    suggestion: PathSuggestion,
    onAccept: (PathSuggestion) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val accept by rememberUpdatedState {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        onAccept(suggestion)
    }
    val label = if (suggestion.isDirectory) "${suggestion.name}/" else suggestion.name
    val description = stringResource(R.string.bridle_suggestion_description, label)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .pointerInput(Unit) { detectTapGestures(onTap = { accept() }) }
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = description
                    onClick {
                        accept()
                        true
                    }
                }.padding(horizontal = 10.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}
