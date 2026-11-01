package com.kitefoldlabs.bridle.core.terminal

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InputDispatcherTest {
    private val input = InputDispatcher()

    private fun action(
        action: KeyAction,
        now: Long = 0,
        app: Boolean = false,
    ) = input.onKeyBarAction(action, now, app).latin1()

    private val ctrl = KeyAction.Toggle(StickyModifier.CTRL)
    private val alt = KeyAction.Toggle(StickyModifier.ALT)

    @Test
    fun `defaults`() {
        input.encoder.backspace shouldBe BackspaceMode.DEL
        input.sticky.state shouldBe StickyState()
        input.onKeyBarAction(KeyAction.Press(Key.Up), nowMillis = 0).latin1() shouldBe "\u001b[A"
    }

    @Test
    fun `modifier toggle sends nothing and arms the modifier`() {
        action(ctrl) shouldBe ""
        input.sticky.state.ctrl shouldBe ModifierState.ONCE
    }

    @Test
    fun `sticky ctrl then a typed letter sends a control code once`() {
        action(ctrl)
        String(input.type("c"), Charsets.ISO_8859_1) shouldBe "\u0003"
        String(input.type("c"), Charsets.ISO_8859_1) shouldBe "c"
    }

    @Test
    fun `key actions combine their own and sticky modifiers`() {
        action(alt)
        action(KeyAction.Press(Key.Up, Modifiers.CTRL)) shouldBe "\u001b[1;7A"
        action(KeyAction.Press(Key.Up), app = true) shouldBe "\u001bOA"
    }

    @Test
    fun `char actions`() {
        action(KeyAction.Chord("c", Modifiers.CTRL)) shouldBe "\u0003"
        action(alt)
        action(KeyAction.Chord("x")) shouldBe "\u001bx"
    }

    @Test
    fun `single character text takes sticky modifiers, longer text does not`() {
        action(ctrl)
        action(KeyAction.Text("/")) shouldBe "\u001f"
        action(ctrl)
        action(KeyAction.Text("ls")) shouldBe "ls"
        input.sticky.state.ctrl shouldBe ModifierState.ONCE
    }

    @Test
    fun `macros run in order and ignore sticky modifiers`() {
        action(ctrl)
        val compact = KeyAction.Macro(listOf(KeyAction.Text("/compact"), KeyAction.Press(Key.Enter)))
        action(compact) shouldBe "/compact\r"
        input.sticky.state.ctrl shouldBe ModifierState.ONCE
    }

    @Test
    fun `macro steps of every kind`() {
        val macro =
            KeyAction.Macro(
                listOf(
                    KeyAction.Press(Key.Up),
                    KeyAction.Chord("c", Modifiers.CTRL),
                    KeyAction.Text("é"),
                    KeyAction.Toggle(StickyModifier.ALT),
                    KeyAction.Macro(listOf(KeyAction.Text("nested"))),
                ),
            )
        action(macro, app = true) shouldBe "\u001bOA\u0003" + String("é".toByteArray(), Charsets.ISO_8859_1)
        input.sticky.state shouldBe StickyState()
    }

    @Test
    fun `typed line feeds are sent as Enter`() {
        input.type("\n").latin1() shouldBe "\r"
        input.type("\r\n").latin1() shouldBe "\r"
        input.type("\n", Modifiers.ALT).latin1() shouldBe "\u001b\r"
        input.type("a\nb\r\nc").latin1() shouldBe "a\rb\rc"
    }

    @Test
    fun `typed text edge cases`() {
        input.type("") shouldBe ByteArray(0)
        input.type("😀") shouldBe "😀".toByteArray(Charsets.UTF_8)
        input.type("x", Modifiers.ALT).latin1() shouldBe "\u001bx"
        // A lone surrogate is not a character; it goes through the UTF-8 encoder's replacement.
        input.type("\uD800") shouldBe "\uD800".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `multi-character keyboard commits are sanitised like a paste`() {
        // Gboard's clipboard strip commits the clipboard as text: no escape sequences may survive.
        input.type("ls\u001b[201~\u001b[31m;\u0000 x\u009b").latin1() shouldBe "ls[31m; x"
        input.type("a\tb").latin1() shouldBe "a\tb"
    }

    @Test
    fun `hardware keys combine with sticky modifiers`() {
        input.sticky.tap(StickyModifier.CTRL, nowMillis = 0)
        input.onKey(Key.Left, Modifiers.SHIFT).latin1() shouldBe "\u001b[1;6D"
        input.onKey(Key.Left).latin1() shouldBe "\u001b[D"
        input.onKey(Key.Left, applicationCursorKeys = true).latin1() shouldBe "\u001bOD"
    }

    @Test
    fun `claude preset keys produce the expected bytes`() {
        val keys =
            KeyBarLayout.CLAUDE.rows
                .flatten()
                .associateBy { it.label }
        action(keys.getValue("⇧TAB").action) shouldBe "\u001b[Z"
        action(keys.getValue("^C").action) shouldBe "\u0003"
        action(keys.getValue("/clear").action) shouldBe "/clear\r"
        action(keys.getValue("ESC").swipeUp!!) shouldBe "\u001b\u001b"
    }
}
