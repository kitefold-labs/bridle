package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.terminal.ModifierState
import com.kitefoldlabs.bridle.core.terminal.StickyModifiers
import org.connectbot.terminal.ModifierManager

/**
 * Lets termlib's keyboard handling (soft keyboard and hardware keys) apply the key bar's sticky
 * Ctrl/Alt: tap CTRL on the bar, then `c` on the keyboard, and `^C` is sent. termlib calls
 * [clearTransients] after each key, which releases single-tap modifiers like a key-bar press.
 */
internal class StickyModifierBridge(
    private val sticky: StickyModifiers,
) : ModifierManager {
    override fun isCtrlActive(): Boolean = sticky.state.ctrl != ModifierState.OFF

    override fun isAltActive(): Boolean = sticky.state.alt != ModifierState.OFF

    /** The key bar has no sticky Shift; the keyboard's own Shift still works. */
    override fun isShiftActive(): Boolean = false

    override fun clearTransients() {
        sticky.consume()
    }
}
