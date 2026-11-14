package com.kitefoldlabs.bridle.core.terminal

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StickyModifiersTest {
    private val sticky = StickyModifiers()
    private val changes = mutableListOf<StickyState>()

    init {
        sticky.onStateChanged = { changes += it }
    }

    @Test
    fun `single tap applies to the next key only`() {
        sticky.tap(StickyModifier.CTRL, nowMillis = 1_000)
        sticky.state.ctrl shouldBe ModifierState.ONCE
        sticky.active shouldBe Modifiers.CTRL
        sticky.consume() shouldBe Modifiers.CTRL
        sticky.state shouldBe StickyState()
        sticky.consume() shouldBe Modifiers.NONE
        changes shouldBe listOf(StickyState(ctrl = ModifierState.ONCE), StickyState())
    }

    @Test
    fun `double tap locks until tapped again`() {
        sticky.tap(StickyModifier.ALT, nowMillis = 1_000)
        sticky.tap(StickyModifier.ALT, nowMillis = 1_000 + StickyModifiers.DEFAULT_DOUBLE_TAP_WINDOW_MILLIS)
        sticky.state.alt shouldBe ModifierState.LOCKED
        sticky.consume() shouldBe Modifiers.ALT
        sticky.consume() shouldBe Modifiers.ALT
        sticky.tap(StickyModifier.ALT, nowMillis = 5_000)
        sticky.state.alt shouldBe ModifierState.OFF
    }

    @Test
    fun `a slow second tap cancels instead of locking`() {
        sticky.tap(StickyModifier.CTRL, nowMillis = 1_000)
        sticky.tap(StickyModifier.CTRL, nowMillis = 1_000 + StickyModifiers.DEFAULT_DOUBLE_TAP_WINDOW_MILLIS + 1)
        sticky.state.ctrl shouldBe ModifierState.OFF
    }

    @Test
    fun `a clock that goes backwards is not a double tap`() {
        sticky.tap(StickyModifier.CTRL, nowMillis = 1_000)
        sticky.tap(StickyModifier.CTRL, nowMillis = 900)
        sticky.state.ctrl shouldBe ModifierState.OFF
    }

    @Test
    fun `double tap window is per modifier`() {
        val custom = StickyModifiers(doubleTapWindowMillis = 50)
        custom.doubleTapWindowMillis shouldBe 50
        custom.tap(StickyModifier.CTRL, nowMillis = 0)
        custom.tap(StickyModifier.ALT, nowMillis = 10)
        custom.state shouldBe StickyState(ctrl = ModifierState.ONCE, alt = ModifierState.ONCE)
        custom.tap(StickyModifier.CTRL, nowMillis = 20)
        custom.state shouldBe StickyState(ctrl = ModifierState.LOCKED, alt = ModifierState.ONCE)
        custom.state[StickyModifier.CTRL] shouldBe ModifierState.LOCKED
        custom.state[StickyModifier.ALT] shouldBe ModifierState.ONCE
    }

    @Test
    fun `sticky shift applies to the next key only`() {
        sticky.tap(StickyModifier.SHIFT, nowMillis = 0)
        sticky.state.shift shouldBe ModifierState.ONCE
        sticky.consume() shouldBe Modifiers.SHIFT
        sticky.state shouldBe StickyState()
    }

    @Test
    fun `hardware modifiers are combined with sticky ones`() {
        sticky.tap(StickyModifier.CTRL, nowMillis = 0)
        sticky.consume(Modifiers.SHIFT) shouldBe Modifiers(ctrl = true, shift = true)
        sticky.consume(Modifiers.META) shouldBe Modifiers.META
    }

    @Test
    fun `locked and once modifiers combine and release independently`() {
        sticky.tap(StickyModifier.CTRL, nowMillis = 0)
        sticky.tap(StickyModifier.CTRL, nowMillis = 100)
        sticky.tap(StickyModifier.ALT, nowMillis = 1_000)
        sticky.consume() shouldBe Modifiers(ctrl = true, alt = true)
        sticky.state shouldBe StickyState(ctrl = ModifierState.LOCKED)
    }

    @Test
    fun `reset turns everything off and forgets taps`() {
        sticky.tap(StickyModifier.CTRL, nowMillis = 0)
        sticky.reset()
        sticky.state shouldBe StickyState()
        // The earlier tap no longer counts towards a double tap.
        sticky.tap(StickyModifier.CTRL, nowMillis = 10)
        sticky.state.ctrl shouldBe ModifierState.ONCE
    }

    @Test
    fun `works without a listener`() {
        val quiet = StickyModifiers()
        quiet.onStateChanged shouldBe null
        quiet.tap(StickyModifier.ALT, nowMillis = 0)
        quiet.consume() shouldBe Modifiers.ALT
    }

    @Test
    fun `state snapshot reports modifiers`() {
        StickyState(alt = ModifierState.LOCKED).modifiers shouldBe Modifiers.ALT
        StickyState().modifiers shouldBe Modifiers.NONE
    }
}
