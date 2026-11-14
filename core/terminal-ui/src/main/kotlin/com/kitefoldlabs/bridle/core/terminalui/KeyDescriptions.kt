package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.terminal.Key
import com.kitefoldlabs.bridle.core.terminal.KeyAction
import com.kitefoldlabs.bridle.core.terminal.Modifiers
import com.kitefoldlabs.bridle.core.terminal.StickyModifier

/**
 * Spoken names for key-bar actions, so TalkBack says "Control C" rather than "caret C" and
 * "Up arrow" rather than reading a glyph. Built from the action, not the label, so custom
 * layouts are described correctly too. Key names stay English, like the keys they name.
 */
internal object KeyDescriptions {
    fun describe(action: KeyAction): String =
        when (action) {
            is KeyAction.Press -> withModifiers(action.modifiers, keyName(action.key))
            is KeyAction.Chord -> withModifiers(action.modifiers, action.char.uppercase())
            is KeyAction.Toggle -> modifierName(action.modifier)
            is KeyAction.Text -> action.text
            is KeyAction.Macro -> action.actions.joinToString(", ") { describe(it) }
        }

    private fun keyName(key: Key): String =
        when (key) {
            Key.Up -> "Up arrow"
            Key.Down -> "Down arrow"
            Key.Left -> "Left arrow"
            Key.Right -> "Right arrow"
            Key.PageUp -> "Page up"
            Key.PageDown -> "Page down"
            else -> key.name
        }

    private fun modifierName(modifier: StickyModifier): String =
        when (modifier) {
            StickyModifier.CTRL -> "Control"
            StickyModifier.ALT -> "Alt"
            StickyModifier.SHIFT -> "Shift"
        }

    private fun withModifiers(
        modifiers: Modifiers,
        name: String,
    ): String =
        buildString {
            if (modifiers.ctrl) append("Control ")
            if (modifiers.alt) append("Alt ")
            if (modifiers.shift) append("Shift ")
            if (modifiers.meta) append("Meta ")
            append(name)
        }
}
