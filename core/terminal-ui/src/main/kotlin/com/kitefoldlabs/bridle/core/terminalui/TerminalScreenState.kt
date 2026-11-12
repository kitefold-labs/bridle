package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.terminal.BracketedPaste
import com.kitefoldlabs.bridle.core.terminal.ComposeHistory
import com.kitefoldlabs.bridle.core.terminal.InputDispatcher
import com.kitefoldlabs.bridle.core.terminal.Key
import com.kitefoldlabs.bridle.core.terminal.KeyAction
import com.kitefoldlabs.bridle.core.terminal.StickyModifiers
import com.kitefoldlabs.bridle.core.terminal.StickyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Pinch-zoom limits and the starting font size, in sp. */
public data class FontSizeBounds(
    val min: Float = 6f,
    val max: Float = 32f,
    val default: Float = 12f,
) {
    init {
        require(min > 0f && min <= default && default <= max) { "need 0 < min <= default <= max: $this" }
    }

    /** [sp] clamped to these bounds. */
    public fun clamp(sp: Float): Float = sp.coerceIn(min, max)
}

/** The size of one character cell in pixels, as the engine draws it. */
public data class CellMetrics(
    val widthPx: Float,
    val heightPx: Float,
)

/** Text waiting for the user to confirm, because sending it would run lines immediately. */
public data class PendingSend(
    val text: String,
    /** True for the compose box's Send (Enter follows), false for a plain paste. */
    val submit: Boolean,
) {
    /** How many lines would run. */
    public val lineCount: Int
        get() = BracketedPaste.sanitize(text).trimEnd('\r').count { it == '\r' } + 1
}

/**
 * State and actions for one terminal screen: it wires a [connection] (the session), an [engine]
 * and an [InputDispatcher] together and holds what the UI shows.
 *
 * Not UI code, so it is tested without a device: every property is Compose snapshot state and
 * every action is a plain method. Drive it from the main thread. It lives as long as the screen
 * (create it in a ViewModel with `viewModelScope` so it survives rotation, or with
 * [rememberTerminalScreenState]); the session and engine live in the service and outlive it.
 *
 * The remote PTY follows the engine's grid: whenever [TerminalEngine.size] settles for
 * [resizeDebounce] (a pinch, rotation or keyboard animation produces many sizes in a row), the
 * session is resized once.
 */
