package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.terminal.BracketedPaste
import com.kitefoldlabs.bridle.core.terminal.ComposeHistory
import com.kitefoldlabs.bridle.core.terminal.Key
import com.kitefoldlabs.bridle.core.terminal.KeyAction
import com.kitefoldlabs.bridle.core.terminal.ModifierState
import com.kitefoldlabs.bridle.core.terminal.StickyModifier
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalScreenStateTest {
    private val engine = FakeTerminalEngine()
    private val connection = FakeConnection()
    private val history = ComposeHistory()
    private var now = 1_000L

    private fun screen(scope: CoroutineScope) = TerminalScreenState(engine, connection, scope, history, clock = { now })

    private fun TestScope.screen() = screen(backgroundScope)

    @Test
    fun `exposes the connection state and engine title`() =
        runTest {
            val state = screen()
            state.terminalState.value shouldBe TerminalState.Open
            connection.state.value = TerminalState.Reattaching
            state.terminalState.value shouldBe TerminalState.Reattaching
            engine.title.value = "claude"
            state.title.value shouldBe "claude"
        }

    @Test
    fun `resizes the session once the engine size settles`() =
        runTest {
            val state = screen()
            runCurrent()
            advanceTimeBy(TerminalScreenState.DEFAULT_RESIZE_DEBOUNCE.inWholeMilliseconds + 1)
            connection.resizes shouldContainExactly listOf(80 to 24)

            state.onViewportChanged(500, 400, CellMetrics(10f, 20f))
            engine.resizes shouldContainExactly listOf(TerminalGridSize(50, 20))
            runCurrent()
            state.onViewportChanged(600, 400, CellMetrics(10f, 20f))
            runCurrent()
            advanceTimeBy(50)
            state.onViewportChanged(700, 400, CellMetrics(10f, 20f))
            runCurrent()
            advanceTimeBy(TerminalScreenState.DEFAULT_RESIZE_DEBOUNCE.inWholeMilliseconds + 1)
            connection.resizes shouldContainExactly listOf(80 to 24, 70 to 20)
        }

    @Test
    fun `fits the grid to the view and font`() {
        TerminalScreenState.fitGrid(1080, 1500, CellMetrics(20f, 40f)) shouldBe TerminalGridSize(54, 37)
        TerminalScreenState.fitGrid(5, 5, CellMetrics(10f, 20f)) shouldBe TerminalGridSize(1, 1)
        TerminalScreenState.fitGrid(0, 100, CellMetrics(10f, 20f)).shouldBeNull()
        TerminalScreenState.fitGrid(100, 0, CellMetrics(10f, 20f)).shouldBeNull()
        TerminalScreenState.fitGrid(100, 100, CellMetrics(0f, 20f)).shouldBeNull()
        TerminalScreenState.fitGrid(100, 100, CellMetrics(10f, 0f)).shouldBeNull()
    }

    @Test
    fun `an unmeasured view does not resize`() =
        runTest {
            val state = screen()
            state.onViewportChanged(0, 0, CellMetrics(10f, 20f))
            engine.resizes.shouldBeEmpty()
        }

    @Test
    fun `grid sizes must be positive`() {
        shouldThrow<IllegalArgumentException> { TerminalGridSize(0, 1) }
        shouldThrow<IllegalArgumentException> { TerminalGridSize(1, 0) }
    }

    @Test
    fun `pinch zoom scales the font within bounds`() =
        runTest {
            val state = screen()
            state.fontSizeSp shouldBe 12f
            state.zoomBy(1.5f)
            state.fontSizeSp shouldBe 18f
            state.zoomBy(10f)
            state.fontSizeSp shouldBe state.fontBounds.max
            state.zoomBy(0.01f)
            state.fontSizeSp shouldBe state.fontBounds.min
            state.setFontSize(14f)
            state.fontSizeSp shouldBe 14f
        }

    @Test
    fun `the initial font size is clamped`() =
        runTest {
            val state = TerminalScreenState(engine, connection, backgroundScope, initialFontSizeSp = 100f)
            state.fontSizeSp shouldBe FontSizeBounds().max
        }

    @Test
    fun `font bounds are validated`() {
        shouldThrow<IllegalArgumentException> { FontSizeBounds(min = 0f) }
        shouldThrow<IllegalArgumentException> { FontSizeBounds(min = 10f, default = 8f) }
        shouldThrow<IllegalArgumentException> { FontSizeBounds(default = 40f, max = 30f) }
    }

    @Test
    fun `tapping the terminal shows the keyboard and the IME reports back`() =
        runTest {
            val state = screen()
            state.isKeyboardVisible shouldBe false
            state.onTerminalTap()
            state.isKeyboardVisible shouldBe true
            state.showSoftKeyboard shouldBe true
            state.onImeVisibilityChanged(false)
            state.isKeyboardVisible shouldBe false
            state.toggleKeyboard()
            state.isKeyboardVisible shouldBe true
            state.toggleKeyboard()
            state.isKeyboardVisible shouldBe false
        }

    @Test
    fun `the terminal leaves the keyboard to an open compose box`() =
        runTest {
            val state = screen()
            state.onTerminalTap()
            state.openCompose()
            state.showSoftKeyboard shouldBe false
            state.closeCompose()
            state.showSoftKeyboard shouldBe true
        }

    @Test
    fun `key-bar actions are encoded and sent`() =
        runTest {
            val state = screen()
            state.onKeyBarAction(KeyAction.Press(Key.Up))
            engine.isApplicationCursorKeys = true
            state.onKeyBarAction(KeyAction.Press(Key.Up))
            connection.sentText() shouldBe "\u001b[A\u001bOA"
        }

    @Test
    fun `sticky modifiers are tracked and applied`() =
        runTest {
            val state = screen()
            state.onKeyBarAction(KeyAction.Toggle(StickyModifier.CTRL))
            connection.sent.shouldBeEmpty() // a toggle sends nothing
            state.sticky.ctrl shouldBe ModifierState.ONCE
            state.stickyModifiers.state.ctrl shouldBe ModifierState.ONCE
            state.onKeyBarAction(KeyAction.Text("c"))
            connection.sentText() shouldBe "\u0003"
            state.sticky.ctrl shouldBe ModifierState.OFF

            state.onKeyBarAction(KeyAction.Toggle(StickyModifier.ALT))
            now += 100
            state.onKeyBarAction(KeyAction.Toggle(StickyModifier.ALT))
            state.sticky.alt shouldBe ModifierState.LOCKED
            state.resetModifiers()
            state.sticky.alt shouldBe ModifierState.OFF
        }

    @Test
    fun `paste is sanitised and bracketed when the remote wants it`() =
        runTest {
            val state = screen()
            engine.isBracketedPasteEnabled = true
            state.paste("one\ntwo\u001b[201~")
            connection.sentText() shouldBe "${BracketedPaste.START}one\rtwo${BracketedPaste.END}"
            state.pendingSend.shouldBeNull()
        }

    @Test
    fun `a single-line paste without bracketed mode is sent as is`() =
        runTest {
            val state = screen()
            state.paste("ls -la")
            connection.sentText() shouldBe "ls -la"
            state.paste("")
            connection.sent.size shouldBe 1
        }

    @Test
    fun `a multi-line paste without bracketed mode waits for confirmation`() =
        runTest {
            val state = screen()
            state.paste("rm a\nrm b\n")
            connection.sent.shouldBeEmpty()
            state.pendingSend shouldBe PendingSend("rm a\nrm b\n", submit = false)
            state.pendingSend?.lineCount shouldBe 2
            state.confirmPendingSend()
            state.pendingSend.shouldBeNull()
            connection.sentText() shouldBe "rm a\rrm b\r"
            history.entries.shouldBeEmpty() // pastes are not compose-box history
        }

    @Test
    fun `a dismissed confirmation sends nothing`() =
        runTest {
            val state = screen()
            state.paste("a\nb")
            state.dismissPendingSend()
            state.pendingSend.shouldBeNull()
            state.confirmPendingSend()
            connection.sent.shouldBeEmpty()
        }

    @Test
    fun `compose send pastes then presses Enter and records history`() =
        runTest {
            val state = screen()
            engine.isBracketedPasteEnabled = true
            state.openCompose()
            state.isComposeOpen shouldBe true
            state.updateComposeText("fix the tests\nthen commit")
            state.submitCompose()
            connection.sentText() shouldBe "${BracketedPaste.START}fix the tests\rthen commit${BracketedPaste.END}\r"
            state.composeText shouldBe ""
            history.entries shouldContainExactly listOf("fix the tests\nthen commit")
            state.isComposeOpen shouldBe true
        }

    @Test
    fun `compose send of blank text does nothing`() =
        runTest {
            val state = screen()
            state.updateComposeText("   ")
            state.submitCompose()
            connection.sent.shouldBeEmpty()
        }

    @Test
    fun `multi-line compose send without bracketed mode asks first and keeps the text if cancelled`() =
        runTest {
            val state = screen()
            state.updateComposeText("a\nb")
            state.submitCompose()
            state.pendingSend shouldBe PendingSend("a\nb", submit = true)
            state.dismissPendingSend()
            state.composeText shouldBe "a\nb"
            state.submitCompose()
            state.confirmPendingSend()
            connection.sentText() shouldBe "a\rb\r"
            state.composeText shouldBe ""
        }

    @Test
    fun `history moves through earlier sends and back to the draft`() =
        runTest {
            val state = screen()
            history.add("first")
            history.add("second")
            state.updateComposeText("draft")
            state.historyPrevious()
            state.composeText shouldBe "second"
            state.historyPrevious()
            state.composeText shouldBe "first"
            state.historyPrevious() // no older entry: stays
            state.composeText shouldBe "first"
            state.historyNext()
            state.historyNext()
            state.composeText shouldBe "draft"
            state.historyNext() // not navigating: stays
            state.composeText shouldBe "draft"
            state.historyPrevious()
            state.closeCompose()
            history.isNavigating shouldBe false
        }

    @Test
    fun `copies the engine selection`() =
        runTest {
            val state = screen()
            engine.selection = "selected"
            state.copySelection() shouldBe "selected"
            state.copySelection().shouldBeNull()
        }

    @Test
    fun `the default clock is monotonic milliseconds`() =
        runTest {
            val state = TerminalScreenState(engine, connection, backgroundScope)
            state.onKeyBarAction(KeyAction.Toggle(StickyModifier.CTRL))
            state.onKeyBarAction(KeyAction.Toggle(StickyModifier.CTRL))
            state.sticky.ctrl shouldBe ModifierState.LOCKED // two taps well inside the window
        }

    @Test
    fun `a pending send counts lines like the remote would run them`() {
        PendingSend("one", submit = true).lineCount shouldBe 1
        PendingSend("one\r\ntwo\n", submit = false).lineCount shouldBe 2
    }
}
