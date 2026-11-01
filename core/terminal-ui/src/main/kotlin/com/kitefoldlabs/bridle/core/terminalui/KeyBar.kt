package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitefoldlabs.bridle.core.terminal.KeyAction
import com.kitefoldlabs.bridle.core.terminal.KeyBarKey
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout
import com.kitefoldlabs.bridle.core.terminal.ModifierState
import com.kitefoldlabs.bridle.core.terminal.StickyState

/** Height of one key-bar row. */
internal val KeyBarRowHeight: Dp = 44.dp

/** How far a finger must travel up on a key to send its alternate instead of a tap. */
private val SwipeUpThreshold = 20.dp

/**
 * The extra-keys bar: renders [layout] (one row per layout row, keys sharing the width), shows
 * sticky Ctrl/Alt from [sticky], and reports each tap or swipe-up alternate to [onAction]
 * (normally [TerminalScreenState.onKeyBarAction]).
 *
 * Keys fire on release, so a key can be swiped up instead; a key's alternate label is shown in
 * its corner. Every press gives haptic feedback. For TalkBack each key is a button named after
 * what it sends ("Control C", "Up arrow"), sticky keys announce their state, and the swipe-up
 * alternate is a custom action.
 */
@Composable
public fun KeyBar(
    layout: KeyBarLayout,
    sticky: StickyState,
    onAction: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()) {
            layout.rows.forEach { row ->
                Row(Modifier.fillMaxWidth().height(KeyBarRowHeight)) {
                    row.forEach { key ->
                        KeyBarButton(key, sticky, onAction, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyBarButton(
    key: KeyBarKey,
    sticky: StickyState,
    onAction: (KeyAction) -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val modifierState = (key.action as? KeyAction.Toggle)?.let { sticky[it.modifier] }
    val thresholdPx = with(LocalDensity.current) { SwipeUpThreshold.toPx() }
    val tap = {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        onAction(key.action)
    }
    val swipe =
        key.swipeUp?.let { alternate ->
            {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onAction(alternate)
            }
        }
    val description = KeyDescriptions.describe(key.action)
    val swipeDescription =
        stringResource(R.string.bridle_key_swipe_up, key.swipeUp?.let(KeyDescriptions::describe).orEmpty())
    val state = modifierState?.let { stringResource(stateLabel(it)) }
    val (container, content) = keyColors(modifierState)
    val currentTap by rememberUpdatedState(tap)
    val currentSwipe by rememberUpdatedState(swipe)

    Box(
        modifier =
            modifier
                .padding(horizontal = 2.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(container)
                .pointerInput(thresholdPx) { detectKeyGesture(thresholdPx, { currentTap() }, { currentSwipe }) }
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = description
                    state?.let { stateDescription = it }
                    onClick {
                        tap()
                        true
                    }
                    if (swipe != null) {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(swipeDescription) {
                                    swipe()
                                    true
                                },
                            )
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            color = content,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (modifierState == ModifierState.LOCKED) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
        key.swipeUpLabel?.let {
            Text(
                text = it,
                color = content.copy(alpha = 0.6f),
                fontSize = 8.sp,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 3.dp),
            )
        }
    }
}

/**
 * Taps fire on release; a release at least [thresholdPx] above the press fires the swipe-up
 * alternate from [swipe] when the key has one. A drag downwards cancels.
 */
private suspend fun PointerInputScope.detectKeyGesture(
    thresholdPx: Float,
    onTap: () -> Unit,
    swipe: () -> (() -> Unit)?,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val dy = awaitRelease(down) ?: return@awaitEachGesture
        val onSwipeUp = swipe()
        when {
            dy <= -thresholdPx && onSwipeUp != null -> onSwipeUp()
            dy < thresholdPx -> onTap() // an upward swipe on a key without an alternate is a tap
            else -> Unit // dragged down off the key: cancelled
        }
    }
}

/** Follows [down] until it lifts; returns how far it moved vertically, or null if it vanished. */
private suspend fun AwaitPointerEventScope.awaitRelease(down: PointerInputChange): Float? {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return null
        change.consume()
        if (!change.pressed) return change.position.y - down.position.y
    }
}

private fun stateLabel(state: ModifierState): Int =
    when (state) {
        ModifierState.OFF -> R.string.bridle_key_state_off
        ModifierState.ONCE -> R.string.bridle_key_state_once
        ModifierState.LOCKED -> R.string.bridle_key_state_locked
    }

@Composable
private fun keyColors(state: ModifierState?): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        ModifierState.ONCE -> scheme.primaryContainer to scheme.onPrimaryContainer
        ModifierState.LOCKED -> scheme.primary to scheme.onPrimary
        ModifierState.OFF, null -> scheme.surfaceContainerHighest to scheme.onSurface
    }
}
