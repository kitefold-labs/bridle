package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import com.kitefoldlabs.bridle.core.terminal.BracketedPaste
import com.kitefoldlabs.bridle.core.terminal.ComposeHistory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeBoxTest {
    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()
    private var text by mutableStateOf("")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun show() {
        rule.setContent {
            MaterialTheme {
                ComposeBox(
                    text = text,
                    onTextChange = {
                        events += "text:$it"
                        text = it
                    },
                    onSend = { events += "send" },
                    onHistoryPrevious = { events += "previous" },
                    onHistoryNext = { events += "next" },
                    onClose = { events += "close" },
                )
            }
        }
    }

    private val field get() = rule.onNode(hasSetTextAction())

    @Test
    fun `typing updates the text and enables Send`() {
        show()
        field.assertIsFocused()
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        field.performTextInput("hello")
        events shouldContainExactly listOf("text:hello")
        rule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        events.last() shouldBe "send"
    }

    @Test
    fun `history and close buttons call back`() {
        show()
        rule.onNodeWithContentDescription("Previous message").performClick()
        rule.onNodeWithContentDescription("Next message").performClick()
        rule.onNodeWithContentDescription("Close compose box").performClick()
        events shouldContainExactly listOf("previous", "next", "close")
    }

    @Test
    fun `moving the cursor alone does not change the text`() {
        text = "abc"
        show()
        field.performTextInputSelection(TextRange(1))
        events.shouldBeEmpty()
    }

    @Test
    fun `text replaced from outside shows with the cursor at its end`() {
        text = "draft"
        show()
        text = "an older prompt"
        rule.waitForIdle()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(15)))
        field.assertTextEquals("Message to send", "an older prompt")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Ctrl+Enter sends and plain Enter types a newline`() {
        text = "x"
        show()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        events shouldContainExactly listOf("send")
        field.performKeyInput { pressKey(Key.Enter) }
        events.last() shouldBe "text:x\n"
    }

    @Test
    fun `the state-bound box sends through the screen state`() {
        val engine = FakeTerminalEngine().apply { isBracketedPasteEnabled = true }
        val connection = FakeConnection()
        val state = TerminalScreenState(engine, connection, scope, ComposeHistory())
        state.openCompose()
        rule.setContent { MaterialTheme { ComposeBox(state) } }
        field.performTextInput("line one\nline two")
        rule.onNodeWithContentDescription("Send").performClick()
        connection.sentText() shouldBe "${BracketedPaste.START}line one\rline two${BracketedPaste.END}\r"
        state.composeText shouldBe ""
        rule.onNodeWithContentDescription("Close compose box").performClick()
        state.isComposeOpen shouldBe false
    }

    @Test
    fun `the paste confirmation names the line count and calls back`() {
        rule.setContent {
            MaterialTheme {
                PasteConfirmationDialog(
                    PendingSend("a\nb\nc", submit = false),
                    onConfirm = { events += "confirm" },
                    onDismiss = { events += "dismiss" },
                )
            }
        }
        rule.onNodeWithText("Run 3 lines?").assertExists()
        rule.onNodeWithText("Send anyway").performClick()
        rule.onNodeWithText("Cancel").performClick()
        events shouldContainExactly listOf("confirm", "dismiss")
    }

    @Test
    fun `a one-line confirmation is singular`() {
        rule.setContent {
            MaterialTheme { PasteConfirmationDialog(PendingSend("a\n", submit = false), {}, {}) }
        }
        rule.onNodeWithText("Run 1 line?").assertExists()
    }
}
