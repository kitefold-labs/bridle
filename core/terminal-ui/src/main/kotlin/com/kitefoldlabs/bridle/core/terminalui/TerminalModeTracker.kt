package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.terminal.MouseReporting
import com.kitefoldlabs.bridle.core.terminal.ShellEvent
import com.kitefoldlabs.bridle.core.terminal.ShellEvents
import com.kitefoldlabs.bridle.core.terminal.ShellPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Follows the few terminal modes the input layer needs, by watching the remote output stream:
 * bracketed paste (`CSI ? 2004 h/l`), application cursor keys (DECCKM, `CSI ? 1 h/l`), the
 * alternate screen (`CSI ? 47 / 1047 / 1049 h/l`), mouse reporting (`CSI ? 1000 / 1002 / 1003`
 * and `1006 h/l`), the window title (OSC 0 / OSC 2), and the shell integration's prompt marks
 * (OSC 133) and working directory (OSC 7).
 *
 * termlib keeps these inside libvterm without exposing them, so the engine feeds every output
 * byte through this tracker as well. It is a small VT500-style parser: sequences split across
 * chunks are handled, DCS/SOS/PM/APC strings (including tmux passthrough) are skipped, CAN and
 * SUB abort a sequence, and RIS (`ESC c`) and DECSTR (`CSI ! p`) reset the modes, as libvterm
 * does (only RIS leaves the alternate screen). OSC strings and CSI parameters are capped, so
 * hostile output cannot grow memory.
 *
 * [feed] must be called from one thread at a time, which is also the thread [onShellEvent] runs
 * on; the modes, [title] and [shellPrompt] can be read anywhere.
 */
public class TerminalModeTracker {
    private val mutableTitle = MutableStateFlow("")

    /** The last title set with OSC 0 or OSC 2, without control or bidirectional formatting characters. */
    public val title: StateFlow<String> = mutableTitle.asStateFlow()

    private val mutableShellPrompt = MutableStateFlow(ShellPrompt())

    /** The shell integration's state, folded from every [ShellEvent] seen. */
    public val shellPrompt: StateFlow<ShellPrompt> = mutableShellPrompt.asStateFlow()

    /** Called with each [ShellEvent] in output order, after [shellPrompt] has been updated. */
    @Volatile
    public var onShellEvent: ((ShellEvent) -> Unit)? = null

    @Volatile
    public var isBracketedPasteEnabled: Boolean = false
        private set

    @Volatile
    public var isApplicationCursorKeys: Boolean = false
        private set

    /** True while the remote program is on the alternate screen (vim, less, an attached tmux). */
    @Volatile
    public var isAlternateScreen: Boolean = false
        private set

    @Volatile
    private var mouseTracking: Boolean = false

    @Volatile
    private var sgrMouse: Boolean = false

    /** How the remote program wants mouse events, if at all. */
    public val mouseReporting: MouseReporting
        get() =
            when {
                !mouseTracking -> MouseReporting.OFF
                sgrMouse -> MouseReporting.SGR
                else -> MouseReporting.X10
            }

    private var state = State.GROUND
    private var escapeIntermediate = false

    // CSI
    private var marker = NO_MARKER
    private var intermediate = NO_INTERMEDIATE
    private val params = IntArray(MAX_PARAMS)
    private var paramCount = 0
    private var current = DEFAULT_PARAM

    // OSC
    private val osc = ByteArray(MAX_OSC_LENGTH)
    private var oscLength = 0
    private var oscOverflow = false

