package com.kitefoldlabs.bridle.core.terminal

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The shell's input line, rebuilt from the bytes Bridle sends and the shell integration's
 * [ShellEvent]s, without reading the screen (inside tmux the screen can't be trusted).
 *
 * The line is known only from a new prompt (`A`) while every byte sent since is understood:
 * printable text, DEL/BS, Ctrl-U, Ctrl-L, Ctrl-W where bash and zsh agree, and bracketed pastes
 * of printable text. Anything else (Enter, Tab, arrows, history, a tmux prefix key, mouse
 * reports) makes it unknown until the next prompt. Bytes sent while the line is not known and not
 * yet submitted with Enter or Ctrl-C (typeahead, a tmux pane switch) make the next prompt unknown
 * too, since the shell may already hold them.
 *
 * Inside tmux, codes carry their pane. While the line is known, or a command typed there runs,
 * codes from other panes are ignored; once it is unknown, the next `A`, `C` or `D` from any pane
 * takes over. Terminal replies to queries, which arrive on the same path as keystrokes, are
 * ignored.
 *
 * Thread-safe: events come from the output thread and input from the UI thread.
 * [onChange] runs on the calling thread, while the lock is held.
 */
public class PromptLine {
    /** What is known: [text] is the line typed at the prompt, or null when it isn't known. */
    public data class Snapshot(
        val text: String? = null,
        val pane: String? = null,
    )

    private enum class Mode { EDITING, RUNNING, UNKNOWN }

    private var mode = Mode.UNKNOWN
    private var text = StringBuilder()
    private var hasOwner = false
    private var owner: String? = null
    private var typedAhead = false

    /** Called after every change to [snapshot]. */
    @Volatile
    public var onChange: ((Snapshot) -> Unit)? = null

    @get:Synchronized
    public val snapshot: Snapshot
        get() = Snapshot(if (mode == Mode.EDITING) text.toString() else null, owner)

    @Synchronized
    public fun onShellEvent(event: ShellEvent) {
        if (event !is ShellEvent.Mark || event is ShellEvent.InputStart) return
        if (hasOwner && event.pane != owner && mode != Mode.UNKNOWN) return
        hasOwner = true
        owner = event.pane
        when (event) {
            is ShellEvent.PromptStart -> {
                mode = if (typedAhead) Mode.UNKNOWN else Mode.EDITING
                text = StringBuilder()
                typedAhead = false
            }
            else -> mode = Mode.RUNNING
        }
        changed()
    }

    /**
     * Forgets everything: the line is unknown until the next prompt, from any pane. For when the
     * connection drops or a tmux session is reattached, after which the shell's state is unknown.
     */
    @Synchronized
    public fun reset() {
        val before = snapshot
        mode = Mode.UNKNOWN
        text = StringBuilder()
        hasOwner = false
        owner = null
        typedAhead = false
        if (snapshot != before) changed()
    }

    /** Records [bytes] sent to the remote side, exactly as sent. */
    @Synchronized
    public fun onInput(bytes: ByteArray) {
        if (bytes.isEmpty() || isTerminalReply(bytes)) return
        val before = snapshot
        val decoded = decode(bytes)
        if (mode == Mode.EDITING && decoded != null && edit(decoded)) {
            // Understood: the line is still known.
        } else {
            if (mode == Mode.EDITING) mode = Mode.UNKNOWN
            typedAhead = !endsSubmitted(bytes)
        }
        if (snapshot != before) changed()
    }

    private fun changed() {
        onChange?.invoke(snapshot)
    }

    /** Applies [input] to the known line; false (line unchanged) if any of it isn't understood. */
    private fun edit(input: String): Boolean {
        val edited = StringBuilder(text)
        var i = 0
        while (i < input.length) i = step(input, i, edited) ?: return false
        text = edited
        return true
    }

    /** Applies the key or paste at [i] of [input] to [edited]; returns the next index, or null if not understood. */
    private fun step(
        input: String,
        i: Int,
        edited: StringBuilder,
    ): Int? {
        if (input.startsWith(PASTE_START, i)) return paste(input, i + PASTE_START.length, edited)
        val c = input[i]
        val understood =
            when {
                c == DEL || c == BACKSPACE -> true.also { deleteLastCodePoint(edited) }
                c == CTRL_U -> true.also { edited.setLength(0) }
                c == CTRL_L -> true
                c == CTRL_W -> rubOutWord(edited)
                isPrintable(c) -> true.also { edited.append(c) }
                else -> false
            }
        return if (understood) i + 1 else null
    }

    /** Appends a bracketed paste whose text starts at [start]; null if it is unfinished or not all printable. */
    private fun paste(
        input: String,
        start: Int,
        edited: StringBuilder,
    ): Int? {
        val end = input.indexOf(PASTE_END, start)
        val pasted = if (end < 0) null else input.substring(start, end)
        if (pasted == null || !pasted.all(::isPrintable)) return null
        edited.append(pasted)
        return end + PASTE_END.length
    }

    private fun deleteLastCodePoint(line: StringBuilder) {
        if (line.isEmpty()) return
        val last = line.length - 1
        val width = if (line.length >= 2 && line[last].isLowSurrogate() && line[last - 1].isHighSurrogate()) 2 else 1
        line.setLength(line.length - width)
    }

    /**
     * Ctrl-W: deletes trailing whitespace and the word before it, but only when the word is
     * letters, digits and `_ . ~ / -`, where readline's unix-word-rubout and zsh's
     * backward-kill-word (with the default WORDCHARS) delete the same text.
     */
    private fun rubOutWord(line: StringBuilder): Boolean {
        var end = line.length
        while (end > 0 && line[end - 1] == ' ') end--
        var start = end
        while (start > 0 && line[start - 1] != ' ') start--
        if (!line.substring(start, end).all { it.isLetterOrDigit() || it in WORD_PUNCTUATION }) return false
        line.setLength(start)
        return true
    }

    private fun isPrintable(c: Char): Boolean = !c.isISOControl() && c != '�'

    private fun decode(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    /** True if the last byte submits or abandons the line (Enter or Ctrl-C), so nothing is left pending. */
    private fun endsSubmitted(bytes: ByteArray): Boolean = bytes.last().toInt() in SUBMIT_BYTES

    private companion object {
        const val DEL = '\u007f'
        const val BACKSPACE = '\b'
        const val CTRL_U = '\u0015'
        const val CTRL_L = '\u000c'
        const val CTRL_W = '\u0017'
        const val PASTE_START = "\u001b[200~"
        const val PASTE_END = "\u001b[201~"
        const val WORD_PUNCTUATION = "_.~/-"
        val SUBMIT_BYTES = setOf('\r'.code, '\n'.code, 0x03)

        /**
         * Bytes on the keystroke path that never reach the line: the emulator's replies (cursor
         * position and device attribute reports, mode reports, focus events, window reports,
         * OSC/DCS replies such as colours and XTVERSION) and mouse events (X10 `CSI M b x y` and
         * SGR `CSI < b ; x ; y M/m`), which only a program that asked for the mouse receives.
         * A scroll gesture sends several mouse events in one write.
         */
        val TERMINAL_REPLY =
            Regex(
                "(?:\u001b\\[(?:\\d+;\\d+R|[?>=][\\d;]*c|\\??\\d+;\\d+\\\$y|[IO]|[\\d;]*t)" +
                    "|\u001b\\[(?:M[\\s\\S]{3}|<\\d+;\\d+;\\d+[Mm])" +
                    "|\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)" +
                    "|\u001bP[^\u001b]*\u001b\\\\)+",
            )

        fun isTerminalReply(bytes: ByteArray): Boolean = TERMINAL_REPLY.matches(String(bytes, Charsets.ISO_8859_1))
    }
}
