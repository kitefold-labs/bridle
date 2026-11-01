package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kitefoldlabs.bridle.core.session.TerminalState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalStatusBannerTest {
    @get:Rule
    val rule = createComposeRule()

    private var reconnects = 0

    private fun show(state: TerminalState) {
        rule.setContent { MaterialTheme { TerminalStatusBanner(state, onReconnect = { reconnects++ }) } }
    }

    private val liveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun `shows nothing while open`() {
        show(TerminalState.Open)
        rule.onAllNodes(liveRegion).assertCountEquals(0)
    }

    @Test
    fun `announces connecting`() {
        show(TerminalState.Connecting)
        rule.onNodeWithText("Connecting…").assertExists()
        rule.onAllNodes(liveRegion).assertCountEquals(1)
    }

    @Test
    fun `announces reattaching`() {
        show(TerminalState.Reattaching)
        rule.onNodeWithText("Connection lost. Reattaching…").assertExists()
    }

    @Test
    fun `an ended session shows the reason, exit status and Reconnect`() {
        show(TerminalState.Ended(exitStatus = 130, reason = "Session ended"))
        rule.onNodeWithText("Session ended: Session ended").assertExists()
        rule.onNodeWithText("Exit status 130").assertExists()
        rule.onNodeWithText("Reconnect").performClick()
        reconnects shouldBe 1
    }

    @Test
    fun `an ended session without a status omits it`() {
        show(TerminalState.Ended(exitStatus = null, reason = "Connection lost"))
        rule.onNodeWithText("Session ended: Connection lost").assertExists()
        rule.onNodeWithText("Exit status", substring = true).assertDoesNotExist()
    }
}
