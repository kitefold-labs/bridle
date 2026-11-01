package com.kitefoldlabs.bridle.core.terminal

/** What the Backspace key sends. Most modern hosts (and tmux) expect [DEL]. */
public enum class BackspaceMode {
    /** `0x7f` (DEL, `^?`): the xterm, tmux and Linux-console default. */
    DEL,

    /** `0x08` (BS, `^H`): for hosts whose `stty erase` is `^H`. */
    CTRL_H,
}

/**
 * Encodes key presses into the bytes an xterm-compatible terminal expects, as consumed by tmux,
 * readline and full-screen TUIs such as Claude Code.
 *
 * The encoder is stateless apart from its options. The one terminal mode that changes key
 * encoding, DECCKM (application cursor keys, `CSI ? 1 h`), is tracked by the emulator and
 * passed per call as `applicationCursorKeys`.
 *
 * Primary reference: xterm "Control Sequences" (ctlseqs), sections "PC-Style Function Keys"
 * and "VT220-Style Function Keys". `CSI` below is `ESC [` and `SS3` is `ESC O`.
 */
public class KeyEncoder(
    public val backspace: BackspaceMode = BackspaceMode.DEL,
) {
    /**
     * Encodes a non-character [key].
     *
     * | Key | Plain | Modified (`m` = [Modifiers.xtermParameter]) |
     * |-----|-------|-----------------------------------------------|
     * | Enter | `CR` | Alt/Meta: `ESC CR` |
     * | Tab | `HT` | Shift: `CSI Z`; Alt/Meta: ESC prefix |
     * | Backspace | `DEL` (or `BS`, see [BackspaceMode]) | Ctrl: the other one; Alt/Meta: ESC prefix |
     * | Escape | `ESC` | Alt/Meta: `ESC ESC` |
     * | Up/Down/Right/Left | `CSI A/B/C/D`, `SS3 A/B/C/D` in DECCKM | `CSI 1 ; m A` … |
     * | Home/End | `CSI H/F`, `SS3 H/F` in DECCKM | `CSI 1 ; m H/F` |
     * | Insert/Delete/PageUp/PageDown | `CSI 2~` / `3~` / `5~` / `6~` | `CSI n ; m ~` |
     * | F1–F4 | `SS3 P/Q/R/S` | `CSI 1 ; m P/Q/R/S` |
     * | F5–F12 | `CSI 15~ 17~ 18~ 19~ 20~ 21~ 23~ 24~` | `CSI n ; m ~` |
     */
    public fun encode(
        key: Key,
        modifiers: Modifiers = Modifiers.NONE,
        applicationCursorKeys: Boolean = false,
    ): ByteArray =
        when (key) {
            // ctlseqs: Enter sends CR (unless LNM is set, which no modern host uses).
            Key.Enter -> simple(CR, modifiers)
            // ctlseqs "CSI Ps Z" (CBT, back-tab) is what xterm sends for Shift+Tab.
            Key.Tab -> if (modifiers.shift) escapePrefixed(ascii("${CSI}Z"), modifiers) else simple(HT, modifiers)
            Key.Backspace -> simple(backspaceByte(modifiers.ctrl), modifiers)
            Key.Escape -> simple(ESC, modifiers)
            // ctlseqs "PC-Style Function Keys": cursor keys honour DECCKM only when unmodified.
            Key.Up -> cursor('A', modifiers, applicationCursorKeys)
            Key.Down -> cursor('B', modifiers, applicationCursorKeys)
            Key.Right -> cursor('C', modifiers, applicationCursorKeys)
            Key.Left -> cursor('D', modifiers, applicationCursorKeys)
            Key.Home -> cursor('H', modifiers, applicationCursorKeys)
            Key.End -> cursor('F', modifiers, applicationCursorKeys)
            // ctlseqs "VT220-Style Function Keys": editing keypad uses CSI n ~.
            Key.Insert -> tilde(INSERT, modifiers)
            Key.Delete -> tilde(DELETE, modifiers)
            Key.PageUp -> tilde(PAGE_UP, modifiers)
            Key.PageDown -> tilde(PAGE_DOWN, modifiers)
            // ctlseqs: F1–F4 are SS3 P..S unmodified, CSI 1 ; m P..S when modified.
            Key.F1 -> ss3Function('P', modifiers)
            Key.F2 -> ss3Function('Q', modifiers)
            Key.F3 -> ss3Function('R', modifiers)
            Key.F4 -> ss3Function('S', modifiers)
            // ctlseqs: F5–F12 use CSI n ~ with the historical gaps at 16 and 22.
            Key.F5 -> tilde(F5_CODE, modifiers)
            Key.F6 -> tilde(F6_CODE, modifiers)
            Key.F7 -> tilde(F7_CODE, modifiers)
            Key.F8 -> tilde(F8_CODE, modifiers)
            Key.F9 -> tilde(F9_CODE, modifiers)
            Key.F10 -> tilde(F10_CODE, modifiers)
            Key.F11 -> tilde(F11_CODE, modifiers)
            Key.F12 -> tilde(F12_CODE, modifiers)
        }

    /**
     * Encodes one typed character (a Unicode [codePoint]) with [modifiers].
     *
     * - Shift upper-cases the character (the IME normally does this already).
     * - Ctrl maps to a C0 control code using the classic VT/xterm table: `Ctrl+@`/`Ctrl+Space`/
     *   `Ctrl+2` → NUL, `Ctrl+A`…`Ctrl+Z` → 0x01–0x1a (either case), `Ctrl+[`/`Ctrl+3` → ESC,
     *   `Ctrl+\`/`Ctrl+4` → FS, `Ctrl+]`/`Ctrl+5` → GS, `Ctrl+^`/`Ctrl+6` → RS,
     *   `Ctrl+_`/`Ctrl+7`/`Ctrl+/` → US, `Ctrl+?`/`Ctrl+8` → DEL. Characters with no control
     *   equivalent are sent unchanged.
     * - Alt/Meta prefix the result with ESC (xterm `metaSendsEscape`).
     * - Everything is encoded as UTF-8.
     *
     * @throws IllegalArgumentException if [codePoint] is not a Unicode scalar value.
     */
    public fun encodeChar(
        codePoint: Int,
        modifiers: Modifiers = Modifiers.NONE,
    ): ByteArray {
        require(isScalarValue(codePoint)) { "Not a Unicode scalar value: $codePoint" }
        val shifted = if (modifiers.shift) Character.toUpperCase(codePoint) else codePoint
        val control = if (modifiers.ctrl) controlCode(shifted) else null
        val base = if (control != null) byteArrayOf(control.toByte()) else utf8(shifted)
        return if (modifiers.escapePrefix) byteArrayOf(ESC) + base else base
    }

    /** Encodes typed [text] (for example an IME commit) as UTF-8, without modifiers. */
    public fun encodeText(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun backspaceByte(ctrl: Boolean): Byte {
        // xterm: Ctrl+Backspace sends the "other" erase character.
        val sendsDel = (backspace == BackspaceMode.DEL) != ctrl
        return if (sendsDel) DEL else BS
    }

    internal companion object {
        const val ESC: Byte = 0x1b
        const val CR: Byte = 0x0d
        const val HT: Byte = 0x09
        const val BS: Byte = 0x08
        const val DEL: Byte = 0x7f
        const val CSI: String = "\u001b["
        const val SS3: String = "\u001bO"

        private const val INSERT = 2
        private const val DELETE = 3
        private const val PAGE_UP = 5
        private const val PAGE_DOWN = 6
        private const val F5_CODE = 15
        private const val F6_CODE = 17
        private const val F7_CODE = 18
        private const val F8_CODE = 19
        private const val F9_CODE = 20
        private const val F10_CODE = 21
        private const val F11_CODE = 23
        private const val F12_CODE = 24

        private const val NUL_CODE = 0x00
        private const val ESC_CODE = 0x1b
        private const val FS_CODE = 0x1c
        private const val GS_CODE = 0x1d
        private const val RS_CODE = 0x1e
        private const val US_CODE = 0x1f
        private const val DEL_CODE = 0x7f
        private const val CONTROL_MASK = 0x1f
        private val SURROGATES = 0xD800..0xDFFF

        fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

        fun utf8(codePoint: Int): ByteArray = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)

        fun isScalarValue(codePoint: Int): Boolean = Character.isValidCodePoint(codePoint) && codePoint !in SURROGATES

        /** A one-byte key, ESC-prefixed when Alt/Meta is held. */
        private fun simple(
            byte: Byte,
            modifiers: Modifiers,
        ): ByteArray = escapePrefixed(byteArrayOf(byte), modifiers)

        private fun escapePrefixed(
            bytes: ByteArray,
            modifiers: Modifiers,
        ): ByteArray = if (modifiers.escapePrefix) byteArrayOf(ESC) + bytes else bytes

        /** Cursor and Home/End keys: `CSI x`, `SS3 x` in DECCKM, `CSI 1 ; m x` when modified. */
        private fun cursor(
            final: Char,
            modifiers: Modifiers,
            applicationCursorKeys: Boolean,
        ): ByteArray =
            when {
                !modifiers.isEmpty -> ascii("${CSI}1;${modifiers.xtermParameter}$final")
                applicationCursorKeys -> ascii("$SS3$final")
                else -> ascii("$CSI$final")
            }

        /** F1–F4: `SS3 x`, or `CSI 1 ; m x` when modified. */
        private fun ss3Function(
            final: Char,
            modifiers: Modifiers,
        ): ByteArray =
            if (modifiers.isEmpty) ascii("$SS3$final") else ascii("${CSI}1;${modifiers.xtermParameter}$final")

        /** VT220-style keys: `CSI n ~`, or `CSI n ; m ~` when modified. */
        private fun tilde(
            code: Int,
            modifiers: Modifiers,
        ): ByteArray = if (modifiers.isEmpty) ascii("$CSI$code~") else ascii("$CSI$code;${modifiers.xtermParameter}~")

        /** The C0 control code for Ctrl+[codePoint], or null if it has none. */
        private fun controlCode(codePoint: Int): Int? =
            when (codePoint) {
                // '@' (0x40) through '_' (0x5f) map to 0x00-0x1f by clearing the top bits.
                in '@'.code..'_'.code -> codePoint and CONTROL_MASK
                in 'a'.code..'z'.code -> codePoint and CONTROL_MASK
                ' '.code, '2'.code -> NUL_CODE
                '3'.code -> ESC_CODE
                '4'.code -> FS_CODE
                '5'.code -> GS_CODE
                '6'.code -> RS_CODE
                '7'.code, '/'.code -> US_CODE
                '?'.code, '8'.code -> DEL_CODE
                else -> null
            }
    }
}
