package com.kitefoldlabs.bridle.core.terminalui

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The terminal itself: hosts the [TerminalScreenState.engine]'s view and connects it to [state].
 *
 * - The grid is fitted to the pane (and refitted when it or the font size changes), and the
 *   remote PTY follows ([TerminalScreenState.onViewportChanged]).
 * - Pinch zooms the font. termlib magnifies while the fingers move; the new size is applied when
 *   they lift, within [TerminalScreenState.fontBounds].
 * - A one-finger vertical drag scrolls the engine's scrollback, except on the alternate screen,
 *   where the pane takes it and sends it to the remote program
 *   ([TerminalScreenState.onScrollGesture]), one notch per line of cell height.
 * - Tap shows the soft keyboard; long-press selects (both handled by the engine).
 * - The engine's Paste menu pastes the clipboard through [TerminalScreenState.paste], so it is
 *   sanitised, bracketed when the remote wants it, and confirmed when it would run lines.
 * - Tapping a link opens it when it is http(s); other schemes (`intent:`, `file:`) are ignored.
 */
@Composable
public fun TerminalPane(
    state: TerminalScreenState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val fontPx = with(LocalDensity.current) { state.fontSizeSp.sp.toPx() }
    val cell = remember(state.engine, fontPx) { state.engine.cellMetrics(fontPx) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(state, viewport, cell) { state.onViewportChanged(viewport.width, viewport.height, cell) }
    val currentState by rememberUpdatedState(state)

    Box(
        modifier =
            modifier
                .onSizeChanged { viewport = it }
                .pointerInput(Unit) { detectPinch { currentState.zoomBy(it) } }
                .pointerInput(cell) {
                    detectVerticalDrag(cell.heightPx, { currentState.takesScrollGesture }) { lines, at ->
                        val grid = currentState.engine.size.value
                        currentState.onScrollGesture(
                            lines,
                            column = (at.x / cell.widthPx).toInt().coerceIn(0, grid.columns - 1) + 1,
                            row = (at.y / cell.heightPx).toInt().coerceIn(0, grid.rows - 1) + 1,
                        )
                    }
                },
    ) {
        state.engine.Content(
            host = state,
            onPasteRequest = {
                scope.launch { clipboard.getClipEntry()?.let { state.paste(clipText(it, context)) } }
            },
            onOpenUrl = { url ->
                if (isOpenableUrl(url)) {
                    try {
                        uriHandler.openUri(url)
                    } catch (_: IllegalArgumentException) {
                        // No app handles it.
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** True for http(s) links, the only ones the terminal opens. */
internal fun isOpenableUrl(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https"
}

/** The clipboard's first item as text (HTML and URIs are converted, as a text field would). */
private fun clipText(
    entry: ClipEntry,
    context: Context,
): String =
    entry.clipData
        .getItemAt(0)
        .coerceToText(context)
        .toString()

/**
 * Watches one-finger vertical drags on the Initial pass. Nothing is consumed until a drag is
 * certain ([awaitVerticalDrag]), so the engine still gets taps, long-press selection,
 * horizontal swipes and pinches. Once it is, [takes] decides whether the pane wants it; if so
 * every further change is consumed (the engine then cancels its own gesture) and [onLines] gets
 * each whole [lineHeightPx] moved since the finger went down, positive downwards, with the
 * finger's position.
 */
private suspend fun PointerInputScope.detectVerticalDrag(
    lineHeightPx: Float,
    takes: () -> Boolean,
    onLines: (lines: Int, at: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var change = awaitVerticalDrag(down)
        if (change == null || !takes()) return@awaitEachGesture
        var lastY = down.position.y
        var carried = 0f
        while (change != null) {
            change.consume()
            carried += change.position.y - lastY
            lastY = change.position.y
            val lines = (carried / lineHeightPx).toInt()
            if (lines != 0) {
                carried -= lines * lineHeightPx
                onLines(lines, change.position)
            }
            change =
                if (change.pressed) {
                    awaitPointerEvent(
                        PointerEventPass.Initial,
                    ).changes.firstOrNull { it.id == down.id }
                } else {
                    null
                }
        }
    }
}

/**
 * Waits for [down] to travel past the touch slop, more vertically than horizontally, before the
 * long-press timeout, and returns that change. Returns null when the gesture turns out to be
 * something else: a lift (tap), a second finger (pinch), a long press (selection) or a
 * horizontal swipe.
 */
private suspend fun AwaitPointerEventScope.awaitVerticalDrag(down: PointerInputChange): PointerInputChange? {
    val slop = viewConfiguration.touchSlop
    val longPressMillis = viewConfiguration.longPressTimeoutMillis
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id }
        val stillOneFinger =
            change != null &&
                change.pressed &&
                event.changes.count { it.pressed } == 1 &&
                change.uptimeMillis - down.uptimeMillis < longPressMillis
        if (!stillOneFinger) return null
        val dx = abs(change.position.x - down.position.x)
        val dy = abs(change.position.y - down.position.y)
        when {
            dy > slop && dy > dx -> return change
            dx > slop -> return null
        }
    }
}

/**
 * Watches for a pinch without consuming it (the engine sees the same events) and reports the
 * total scale once all fingers lift.
 */
private suspend fun PointerInputScope.detectPinch(onZoom: (Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var scale = 1f
        var pinched = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) {
                pinched = true
                scale *= event.calculateZoom()
            }
        } while (event.changes.any { it.pressed })
        if (pinched) onZoom(scale)
    }
}
