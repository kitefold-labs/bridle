package com.kitefoldlabs.bridle.core.terminal

import kotlinx.serialization.Serializable

/**
 * Non-character keys the terminal input layer knows how to encode. Printable input (letters,
 * digits, punctuation, emoji) is sent as a code point via [KeyEncoder.encodeChar] instead.
 *
 * The constant names are the JSON names used in key-bar layouts (see `docs/terminal-input.md`).
 */
@Serializable
public enum class Key {
    Enter,
    Tab,
    Backspace,
    Escape,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    Insert,
    Delete,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
}

/**
 * Modifier keys held (or latched via [StickyModifiers]) for one key press.
 *
 * [meta] is the "Super"/"Windows"/"Command" key on hardware keyboards. Terminals have no
 * separate byte for it, so on characters and simple keys it behaves like [alt] (ESC prefix,
 * xterm's `metaSendsEscape`); only xterm's modifier parameter distinguishes it.
 */
@Serializable
public data class Modifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    /** True when no modifier is held. */
    public val isEmpty: Boolean
        get() = !ctrl && !alt && !shift && !meta

    /** True when the key should be prefixed with ESC (Alt or Meta held). */
    internal val escapePrefix: Boolean
        get() = alt || meta

    /**
     * The xterm modifier parameter: `1 + shift·1 + alt·2 + ctrl·4 + meta·8`, as used in
     * `CSI 1 ; <m> A` and `CSI <n> ; <m> ~`.
     *
     * Reference: xterm ctlseqs, "PC-Style Function Keys", modifier table.
     */
    public val xtermParameter: Int
        get() = 1 + bit(shift, SHIFT_BIT) + bit(alt, ALT_BIT) + bit(ctrl, CTRL_BIT) + bit(meta, META_BIT)

    /** The union of both sets of modifiers, e.g. sticky on-screen Ctrl plus a hardware Shift. */
    public operator fun plus(other: Modifiers): Modifiers =
        Modifiers(
            ctrl = ctrl || other.ctrl,
            alt = alt || other.alt,
            shift = shift || other.shift,
            meta = meta || other.meta,
        )

    public companion object {
        private const val SHIFT_BIT = 1
        private const val ALT_BIT = 2
        private const val CTRL_BIT = 4
        private const val META_BIT = 8

        public val NONE: Modifiers = Modifiers()
        public val CTRL: Modifiers = Modifiers(ctrl = true)
        public val ALT: Modifiers = Modifiers(alt = true)
        public val SHIFT: Modifiers = Modifiers(shift = true)
        public val META: Modifiers = Modifiers(meta = true)

        private fun bit(
            set: Boolean,
            value: Int,
        ): Int = if (set) value else 0
    }
}
