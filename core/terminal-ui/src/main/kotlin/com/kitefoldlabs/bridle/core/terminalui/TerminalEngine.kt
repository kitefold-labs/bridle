package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kitefoldlabs.bridle.core.terminal.MouseReporting
import com.kitefoldlabs.bridle.core.terminal.ShellEvent
import com.kitefoldlabs.bridle.core.terminal.ShellPrompt
import com.kitefoldlabs.bridle.core.terminal.StickyModifiers
import kotlinx.coroutines.flow.StateFlow

/** A terminal grid size in character cells. */
public data class TerminalGridSize(
    val columns: Int,
    val rows: Int,
) {
    init {
        require(columns > 0 && rows > 0) { "terminal size must be positive: ${columns}x$rows" }
    }
}

/**
 * A terminal emulator: it parses remote output, keeps the screen, and renders it.
 *
 * The rest of the terminal UI is written against this interface, so the emulator can be swapped
 * (termlib today, Termux's `terminal-view` as the fallback in ADR-0002) and so the UI can be
 * tested on the host JVM with a fake, where termlib's JNI library does not load.
 */
public interface TerminalEngine : AutoCloseable {
    /** The emulator's current grid. The remote PTY is resized to follow it. */
    public val size: StateFlow<TerminalGridSize>

    /** The window title set by the remote program (OSC 0 / OSC 2), or empty. */
    public val title: StateFlow<String>

    /** True while the remote program has bracketed-paste mode on (`CSI ? 2004 h`). */
    public val isBracketedPasteEnabled: Boolean

    /** True while the remote program has application cursor keys on (DECCKM, `CSI ? 1 h`). */
    public val isApplicationCursorKeys: Boolean

    /**
     * True while the remote program is on the alternate screen (`CSI ? 1049 h` and friends),
     * where nothing reaches the engine's scrollback.
     */
    public val isAlternateScreen: Boolean

    /** How the remote program wants mouse events (`CSI ? 1000 h` and friends), if at all. */
    public val mouseReporting: MouseReporting

    /** What the shell integration (OSC 133 and OSC 7) has reported. */
    public val shellPrompt: StateFlow<ShellPrompt>

    /**
     * Receives each shell integration event in output order, on the thread that calls [feed],
     * after [shellPrompt] reflects it.
     */
    public var onShellEvent: ((ShellEvent) -> Unit)?

    /**
     * Receives bytes the emulator generates for the remote side: replies to queries (device
     * attributes, cursor position reports) and input from the emulator's own keyboard handling.
     * May be called on any thread.
     */
    public var onRemoteInput: ((ByteArray) -> Unit)?

    /**
     * Feeds remote output. Calls come from one thread at a time (see [EngineSink]), and the
     * engine must not keep [bytes] after returning.
     */
    public fun feed(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    /** Resizes the grid. A no-op when the size is unchanged. */
    public fun resize(size: TerminalGridSize)

    /** Copies the current selection (if any) to the clipboard, clears it and returns its text. */
    public fun copySelection(): String?

    /** The size of a character cell at [fontSizePx], measured the way the engine draws text. */
    public fun cellMetrics(fontSizePx: Float): CellMetrics

    /**
     * Draws the screen and handles touch, selection and the soft keyboard, driven by [host].
     * [onPasteRequest] is called when the user picks Paste from the emulator's own menu; the host
     * pastes through [com.kitefoldlabs.bridle.core.terminal.BracketedPaste]. [onOpenUrl] is
     * called when the user taps a link.
     */
    @Composable
    public fun Content(
        host: TerminalViewHost,
        onPasteRequest: () -> Unit,
        onOpenUrl: (String) -> Unit,
        modifier: Modifier,
    )
}

/** What a [TerminalEngine]'s view reads from, and reports to, the screen around it. */
public interface TerminalViewHost {
    /** The font size in sp. */
    public val fontSizeSp: Float

    /** True when the terminal should show the soft keyboard. */
    public val showSoftKeyboard: Boolean

    /** The key bar's sticky Ctrl/Alt, which also apply to soft- and hardware-keyboard keys. */
    public val stickyModifiers: StickyModifiers

    /** The user tapped the terminal (not a link, not while selecting). */
    public fun onTerminalTap()

    /** The soft keyboard was shown or hidden (also by the system, e.g. the back gesture). */
    public fun onImeVisibilityChanged(visible: Boolean)
}