@Stable
public class TerminalScreenState(
    public val engine: TerminalEngine,
    private val connection: TerminalConnection,
    scope: CoroutineScope,
    public val history: ComposeHistory = ComposeHistory(),
    public val dispatcher: InputDispatcher = InputDispatcher(),
    public val fontBounds: FontSizeBounds = FontSizeBounds(),
    initialFontSizeSp: Float = fontBounds.default,
    private val resizeDebounce: Duration = DEFAULT_RESIZE_DEBOUNCE,
    private val clock: () -> Long = ::monotonicMillis,
) : TerminalViewHost {
    /** Connecting, open, reattaching or ended; drives [TerminalStatusBanner]. */
    public val terminalState: StateFlow<TerminalState> get() = connection.state

    /** The remote program's window title. */
    public val title: StateFlow<String> get() = engine.title

    override var fontSizeSp: Float by mutableFloatStateOf(fontBounds.clamp(initialFontSizeSp))
        private set

    /** Whether the user wants the soft keyboard (tap the terminal, or the keyboard button). */
    public var isKeyboardVisible: Boolean by mutableStateOf(false)
        private set

    /** Whether the soft keyboard has been reported visible since it was last reported hidden. */
    private var imeShown = false

    /** The terminal shows the keyboard when wanted, except while the compose box has it. */
    override val showSoftKeyboard: Boolean get() = isKeyboardVisible && !isComposeOpen

    override val stickyModifiers: StickyModifiers get() = dispatcher.sticky

    /** The sticky Ctrl/Alt state, for the key bar to render. */
    public var sticky: StickyState by mutableStateOf(dispatcher.sticky.state)
        private set

    public var isComposeOpen: Boolean by mutableStateOf(false)
        private set

    /** The compose box's text. */
    public var composeText: String by mutableStateOf("")
        private set

    /** A paste or compose-box send waiting for confirmation, or null. */
    public var pendingSend: PendingSend? by mutableStateOf(null)
        private set

    init {
        dispatcher.sticky.onStateChanged = { sticky = it }
        scope.launch {
            engine.size.collectLatest { size ->
                delay(resizeDebounce)
                connection.resize(size.columns, size.rows)
            }
        }
    }

    // ---- Terminal view -----------------------------------------------------------------------

    /** The terminal view is [widthPx]×[heightPx] with [cell]-sized characters: fit the grid. */
    public fun onViewportChanged(
        widthPx: Int,
        heightPx: Int,
        cell: CellMetrics,
    ) {
        fitGrid(widthPx, heightPx, cell)?.let(engine::resize)
    }

    override fun onTerminalTap() {
        isKeyboardVisible = true
    }

    /**
     * Only a keyboard that came up and then went away (the back gesture, another window) counts as
     * dismissed. The first report is always "hidden", and reports flip while a show is settling;
     * cancelling the request on those made termlib hide the keyboard, report again, and show it,
     * so the keyboard flashed until the reports happened to settle.
     */
    override fun onImeVisibilityChanged(visible: Boolean) {
        if (visible) {
            imeShown = true
            isKeyboardVisible = true
        } else if (imeShown) {
            imeShown = false
            isKeyboardVisible = false
        }
    }

    public fun toggleKeyboard() {
        isKeyboardVisible = !isKeyboardVisible
    }

    /** Multiplies the font size by [scale] (a finished pinch), within [fontBounds]. */
    public fun zoomBy(scale: Float) {
        setFontSize(fontSizeSp * scale)
    }

    public fun setFontSize(sp: Float) {
        fontSizeSp = fontBounds.clamp(sp)
    }

    /** Copies the emulator's selection to the clipboard and returns it, or null if none. */
    public fun copySelection(): String? = engine.copySelection()

    // ---- Key bar -----------------------------------------------------------------------------

    /** A key-bar key was tapped (its action) or swiped up (its alternate). */
    public fun onKeyBarAction(action: KeyAction) {
        val bytes = dispatcher.onKeyBarAction(action, clock(), engine.isApplicationCursorKeys)
        if (bytes.isNotEmpty()) connection.send(bytes)
    }

    /** Turns sticky Ctrl/Alt off, e.g. when leaving the screen. */
    public fun resetModifiers() {
        dispatcher.sticky.reset()
    }

    // ---- Paste and compose box ---------------------------------------------------------------

    /** Pastes [text] (sanitised and, when the remote wants it, bracketed). May ask first. */
    public fun paste(text: String) {
        if (text.isNotEmpty()) send(PendingSend(text, submit = false))
    }

    public fun openCompose() {
        isComposeOpen = true
    }

    public fun closeCompose() {
        isComposeOpen = false
        history.resetNavigation()
    }

    public fun updateComposeText(text: String) {
        composeText = text
    }

    /** Shows the next older history entry (compose box ↑). */
    public fun historyPrevious() {
        history.previous(composeText)?.let { composeText = it }
    }

    /** Shows the next newer entry, or the draft past the newest (compose box ↓). */
    public fun historyNext() {
        history.next()?.let { composeText = it }
    }

    /** The compose box's Send: pastes the text and presses Enter. May ask first. */
    public fun submitCompose() {
        if (composeText.isNotBlank()) send(PendingSend(composeText, submit = true))
    }

    /**
     * Enter in the compose box when Enter sends: sends the text like [submitCompose] or, when the
     * box is blank, presses Enter in the terminal.
     */
    public fun composeEnter() {
        if (composeText.isBlank()) {
            composeText = ""
            onKeyBarAction(KeyAction.Press(Key.Enter))
        } else {
            submitCompose()
        }
    }

    /** The user confirmed [pendingSend]. */
    public fun confirmPendingSend() {
        val pending = pendingSend ?: return
        pendingSend = null
        transmit(pending)
    }

    /** The user cancelled [pendingSend]; compose-box text stays for editing. */
    public fun dismissPendingSend() {
        pendingSend = null
    }

    private fun send(request: PendingSend) {
        if (BracketedPaste.needsConfirmation(request.text, engine.isBracketedPasteEnabled)) {
            pendingSend = request
        } else {
            transmit(request)
        }
    }

    private fun transmit(request: PendingSend) {
        connection.send(BracketedPaste.encode(request.text, engine.isBracketedPasteEnabled, request.submit))
        if (request.submit) {
            history.add(request.text)
            composeText = ""
        }
    }

    public companion object {
        public val DEFAULT_RESIZE_DEBOUNCE: Duration = 150.milliseconds

        /**
         * The grid that fits a [widthPx]×[heightPx] view with [cell]-sized characters (at least
         * 1×1), or null while the view or font is not measured yet.
         */
        public fun fitGrid(
            widthPx: Int,
            heightPx: Int,
            cell: CellMetrics,
        ): TerminalGridSize? {
            if (widthPx <= 0 || heightPx <= 0) return null
            if (cell.widthPx <= 0f || cell.heightPx <= 0f) return null
            return TerminalGridSize(
                columns = floor(widthPx / cell.widthPx).toInt().coerceAtLeast(1),
                rows = floor(heightPx / cell.heightPx).toInt().coerceAtLeast(1),
            )
        }
    }
}

private fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L
