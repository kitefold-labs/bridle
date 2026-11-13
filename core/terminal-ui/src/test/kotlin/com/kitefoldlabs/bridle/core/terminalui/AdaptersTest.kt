package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.ConnectionLease
import com.kitefoldlabs.bridle.core.session.HostConnectionState
import com.kitefoldlabs.bridle.core.session.TerminalSession
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.core.terminal.Key
import com.kitefoldlabs.bridle.core.terminal.KeyAction
import com.kitefoldlabs.bridle.core.terminal.Modifiers
import com.kitefoldlabs.bridle.core.terminal.StickyModifier
import com.kitefoldlabs.bridle.core.terminal.StickyModifiers
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AdaptersTest {
    @Test
    fun `a session adapts to a terminal connection`() =
        runTest {
            val lease =
                object : ConnectionLease {
                    override val hostId = 1L
                    override val state = MutableStateFlow<HostConnectionState>(HostConnectionState.Connecting)

                    override suspend fun awaitConnection(): SshConnection = awaitCancellation()

                    override fun close() = Unit
                }
            val session = TerminalSession(lease, AttachSpec.Shell, { _, _ -> }, backgroundScope)
            val connection = session.asTerminalConnection()
            connection.state.value shouldBe TerminalState.Connecting
            connection.send("ls\r".toByteArray()) // queued until a channel opens
            connection.resize(100, 40) // remembered for the channel
        }

    @Test
    fun `engine replies are routed to the connection`() {
        val engine = FakeTerminalEngine()
        val connection = FakeConnection()
        engine.sendRemoteInputTo(connection)
        engine.onRemoteInput?.invoke("\u001b[?1;2c".toByteArray())
        connection.sentText() shouldBe "\u001b[?1;2c"
    }

    @Test
    fun `termlib's keyboard sees and consumes the sticky modifiers`() {
        val sticky = StickyModifiers()
        val bridge = StickyModifierBridge(sticky)
        bridge.isCtrlActive() shouldBe false
        bridge.isAltActive() shouldBe false
        bridge.isShiftActive() shouldBe false
        sticky.tap(StickyModifier.CTRL, 0)
        sticky.tap(StickyModifier.ALT, 0)
        sticky.tap(StickyModifier.SHIFT, 0)
        bridge.isCtrlActive() shouldBe true
        bridge.isAltActive() shouldBe true
        bridge.isShiftActive() shouldBe true
        bridge.clearTransients()
        bridge.isCtrlActive() shouldBe false
        bridge.isAltActive() shouldBe false
        bridge.isShiftActive() shouldBe false
    }

    @Test
    fun `keys are described by what they send`() {
        KeyDescriptions.describe(KeyAction.Press(Key.Escape)) shouldBe "Escape"
        KeyDescriptions.describe(KeyAction.Press(Key.Up)) shouldBe "Up arrow"
        KeyDescriptions.describe(KeyAction.Press(Key.Down)) shouldBe "Down arrow"
        KeyDescriptions.describe(KeyAction.Press(Key.Left)) shouldBe "Left arrow"
        KeyDescriptions.describe(KeyAction.Press(Key.Right)) shouldBe "Right arrow"
        KeyDescriptions.describe(KeyAction.Press(Key.PageUp)) shouldBe "Page up"
        KeyDescriptions.describe(KeyAction.Press(Key.PageDown)) shouldBe "Page down"
        KeyDescriptions.describe(KeyAction.Press(Key.Tab, Modifiers.SHIFT)) shouldBe "Shift Tab"
        KeyDescriptions.describe(
            KeyAction.Press(Key.F5, Modifiers(ctrl = true, alt = true, shift = true, meta = true)),
        ) shouldBe "Control Alt Shift Meta F5"
        KeyDescriptions.describe(KeyAction.Chord("c", Modifiers.CTRL)) shouldBe "Control C"
        KeyDescriptions.describe(KeyAction.Toggle(StickyModifier.CTRL)) shouldBe "Control"
        KeyDescriptions.describe(KeyAction.Toggle(StickyModifier.ALT)) shouldBe "Alt"
        KeyDescriptions.describe(KeyAction.Toggle(StickyModifier.SHIFT)) shouldBe "Shift"
        KeyDescriptions.describe(KeyAction.Text("/")) shouldBe "/"
        KeyDescriptions.describe(
            KeyAction.Macro(listOf(KeyAction.Text("/compact"), KeyAction.Press(Key.Enter))),
        ) shouldBe "/compact, Enter"
    }

    @Test
    fun `only web links are opened`() {
        isOpenableUrl("https://example.com") shouldBe true
        isOpenableUrl("HTTP://example.com") shouldBe true
        isOpenableUrl("intent://scan/#Intent;end") shouldBe false
        isOpenableUrl("file:///sdcard/x") shouldBe false
        isOpenableUrl("no scheme") shouldBe false
    }
}
