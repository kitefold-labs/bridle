package com.kitefoldlabs.bridle.feature.snippets

import android.app.Application
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.model.Snippet
import com.kitefoldlabs.bridle.core.model.SnippetRunMode
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.feature.FakeConnection
import com.kitefoldlabs.bridle.feature.connections
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Optional

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SnippetsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val data: DataModule = testData(ApplicationProvider.getApplicationContext())
    private val connection = FakeConnection()
    private val pasted = mutableListOf<String>()
    private var backs = 0

    @After
    fun tearDown() = data.close()

    private fun show(target: SnippetTarget? = null) {
        val vm =
            SnippetsViewModel(
                SavedStateHandle(mapOf(SnippetsDestination.ARG_HOST to 1L)),
                data.snippets,
                connections({ connection }),
                Optional.ofNullable(target),
            )
        rule.setContent { SnippetsScreen(vm, onBack = { backs++ }) }
    }

    private fun waitForText(text: String) =
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun click(text: String) = rule.onNodeWithText(text).performClick()

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label, substring = true))

    @Test
    fun `creates a snippet with variables and runs it with exec`() {
        show()
        waitForText("No snippets yet")
        rule.onNodeWithContentDescription("New snippet").performClick()
        click("Save")
        rule.onNodeWithText("Give the snippet a name.").assertExists()
        field("Name").performTextInput("Disk")
        field("Command").performTextInput("df -h {{path:/}}")
        rule.onNodeWithText("Variables: path").assertExists()
        field("Package").performTextInput("ops")
        click("Run and show output")
        click("Save")
        waitForText("Snippet saved")
        waitForText("df -h {{path:/}}") // the list row
        rule.onNodeWithText("Disk").assertExists()
        rule.onNodeWithText("ops").assertExists()

        connection.execResult = ExecResult(0, "Filesystem  Size\n", "")
        click("Disk")
        rule.onNodeWithText("df -h /").assertExists()
        field("path").performTextReplacement("/home")
        rule.onNodeWithText("df -h /home").assertExists()
        rule.onAllNodesWithText("Run and show output")[0].performClick()
        waitForText("Exit status: 0")
        rule.onNodeWithText("Filesystem  Size", substring = true).assertExists()
        connection.commands shouldBe listOf("df -h /home")

        connection.execResult = ExecResult(null, "", "")
        rule.onAllNodesWithText("Run and show output")[0].performClick()
        waitForText("(no output)")
        rule.onNodeWithText("Exit status: ?").assertExists()
        connection.execFailure = IOException("channel closed")
        rule.onAllNodesWithText("Run and show output")[0].performClick()
        waitForText("Couldn't run: channel closed")
        click("Paste into terminal")
        waitForText("No terminal is open on this host.")
        click("Close")
        rule.onNodeWithContentDescription("Back").performClick()
        backs shouldBe 1
    }

    @Test
    fun `pastes, edits and deletes`() {
        runBlocking { data.snippets.upsert(Snippet(name = "Up", body = "uptime\nwho")) }
        show { _, text -> pasted.add(text) }
        waitForText("Up")
        rule.onNodeWithContentDescription("Run Up").performClick()
        rule.onAllNodesWithText("Paste into terminal")[0].performClick()
        waitForText("Pasted into the terminal")
        pasted shouldBe listOf("uptime\nwho")
        rule.onNodeWithContentDescription("Run Up").performClick()
        rule.onAllNodesWithText("Run and show output")[0].performClick()
        waitForText("ok")
        click("Close")

        rule.onNodeWithContentDescription("Actions for Up").performClick()
        click("Edit")
        rule.onNodeWithText("Edit snippet").assertExists()
        field("Name").performTextReplacement("Uptime")
        click("Save")
        rule.waitUntil(20_000) {
            rule.onAllNodes(hasContentDescription("Actions for Uptime")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Actions for Uptime").performClick()
        click("Edit")
        click("Cancel")

        rule.onNodeWithContentDescription("Actions for Uptime").performClick()
        click("Delete")
        rule.onNodeWithText("Delete the snippet Uptime?").assertExists()
        click("Cancel")
        rule.onNodeWithContentDescription("Actions for Uptime").performClick()
        click("Delete")
        click("Delete")
        waitForText("Snippet deleted")
    }

    @Test
    fun `exec snippets offer paste as the second choice`() {
        runBlocking { data.snippets.upsert(Snippet(name = "Logs", body = "tail log", runMode = SnippetRunMode.EXEC)) }
        show()
        waitForText("Logs")
        click("Logs")
        click("Paste into terminal")
        waitForText("No terminal is open on this host.")
        click("Close")
    }
}
