package com.kitefoldlabs.bridle.core.terminal

import kotlinx.serialization.Serializable

/** An on-screen modifier key that can be latched or locked. */
@Serializable
public enum class StickyModifier {
    CTRL,
    ALT,
    SHIFT,
}

/** The state of one on-screen modifier key. */
public enum class ModifierState {
    /** Not applied. */
    OFF,

    /** Applied to the next key press, then released (single tap). */
    ONCE,

    /** Applied to every key press until tapped again (double tap). */
    LOCKED,
}

/** A snapshot of the on-screen modifiers, for the UI to render. */
public data class StickyState(
    val ctrl: ModifierState = ModifierState.OFF,
    val alt: ModifierState = ModifierState.OFF,
    val shift: ModifierState = ModifierState.OFF,
) {
    /** The state of [modifier]. */
    public operator fun get(modifier: StickyModifier): ModifierState =
        when (modifier) {
            StickyModifier.CTRL -> ctrl
            StickyModifier.ALT -> alt
            StickyModifier.SHIFT -> shift
        }

    /** The modifiers the next key press would get. */
    public val modifiers: Modifiers
        get() =
            Modifiers(
                ctrl = ctrl != ModifierState.OFF,
                alt = alt != ModifierState.OFF,
                shift = shift != ModifierState.OFF,
            )

    internal fun with(
        modifier: StickyModifier,
        state: ModifierState,
    ): StickyState =
        when (modifier) {
            StickyModifier.CTRL -> copy(ctrl = state)
            StickyModifier.ALT -> copy(alt = state)
            StickyModifier.SHIFT -> copy(shift = state)
        }
}

/**
 * The state machine behind the key bar's sticky Ctrl/Alt/Shift keys (Termux-style):
 *
 * ```
 * OFF ──tap──▶ ONCE ──key press──▶ OFF
 *               │ └──tap after the double-tap window──▶ OFF
 *               └──tap within the double-tap window──▶ LOCKED ──tap──▶ OFF
 * ```
 *
 * Callers pass a monotonic clock reading (for example `SystemClock.uptimeMillis()`) so double
 * taps can be told apart from a tap that cancels a latched modifier. Not thread-safe: drive it
 * from the UI thread.
 */
public class StickyModifiers(
    public val doubleTapWindowMillis: Long = DEFAULT_DOUBLE_TAP_WINDOW_MILLIS,
) {
    /** The current state. */
    public var state: StickyState = StickyState()
        private set

    /** Called with the new state whenever it changes. */
    public var onStateChanged: ((StickyState) -> Unit)? = null

    private val lastTapAt = mutableMapOf<StickyModifier, Long>()

    /** The modifiers the next key press would get, without consuming them. */
    public val active: Modifiers
        get() = state.modifiers

    /** Handles a tap on the on-screen [modifier] key at [nowMillis]. */
    public fun tap(
        modifier: StickyModifier,
        nowMillis: Long,
    ) {
        val previous = lastTapAt.put(modifier, nowMillis)
        val doubleTap = previous != null && nowMillis - previous in 0..doubleTapWindowMillis
        val next =
            when (state[modifier]) {
                ModifierState.OFF -> ModifierState.ONCE
                ModifierState.ONCE -> if (doubleTap) ModifierState.LOCKED else ModifierState.OFF
                ModifierState.LOCKED -> ModifierState.OFF
            }
        update(state.with(modifier, next))
    }

    /**
     * Returns the modifiers for a key press: [hardware] (held on a physical keyboard) combined
     * with the on-screen ones. Single-tap modifiers are released; locked ones stay.
     */
    public fun consume(hardware: Modifiers = Modifiers.NONE): Modifiers {
        val effective = hardware + state.modifiers
        update(StickyState(ctrl = release(state.ctrl), alt = release(state.alt), shift = release(state.shift)))
        return effective
    }

    /** Turns every on-screen modifier off, for example when switching tabs. */
    public fun reset() {
        lastTapAt.clear()
        update(StickyState())
    }

    private fun update(next: StickyState) {
        if (next != state) {
            state = next
            onStateChanged?.invoke(next)
        }
    }

    public companion object {
        /** Matches Android's `ViewConfiguration.getDoubleTapTimeout()` default. */
        public const val DEFAULT_DOUBLE_TAP_WINDOW_MILLIS: Long = 300

        private fun release(state: ModifierState): ModifierState =
            if (state == ModifierState.ONCE) ModifierState.OFF else state
    }
}