    /** Processes [length] bytes of remote output starting at [offset]. */
    public fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ) {
        for (i in offset until offset + length) process(bytes[i].toInt() and BYTE_MASK)
    }

    private fun process(b: Int) {
        if (b == CAN || b == SUB) {
            state = State.GROUND
            return
        }
        when (state) {
            State.GROUND -> if (b == ESC) enterEscape()
            State.ESCAPE -> escape(b)
            State.CSI -> csi(b)
            State.OSC -> oscByte(b)
            State.OSC_ESCAPE ->
                if (b == ST_FINAL) {
                    finishOsc()
                    state = State.GROUND
                } else {
                    // An ESC that does not start ST abandons the string and starts a new sequence.
                    enterEscape()
                    process(b)
                }
            State.STRING -> if (b == ESC) state = State.STRING_ESCAPE
            // ESC ESC is an escaped ESC (tmux passthrough doubles them); only ESC \ ends the string.
            State.STRING_ESCAPE -> state = if (b == ST_FINAL) State.GROUND else State.STRING
        }
    }

    private fun enterEscape() {
        state = State.ESCAPE
        escapeIntermediate = false
    }

    private fun escape(b: Int) {
        when {
            b == ESC -> enterEscape()
            b in INTERMEDIATES -> escapeIntermediate = true
            escapeIntermediate -> state = State.GROUND // e.g. ESC ( B: a charset, nothing to track
            b == '['.code -> enterCsi()
            b == ']'.code -> enterOsc()
            b == 'P'.code || b == 'X'.code || b == '^'.code || b == '_'.code -> state = State.STRING
            b == 'c'.code -> {
                resetModes()
                isAlternateScreen = false
                state = State.GROUND
            }
            b < SPACE -> Unit // C0 controls inside an escape are executed; nothing to track
            else -> state = State.GROUND
        }
    }

    private fun enterCsi() {
        state = State.CSI
        marker = NO_MARKER
        intermediate = NO_INTERMEDIATE
        paramCount = 0
        current = DEFAULT_PARAM
    }

    private fun csi(b: Int) {
        when (b) {
            ESC -> enterEscape()
            in DIGITS -> {
                val digit = b - '0'.code
                current = if (current == DEFAULT_PARAM) digit else minOf(current * DECIMAL + digit, MAX_PARAM_VALUE)
            }
            SEMICOLON, COLON -> pushParam()
            in PRIVATE_MARKERS ->
                // A marker is only valid before the parameters; otherwise the sequence is ignored.
                marker = if (marker == NO_MARKER && paramCount == 0 && current == DEFAULT_PARAM) b else INVALID
            in INTERMEDIATES -> intermediate = b
            in FINALS -> {
                pushParam()
                dispatchCsi(b)
                state = State.GROUND
            }
            else -> Unit // C0 controls are executed mid-sequence; DEL and 8-bit bytes are ignored
        }
    }

    private fun pushParam() {
        if (paramCount < MAX_PARAMS) params[paramCount++] = current
        current = DEFAULT_PARAM
    }

    private fun dispatchCsi(final: Int) {
        when {
            marker == '?'.code && intermediate == NO_INTERMEDIATE && (final == 'h'.code || final == 'l'.code) -> {
                val on = final == 'h'.code
                for (i in 0 until paramCount) {
                    when (params[i]) {
                        MODE_APPLICATION_CURSOR -> isApplicationCursorKeys = on
                        MODE_BRACKETED_PASTE -> isBracketedPasteEnabled = on
                        MODE_ALT_SCREEN, MODE_ALT_SCREEN_CLEAR, MODE_ALT_SCREEN_SAVE_CURSOR -> isAlternateScreen = on
                        MODE_MOUSE_PRESS, MODE_MOUSE_DRAG, MODE_MOUSE_ANY -> mouseTracking = on
                        MODE_MOUSE_SGR -> sgrMouse = on
                    }
                }
            }
            marker == NO_MARKER && intermediate == '!'.code && final == 'p'.code -> resetModes()
        }
    }

    private fun enterOsc() {
        state = State.OSC
        oscLength = 0
        oscOverflow = false
    }

    private fun oscByte(b: Int) {
        when (b) {
            BEL -> {
                finishOsc()
                state = State.GROUND
            }
            ESC -> state = State.OSC_ESCAPE
            else ->
                if (oscLength < osc.size) {
                    osc[oscLength++] = b.toByte()
                } else {
                    oscOverflow = true
                }
        }
    }

    private fun finishOsc() {
        if (oscOverflow) return
        val text = String(osc, 0, oscLength, Charsets.UTF_8)
        val separator = text.indexOf(';')
        if (separator < 0) return
        when (text.substring(0, separator)) {
            "0", "2" -> mutableTitle.value = cleanTitle(text.substring(separator + 1))
            "7", "133" -> ShellEvents.parseOsc(text)?.let(::shellEvent)
        }
    }

    private fun shellEvent(event: ShellEvent) {
        mutableShellPrompt.value = mutableShellPrompt.value.after(event)
        onShellEvent?.invoke(event)
    }

    /** The modes both RIS and DECSTR turn off; the alternate screen only ends with RIS. */
    private fun resetModes() {
        isBracketedPasteEnabled = false
        isApplicationCursorKeys = false
        mouseTracking = false
        sgrMouse = false
    }

    private enum class State { GROUND, ESCAPE, CSI, OSC, OSC_ESCAPE, STRING, STRING_ESCAPE }

    public companion object {
        /** Longest OSC string considered; longer ones (inline images, OSC 52) are skipped. */
        public const val MAX_OSC_LENGTH: Int = 4096

        /** Titles are cut to this many characters. */
        public const val MAX_TITLE_LENGTH: Int = 256

        private const val MAX_PARAMS = 16
        private const val MAX_PARAM_VALUE = 99_999
        private const val DECIMAL = 10
        private const val DEFAULT_PARAM = -1
        private const val NO_MARKER = 0
        private const val INVALID = -1
        private const val NO_INTERMEDIATE = 0
        private const val MODE_APPLICATION_CURSOR = 1
        private const val MODE_BRACKETED_PASTE = 2004
        private const val MODE_ALT_SCREEN = 47
        private const val MODE_ALT_SCREEN_CLEAR = 1047
        private const val MODE_ALT_SCREEN_SAVE_CURSOR = 1049
        private const val MODE_MOUSE_PRESS = 1000
        private const val MODE_MOUSE_DRAG = 1002
        private const val MODE_MOUSE_ANY = 1003
        private const val MODE_MOUSE_SGR = 1006

        private const val BYTE_MASK = 0xFF
        private const val BEL = 0x07
        private const val CAN = 0x18
        private const val SUB = 0x1A
        private const val ESC = 0x1B
        private const val SPACE = 0x20
        private const val SEMICOLON = 0x3B
        private const val COLON = 0x3A
        private const val ST_FINAL = 0x5C // '\'
        private val DIGITS = 0x30..0x39
        private val PRIVATE_MARKERS = 0x3C..0x3F
        private val INTERMEDIATES = 0x20..0x2F
        private val FINALS = 0x40..0x7E

        /**
         * Bidirectional formatting characters: in a title they could make a tab or notification
         * read differently from what the host sent (e.g. RLO reversing `exe.txt`).
         */
        private fun isBidiControl(c: Char): Boolean =
            c == '\u061C' || c == '\u200E' || c == '\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

        private fun cleanTitle(raw: String): String {
            val printable = raw.filterNot { it.isISOControl() || isBidiControl(it) }
            if (printable.length <= MAX_TITLE_LENGTH) return printable
            // Don't cut a surrogate pair in half.
            val end = if (printable[MAX_TITLE_LENGTH - 1].isHighSurrogate()) MAX_TITLE_LENGTH - 1 else MAX_TITLE_LENGTH
            return printable.substring(0, end)
        }
    }
}
