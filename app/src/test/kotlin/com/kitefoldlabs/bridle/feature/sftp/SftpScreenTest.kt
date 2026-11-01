package com.kitefoldlabs.bridle.feature.sftp

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
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
import com.kitefoldlabs.bridle.core.ssh.Sftp
import com.kitefoldlabs.bridle.feature.FakeConnection
import com.kitefoldlabs.bridle.feature.FakeDocuments
import com.kitefoldlabs.bridle.feature.FakeResults
import com.kitefoldlabs.bridle.feature.FakeSftpSession
import com.kitefoldlabs.bridle.feature.FakeSftpSession.Companion.HOME
import com.kitefoldlabs.bridle.feature.TestHost
import com.kitefoldlabs.bridle.feature.connections
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SftpScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sftp =
        FakeSftpSession().apply {
            dir("$HOME/src")
            file("$HOME/notes.md", "hello")
            file("$HOME/.profile", "x")
        }
    private val connection = FakeConnection(sftp)
    private val documents = FakeDocuments()
    private val results = FakeResults()
    private var backs = 0

    private val manager = connections({ connection }, scope)

    private fun show(): SftpViewModel {
        val vm =
            SftpViewModel(
                SavedStateHandle(mapOf(SftpDestination.ARG_HOST to 1L)),
                manager,
                TransferQueue(manager, documents, scope),
            )
        rule.setContent { TestHost(results) { SftpScreen(vm, onBack = { backs++ }) } }
        return vm
    }

    private fun clickText(text: String) = rule.onNodeWithText(text).performClick()

    private fun clickLabel(label: String) = rule.onNodeWithContentDescription(label).performClick()

    private fun waitForText(text: String) =
        rule.waitUntil(20_000) {
            rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun `browses folders with breadcrumbs, sorting and hidden files`() {
        show()
        rule.onNodeWithText("notes.md").assertExists()
        rule.onNodeWithText(".profile").assertDoesNotExist()
        clickText("src")
        rule.onNodeWithText("This folder is empty.").assertExists()
        clickText("me")
        rule.onNodeWithText("notes.md").assertExists()
        clickText("..")
        rule.onNodeWithText("me").assertExists()
        clickText("/")
        clickText("home")
        clickText("me")
        clickLabel("View options")
        clickText("Sort by size")
        clickLabel("View options")
        clickText("Sort by date")
        clickLabel("View options")
        clickText("Sort by name")
        clickLabel("View options")
        clickText("Show hidden files")
        rule.onNodeWithText(".profile").assertExists()
        clickLabel("View options")
        clickText("Show hidden files")
        rule.onNodeWithText(".profile").assertDoesNotExist()
        clickLabel("Back")
        backs shouldBe 1
    }

    @Test
    fun `creates, renames, chmods and deletes`() {
        show()
        clickLabel("New folder")
        rule.onNode(hasSetTextAction()).performTextInput("build")
        clickText("OK")
        rule.onNodeWithText("build").assertExists()
        clickLabel("New folder")
        clickText("Cancel")

        clickLabel("Actions for notes.md")
        clickText("Rename")
        rule.onNode(hasSetTextAction()).performTextReplacement("todo.md")
        clickText("OK")
        rule.onNodeWithText("todo.md").assertExists()

        clickLabel("Actions for todo.md")
        clickText("Permissions")
        rule.onNode(hasSetTextAction()).performTextReplacement("600")
        clickText("OK")
        rule.onNodeWithText("rw-------", substring = true).assertExists()

        clickLabel("Actions for todo.md")
        clickText("Delete")
        rule.onNodeWithText("The file is deleted on the host. This can't be undone.").assertExists()
        clickText("Cancel")
        clickLabel("Actions for todo.md")
        clickText("Delete")
        rule.onNode(hasText("Delete") and !hasText("Delete todo.md?")).performClick()
        rule.onNodeWithText("todo.md").assertDoesNotExist()

        clickLabel("Actions for build")
        clickText("Delete")
        rule
            .onNodeWithText(
                "The folder and everything inside it are deleted on the host. This can't be undone.",
            ).assertExists()
        rule.onNode(hasText("Delete") and !hasText("Delete build?")).performClick()
        rule.onNodeWithText("build").assertDoesNotExist()
    }

    @Test
    fun `reports problems in a snackbar`() {
        show()
        clickLabel("New folder")
        rule.onNode(hasSetTextAction()).performTextInput("a/b")
        clickText("OK")
        waitForText("Enter a name without “/”.")
        clickLabel("Actions for src")
        clickText("Permissions")
        rule.onNode(hasSetTextAction()).performTextReplacement("x")
        clickText("OK")
        waitForText("Enter three or four octal digits, such as 644 or 0755.")
        sftp.failNext = IOException("permission denied")
        clickText("src")
        waitForText("Failed: permission denied")
    }

    @Test
    fun `too large files can't be edited`() {
        sftp.file("$HOME/huge.bin", bytes = ByteArray((Sftp.EDITOR_LIMIT + 1).toInt()))
        show()
        clickText("huge.bin")
        waitForText("This file is 1.0 MB; the editor opens files up to 1.0 MB. Download it instead.")
    }

    @Test
    fun `uploads and downloads with the transfer list`() {
        show()
        val upload = "content://docs/report.txt"
        documents.inputs[upload] = "report".toByteArray()
        documents.names[upload] = DocumentInfo("report.txt", 6)
        results.queue.add(listOf(Uri.parse(upload)))
        clickLabel("Upload files")
        rule.onNodeWithText("report.txt").assertExists()

        results.queue.add(Uri.parse("content://docs/saved.md"))
        clickLabel("Actions for notes.md")
        clickText("Download")
        rule.waitForIdle()
        results.launched.last() shouldBe "notes.md"
        documents.outputs.getValue("content://docs/saved.md").toString() shouldBe "hello"

        sftp.transferGate = CompletableDeferred()
        results.queue.add(Uri.parse("content://docs/slow.md"))
        clickLabel("Actions for notes.md")
        clickText("Download")
        sftp.file("$HOME/later.txt", "x")
        documents.inputs["content://docs/later.txt"] = "later".toByteArray()
        results.queue.add(listOf(Uri.parse("content://docs/later.txt")))
        clickLabel("Upload files")

        clickLabel("Transfers")
        rule.onAllNodesWithText("Done").assertCountEquals(2)
        rule.onNodeWithText("Waiting").assertExists()
        clickLabel("Cancel transfer of notes.md")
        rule.onNodeWithText("Cancelled").assertExists()
        rule.onNodeWithText("Failed: $HOME/later.txt already exists").assertExists()
        clickText("Clear finished")
        clickText("Close")
    }

    @Test
    fun `transfer progress with and without a known size`() {
        show()
        sftp.transferGate = CompletableDeferred()
        documents.inputs["content://docs/a"] = ByteArray(1000)
        documents.names["content://docs/a"] = DocumentInfo("a", 1000)
        results.queue.add(listOf(Uri.parse("content://docs/a")))
        clickLabel("Upload files")
        clickLabel("Transfers")
        rule.onNodeWithText("500 B").assertExists()
        clickText("Close")
        sftp.transferGate!!.complete(Unit)
        sftp.transferGate = CompletableDeferred()
        documents.inputs["content://docs/b"] = ByteArray(10)
        results.queue.add(listOf(Uri.parse("content://docs/b")))
        clickLabel("Upload files")
        clickLabel("Transfers")
        rule.onNodeWithText("5 B").assertExists()
        sftp.transferGate!!.complete(Unit)
    }

    @Test
    fun `cancelled pickers do nothing`() {
        val vm = show()
        results.queue.add(emptyList<Uri>())
        clickLabel("Upload files")
        results.queue.add(null)
        clickLabel("Actions for notes.md")
        clickText("Download")
        rule.waitForIdle()
        vm.transfers.value shouldBe emptyList()
    }

    @Test
    fun `edits, saves and resolves conflicts`() {
        show()
        clickText("notes.md")
        rule.onNode(hasSetTextAction()).performTextReplacement("hello there")
        rule.onNodeWithText("notes.md •").assertExists()
        clickLabel("Save file")
        waitForText("Saved")
        sftp.text("$HOME/notes.md") shouldBe "hello there"

        rule.onNode(hasSetTextAction()).performTextReplacement("mine")
        sftp.touch("$HOME/notes.md", "theirs")
        clickLabel("Save file")
        rule.onNodeWithText("Changed on the host").assertExists()
        clickText("Cancel")
        clickLabel("Save file")
        clickText("Overwrite")
        sftp.text("$HOME/notes.md") shouldBe "mine"

        rule.onNode(hasSetTextAction()).performTextReplacement("lost")
        sftp.touch("$HOME/notes.md", "fresh")
        clickLabel("Save file")
        clickText("Reload")
        rule.onNodeWithText("fresh").assertExists()

        rule.onNode(hasSetTextAction()).performTextReplacement("unsaved")
        clickLabel("Close the editor")
        rule.onNodeWithText("Discard your changes?").assertExists()
        clickText("Keep editing")
        clickLabel("Close the editor")
        clickText("Discard")
        rule.onNodeWithText("src").assertExists()
        clickText("notes.md")
        clickLabel("Close the editor")
        rule.onNodeWithText("src").assertExists()
    }

    @Test
    fun `warns before editing binary files`() {
        sftp.file("$HOME/a.out", bytes = byteArrayOf(0x7F, 0x00, 0x01))
        show()
        clickText("a.out")
        rule
            .onNodeWithText(
                "This file doesn't look like UTF-8 text. Saving it from the editor may damage it.",
            ).assertExists()
        clickText("Edit anyway")
        rule.onNodeWithText("Edit anyway").assertDoesNotExist()
    }

    @Test
    fun `shows connection failures with a retry`() {
        connection.sftpFailure = IOException("subsystem request failed")
        show()
        rule.onNodeWithText("Couldn't open files on this host").assertExists()
        rule.onNodeWithText("subsystem request failed").assertExists()
        connection.sftpFailure = null
        clickText("Retry")
        rule.onNodeWithText("notes.md").assertExists()
        runBlocking { manager.closeAll() }
        rule.onNodeWithText("Couldn't open files on this host").assertExists()
        rule.onNodeWithText("subsystem request failed").assertDoesNotExist()
    }
}
