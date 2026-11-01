package com.kitefoldlabs.bridle.core.terminalui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.terminal.ComposeHistory
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val engine = FakeTerminalEngine()
    private val connection = FakeConnection(TerminalState.Connecting)
    private val opened = mutableListOf<String>()
    private var reconnects = 0
    private lateinit var state: TerminalScreenState

    private val clipboard: ClipboardManager
        get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)

    private fun show(
        layout: KeyBarLayout = KeyBarLayout.DEFAULT,
        uriHandler: UriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            },
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MaterialTheme {
                    state = rememberTerminalScreenState(engine, connection, ComposeHistory())
                    TerminalScreen(state, onReconnect = { reconnects++ }, keyBarLayout = layout)
                }
            }
        }
    }

    @Test
    fun `shows the banner, the terminal and the key bar`() {
        show()
        rule.onNodeWithText("Connecting…").assertExists()
        rule.onNodeWithTag(FakeTerminalEngine.TERMINAL_TAG).assertExists()
        rule.onNodeWithContentDescription("Escape").performClick()
        connection.sentText() shouldBe "\u001b"
        connection.state.value = TerminalState.Ended(0, "Session ended")
        rule.onNodeWithText("Reconnect").performClick()
        reconnects shouldBe 1
    }

    @Test
    fun `the grid is fitted to the pane`() {
        show()
        rule.waitForIdle()
        engine.resizes.shouldNotBe(emptyList<TerminalGridSize>())
        engine.lastHost shouldBe state
    }

    @Test
    fun `tapping the terminal shows the keyboard`() {
        show()
        rule.onNodeWithText("ime false").assertExists()
        rule.onNodeWithTag(FakeTerminalEngine.TERMINAL_TAG).performClick()
        rule.onNodeWithText("ime true").assertExists()
        rule.onNodeWithContentDescription("Hide keyboard").performClick()
        rule.onNodeWithContentDescription("Show keyboard").assertExists()
        state.isKeyboardVisible shouldBe false
    }

    @Test
    fun `the compose button opens and closes the compose box`() {
        show()
        rule.onNodeWithContentDescription("Open compose box").performClick()
        rule.onNodeWithText("Message to send").assertExists()
        state.isComposeOpen shouldBe true
        rule.onAllNodesWithContentDescription("Close compose box").onFirst().performClick()
        rule.onNodeWithText("Message to send").assertDoesNotExist()
        state.isComposeOpen shouldBe false
    }

    @Test
    fun `pinching zooms the font`() {
        show()
        rule.onNodeWithTag(FakeTerminalEngine.TERMINAL_TAG).performTouchInput {
            pinch(
                center - Offset(10f, 0f),
                center - Offset(60f, 0f),
                center + Offset(10f, 0f),
                center + Offset(60f, 0f),
            )
        }
        rule.waitForIdle()
        state.fontSizeSp shouldBeGreaterThan 12f
        rule.onNodeWithTag(FakeTerminalEngine.TERMINAL_TAG).performTouchInput {
            pinch(
                center - Offset(60f, 0f),
                center - Offset(10f, 0f),
                center + Offset(60f, 0f),
                center + Offset(10f, 0f),
            )
        }
        rule.waitForIdle()
        state.fontSizeSp shouldBeLessThan state.fontBounds.max
    }

    @Test
    fun `the engine's paste menu pastes the clipboard`() {
        show()
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "git status"))
        rule.onNodeWithText("paste").performClick()
        rule.waitForIdle()
        connection.sentText() shouldBe "git status"
    }

    @Test
    fun `a multi-line paste is confirmed first`() {
        show()
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "rm a\nrm b"))
        rule.onNodeWithText("paste").performClick()
        rule.onNodeWithText("Run 2 lines?").assertExists()
        connection.sent.shouldBeEmpty()
        rule.onNodeWithText("Send anyway").performClick()
        rule.onNodeWithText("Run 2 lines?").assertDoesNotExist()
        connection.sentText() shouldBe "rm a\rrm b"
    }

    @Test
    fun `an empty clipboard pastes nothing`() {
        show()
        clipboard.clearPrimaryClip()
        rule.onNodeWithText("paste").performClick()
        rule.waitForIdle()
        connection.sent.shouldBeEmpty()
    }

    @Test
    fun `only web links are opened`() {
        show()
        rule.onNodeWithText("link").performClick()
        rule.onNodeWithText("bad link").performClick()
        opened shouldContainExactly listOf("https://example.com/")
    }

    @Test
    fun `a link no app can open is ignored`() {
        show(
            uriHandler =
                object : UriHandler {
                    override fun openUri(uri: String) = throw IllegalArgumentException("no activity")
                },
        )
        rule.onNodeWithText("link").performClick()
        rule.onNodeWithTag(FakeTerminalEngine.TERMINAL_TAG).assertExists()
    }

    @Test
    fun `the default layout and a remembered state`() {
        var history by mutableStateOf(ComposeHistory())
        rule.setContent {
            MaterialTheme {
                state = rememberTerminalScreenState(engine, connection, history, FontSizeBounds(default = 14f))
                TerminalScreen(state, onReconnect = {})
            }
        }
        rule.onNodeWithContentDescription("Tab").assertExists()
        val first = state
        first.fontSizeSp shouldBe 14f
        history = ComposeHistory(listOf("older"))
        rule.waitForIdle()
        state shouldNotBe first
        state.history.entries shouldContainExactly listOf("older")
    }

    @Test
    fun `the pane works on its own`() {
        rule.setContent {
            MaterialTheme {
                state = rememberTerminalScreenState(engine, connection, ComposeHistory())
                TerminalPane(state)
            }
        }
        rule.onNodeWithTag(FakeTerminalEngine.TERMINAL_TAG).performClick()
        state.isKeyboardVisible shouldBe true
    }

    @Test
    fun `the toolbar matches a one-row layout`() {
        show(KeyBarLayout(listOf(KeyBarLayout.DEFAULT.rows.first())))
        rule.onNodeWithContentDescription("Open compose box").assertExists()
        rule.onNodeWithContentDescription("Tab").assertDoesNotExist()
    }
}
