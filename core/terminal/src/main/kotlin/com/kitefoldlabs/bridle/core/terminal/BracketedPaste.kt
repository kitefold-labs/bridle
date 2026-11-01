package com.kitefoldlabs.bridle.core.terminal

/**
 * Turns pasted (or compose-box) text into bytes that are safe to write to the terminal.
 *
 * When the remote application has enabled bracketed-paste mode (DEC private mode 2004,
 * `CSI ? 2004 h`), the text is wrapped in [START] … [END] so the application can tell it apart
 * from typing: shells don't run each line, and Claude Code keeps a multi-line prompt together.
 *
 * Text is always sanitised first, whether or not the mode is on:
 * - an embedded [END] (or [START]) marker is removed. Otherwise pasted text could end the paste
 *   early and have the rest interpreted as typed keystrokes (the "paste injection" attack);
 * - `\r\n` and `\n` become `\r`, which is what the Enter key sends;
 * - every other control character is removed: C0 (including NUL and ESC) except tab and CR,
 *   DEL, and C1 (U+0080–U+009F, since U+009B is an 8-bit CSI to some terminals).
 *
 * Because ESC and 8-bit CSI never survive sanitising, no escape sequence of any kind, and in
 * particular no paste terminator, can appear inside the payload.
 *
 * Reference: xterm ctlseqs, "Bracketed Paste Mode".
 */
public object BracketedPaste {
    /** Start-of-paste marker, `ESC [ 200 ~`. */
    public const val START: String = "\u001b[200~"

    /** End-of-paste marker, `ESC [ 201 ~`. */
    public const val END: String = "\u001b[201~"

    /** Returns [text] with paste markers and control characters removed and newlines as CR. */
    public fun sanitize(text: String): String {
        val unmarked = text.replace(END, "").replace(START, "")
        val out = StringBuilder(unmarked.length)
        var i = 0
        while (i < unmarked.length) {
            val c = unmarked[i]
            when {
                c == '\r' -> {
                    out.append('\r')
                    if (unmarked.getOrNull(i + 1) == '\n') i++
                }
                c == '\n' -> out.append('\r')
                c == '\t' -> out.append(c)
                isTerminalControl(c) -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /**
     * Sanitises [text] and encodes it as UTF-8, wrapped in paste markers when
     * [bracketedPasteMode] is on, followed by a CR when [sendEnter] is set (the compose box's
     * "send" action). The CR is outside the markers so it submits rather than inserts a newline.
     */
    public fun encode(
        text: String,
        bracketedPasteMode: Boolean,
        sendEnter: Boolean = false,
    ): ByteArray {
        val body = sanitize(text)
        val payload = if (bracketedPasteMode) START + body + END else body
        val withEnter = if (sendEnter) "$payload\r" else payload
        return withEnter.toByteArray(Charsets.UTF_8)
    }

    /**
     * True when pasting [text] without bracketed-paste mode would submit one or more lines
     * immediately (it contains a line break). The UI should ask before sending such a paste.
     */
    public fun needsConfirmation(
        text: String,
        bracketedPasteMode: Boolean,
    ): Boolean = !bracketedPasteMode && sanitize(text).contains('\r')
}
