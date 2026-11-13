package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.terminal.ModifierState
import com.kitefoldlabs.bridle.core.terminal.StickyModifiers
import org.connectbot.terminal.ModifierManager

/**
 * Lets termlib's keyboard handling (soft keyboard and hardware keys) apply the key bar's sticky
 * Ctrl/Alt: tap CTRL on the bar, then `c` on the keyboard, and `^C` is sent. termlib calls
 * [clearTransients] after each key, which releases single-tap modifiers like a key-bar press.
 *
 * Sticky Shift is not passed on: termlib sends soft-keyboard text to libvterm with the Shift
 * flag instead of upper-casing it, and libvterm drops Shift for every character except Space,
 * which it turns into the CSI-u sequence `ESC [ 3 2 ; 2 u`. So the bar's SHIFT applies to the
 * bar's own keys only; the keyboards have their own Shift.
 */
internal class StickyModifierBridge(
    private val sticky: StickyModifiers,
) : ModifierManager {
    override fun isCtrlActive(): Boolean = sticky.state.ctrl != ModifierState.OFF

    override fun isAltActive(): Boolean = sticky.state.alt != ModifierState.OFF

    override fun isShiftActive(): Boolean = false

    override fun clearTransients() {
        sticky.consume()
    }
}
