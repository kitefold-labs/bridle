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
import androidx.compose.ui.input.pointer.PointerEventPass
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

/**
 * The terminal itself: hosts the [TerminalScreenState.engine]'s view and connects it to [state].
 *
 * - The grid is fitted to the pane (and refitted when it or the font size changes), and the
 *   remote PTY follows ([TerminalScreenState.onViewportChanged]).
 * - Pinch zooms the font. termlib magnifies while the fingers move; the new size is applied when
 *   they lift, within [TerminalScreenState.fontBounds].
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
                .pointerInput(Unit) { detectPinch { currentState.zoomBy(it) } },
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
