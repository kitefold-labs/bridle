package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.kitefoldlabs.bridle.core.terminal.MouseReporting
import com.kitefoldlabs.bridle.core.terminal.ShellEvent
import com.kitefoldlabs.bridle.core.terminal.StickyModifiers
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.SelectionMode
import org.connectbot.terminal.TerminalDimensions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * termlib's JNI library is built for Android only, so on the host JVM these tests cover the
 * adapter up to the native boundary. Rendering and emulation are verified on devices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermlibEngineTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `starts at the requested size with every mode off`() {
        val engine = TermlibEngine(TerminalGridSize(100, 30))
        engine.size.value shouldBe TerminalGridSize(100, 30)
        engine.title.value shouldBe ""
        engine.isBracketedPasteEnabled shouldBe false
        engine.isApplicationCursorKeys shouldBe false
        engine.copySelection().shouldBeNull()
        engine.close()
        engine.close() // idempotent
    }

    @Test
    fun `remote input listener can be set`() {
        val engine = TermlibEngine()
        val listener: (ByteArray) -> Unit = {}
        engine.onRemoteInput = listener
        engine.onRemoteInput shouldBe listener
        engine.size.value shouldBe TerminalGridSize(TermlibEngine.DEFAULT_COLUMNS, TermlibEngine.DEFAULT_ROWS)
    }

    @Test
    fun `resizing to the current size does not touch the emulator`() {
        val engine = TermlibEngine()
        engine.resize(TerminalGridSize(80, 24))
        engine.size.value shouldBe TerminalGridSize(80, 24)
    }

    @Test
    fun `modes are tracked before output reaches the native emulator`() {
        val engine = TermlibEngine()
        val bytes = "\u001b[?2004h\u001b[?1049h\u001b[?1000;1006h\u001b]2;claude\u0007".toByteArray()
        // The native library is missing on the host JVM.
        shouldThrow<LinkageError> { engine.feed(bytes, 0, bytes.size) }
        engine.isBracketedPasteEnabled shouldBe true
        engine.isAlternateScreen shouldBe true
        engine.mouseReporting shouldBe MouseReporting.SGR
        engine.title.value shouldBe "claude"
        val events = mutableListOf<ShellEvent>()
        engine.onShellEvent = { events += it }
        engine.onShellEvent shouldNotBe null
        val prompt = "\u001b]133;A\u0007".toByteArray()
        shouldThrow<LinkageError> { engine.feed(prompt, 0, prompt.size) }
        events shouldBe listOf(ShellEvent.PromptStart())
        engine.shellPrompt.value.atPrompt shouldBe true
        shouldThrow<LinkageError> { engine.resize(TerminalGridSize(100, 30)) }
        shouldThrow<LinkageError> { engine.resize(TerminalGridSize(80, 30)) }
    }

    @Test
    fun `termlib's output and resizes are passed on`() {
        val engine = TermlibEngine()
        engine.deliverRemoteInput("ignored".toByteArray()) // no listener yet
        val received = mutableListOf<String>()
        engine.onRemoteInput = { received += it.decodeToString() }
        engine.deliverRemoteInput("\u001b[?1;2c".toByteArray())
        received shouldBe listOf("\u001b[?1;2c")
        engine.onEmulatorResized(TerminalDimensions(rows = 40, columns = 120))
        engine.size.value shouldBe TerminalGridSize(120, 40)
    }

    @Test
    fun `copies termlib's selection when there is one`() {
        val engine = TermlibEngine()
        engine.selection = FakeSelection("")
        engine.copySelection().shouldBeNull()
        engine.selection = FakeSelection("ls -la")
        engine.copySelection() shouldBe "ls -la"
    }

    @Test
    fun `renders termlib's view and reports taps and the keyboard`() {
        val engine = TermlibEngine()
        val events = mutableListOf<String>()
        val host =
            object : TerminalViewHost {
                override val fontSizeSp = 12f
                override val showSoftKeyboard = false
                override val stickyModifiers = StickyModifiers()

                override fun onTerminalTap() {
                    events += "tap"
                }

                override fun onImeVisibilityChanged(visible: Boolean) {
                    events += "ime $visible"
                }
            }
        rule.setContent { engine.Content(host, {}, {}, Modifier.fillMaxSize()) }
        rule.waitForIdle()
        rule.onRoot().performClick()
        rule.waitForIdle()
        events shouldContain "ime false"
        events shouldContain "tap"
    }

    private class FakeSelection(
        private val text: String,
    ) : SelectionController {
        override val isSelectionActive = text.isNotEmpty()

        override fun startSelection(mode: SelectionMode) = Unit

        override fun toggleSelection() = Unit

        override fun moveSelectionUp() = Unit

        override fun moveSelectionDown() = Unit

        override fun moveSelectionLeft() = Unit

        override fun moveSelectionRight() = Unit

        override fun toggleSelectionMode() = Unit

        override fun setSelectionMode(mode: SelectionMode) = Unit

        override fun selectAll() = Unit

        override fun finishSelection() = Unit

        override fun copySelection(): String = text

        override fun clearSelection() = Unit
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `measures cells like termlib`() {
        val small = TermlibEngine().cellMetrics(24f)
        val large = TermlibEngine().cellMetrics(48f)
        small.widthPx shouldBeGreaterThan 0f
        small.heightPx shouldBeGreaterThan 0f
        large.heightPx shouldBeGreaterThan small.heightPx
        (large.heightPx % 1f) shouldBe 0f // whole pixels, so rows share a pixel boundary
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `falls back to termlib's row height when a font has no metrics`() {
        TermlibEngine().cellMetrics(24f).heightPx shouldBe 20f
    }
}
