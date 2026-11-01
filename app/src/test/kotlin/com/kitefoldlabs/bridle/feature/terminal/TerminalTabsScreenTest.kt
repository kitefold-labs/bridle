package com.kitefoldlabs.bridle.feature.terminal

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.terminal.ComposeHistory
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout
import com.kitefoldlabs.bridle.core.terminalui.TerminalConnection
import com.kitefoldlabs.bridle.core.terminalui.rememberTerminalScreenState
import com.kitefoldlabs.bridle.feature.FakeEngine
import com.kitefoldlabs.bridle.feature.recomposeThrough
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records what the tab row asks for. */
class RecordingTabActions : TerminalTabsActions {
    val calls = mutableListOf<String>()

    override fun select(tabId: String) {
        calls += "select $tabId"
    }

    override fun close(tabId: String) {
        calls += "close $tabId"
    }

    override fun reconnect(tabId: String) {
        calls += "reconnect $tabId"
    }

    override fun clearHistory(hostId: EntityId) {
        calls += "clear $hostId"
    }
}

private class StaticConnection(
    state: TerminalState,
) : TerminalConnection {
    override val state = MutableStateFlow(state)

    override fun send(bytes: ByteArray) = Unit

    override suspend fun resize(
        columns: Int,
        rows: Int,
    ) = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalTabsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = RecordingTabActions()
    private var backs = 0

    private val tabs =
        listOf(
            TerminalTabItem("a", 1, "devbox", TerminalState.Open),
            TerminalTabItem("b", 2, "api · gpu", TerminalState.Ended(null, "Session ended")),
            TerminalTabItem("c", 2, "web · gpu", TerminalState.Connecting),
        )

    @Test
    fun `without tabs it says how to open one`() {
        rule.setContent {
            MaterialTheme {
                TerminalTabsScreen(emptyList(), null, emptyMap(), KeyBarLayout.DEFAULT, actions, onBack = { backs++ })
            }
        }
        rule.onNodeWithText("No open terminals").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Back").performClick()
        backs shouldBe 2
    }

    @Test
    fun `tabs can be picked, closed and reconnected`() {
        val engine = FakeEngine()
        rule.setContent {
            MaterialTheme {
                val state =
                    rememberTerminalScreenState(
                        engine,
                        StaticConnection(TerminalState.Ended(null, "Session ended")),
                        ComposeHistory(),
                    )
                TerminalTabsScreen(tabs, "b", mapOf("b" to state), KeyBarLayout.CLAUDE, actions, onBack = { backs++ })
            }
        }
        rule.onNodeWithTag(FakeEngine.TAG).assertExists()
        // The row scrolls the selected tab into view; scroll back to the first.
        rule.onNodeWithText("devbox").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Close web · gpu").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Ended").assertExists()
        rule.onNodeWithContentDescription("Connecting").assertExists()
        rule.onNodeWithText("Reconnect").performClick()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Clear message history for this host").performClick()
        actions.calls shouldBe listOf("select a", "close c", "reconnect b", "clear 2")
        rule.onAllNodesWithContentDescription("Close devbox").fetchSemanticsNodes().size shouldBe 1
    }

    @Test
    fun `recomposes through tab changes`() {
        val engine = FakeEngine()
        val states =
            listOf(
                tabs to "b",
                tabs to "a",
                tabs.drop(1) to "c",
                tabs.map { it.copy(state = TerminalState.Reattaching) } to "b",
                emptyList<TerminalTabItem>() to null,
            )
        rule.recomposeThrough(states, listOf(actions, RecordingTabActions())) { (items, selected), current, tick ->
            MaterialTheme {
                val screen = rememberTerminalScreenState(engine, StaticConnection(TerminalState.Open), ComposeHistory())
                TerminalTabsScreen(
                    items,
                    selected,
                    mapOf("b" to screen),
                    if (tick % 2 == 0) KeyBarLayout.DEFAULT else KeyBarLayout.CLAUDE,
                    current,
                    onBack = { backs++ },
                    modifier = Modifier.testTag("tick-$tick"),
                )
            }
        }
        rule.onNodeWithText("No open terminals").assertExists()
    }

    @Test
    fun `a tab whose screen is not ready shows nothing yet`() {
        rule.setContent {
            MaterialTheme {
                TerminalTabsScreen(tabs, "missing", emptyMap(), KeyBarLayout.DEFAULT, actions, onBack = {})
            }
        }
        rule.onNodeWithTag(FakeEngine.TAG).assertDoesNotExist()
        rule.onAllNodesWithText("devbox").fetchSemanticsNodes().size shouldBe 1
    }

    @Test
    fun `the terminal keeps the screen on until left and leaves the secure flag alone`() {
        var keep by mutableStateOf(true)
        var shown by mutableStateOf(true)
        lateinit var view: View
        var activity: Activity? = null
        rule.setContent {
            view = LocalView.current
            activity = LocalActivity.current
            if (shown) TerminalWindowFlags(keepScreenOn = keep)
        }

        fun secured() = (activity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        rule.waitForIdle()
        // As SecureScreen sets it for the whole app when "secure screen" is on.
        rule.runOnUiThread { activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        view.keepScreenOn shouldBe true

        keep = false
        rule.waitForIdle()
        view.keepScreenOn shouldBe false

        keep = true
        rule.waitForIdle()
        view.keepScreenOn shouldBe true
        shown = false
        rule.waitForIdle()
        view.keepScreenOn shouldBe false
        secured() shouldBe true // leaving the terminal must not undo the app-wide setting
    }
}
