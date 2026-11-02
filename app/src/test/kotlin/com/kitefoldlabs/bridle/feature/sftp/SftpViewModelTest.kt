package com.kitefoldlabs.bridle.feature.sftp

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.ssh.AuthenticationFailedException
import com.kitefoldlabs.bridle.core.ssh.RemoteFile
import com.kitefoldlabs.bridle.core.ssh.Sftp
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.feature.FakeConnection
import com.kitefoldlabs.bridle.feature.FakeDocuments
import com.kitefoldlabs.bridle.feature.FakeSftpSession
import com.kitefoldlabs.bridle.feature.FakeSftpSession.Companion.HOME
import com.kitefoldlabs.bridle.feature.connections
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Clears [viewModel] the way its owner would, calling `onCleared`. */
fun clear(viewModel: ViewModel) {
    val store = ViewModelStore()
    ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
        },
    )[viewModel::class.java]
    store.clear()
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SftpViewModelTest {
    private val unconfined = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sftp = FakeSftpSession()
    private val connection = FakeConnection(sftp)
    private var next: () -> SshConnection = { connection }
    private val manager: ConnectionManager = connections({ next() }, unconfined)
    private val documents = FakeDocuments()
    private val queue = TransferQueue(manager, documents, unconfined, {})

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        sftp.dir("$HOME/src")
        sftp.file("$HOME/notes.md", "hello")
        sftp.file("$HOME/.bashrc", "export X=1")
        sftp.link("$HOME/code", "$HOME/src")
        sftp.link("$HOME/readme", "$HOME/notes.md")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle(mapOf(SftpDestination.ARG_HOST to 7L))) =
        SftpViewModel(saved, manager, queue, unconfined, Dispatchers.Unconfined)

    private fun SftpViewModel.names() = state.value.visible.map { it.name }

    private fun SftpViewModel.entry(name: String): RemoteFile = state.value.entries.first { it.name == name }

    @Test
    fun `opens the home directory with folders first and hidden files off`() {
        val vm = viewModel()
        vm.hostId shouldBe 7L
        vm.state.value.connecting shouldBe false
        vm.state.value.path shouldBe HOME
        vm.names() shouldBe listOf("src", "code", "notes.md", "readme")
        vm.setShowHidden(true)
        vm.names() shouldBe listOf("src", ".bashrc", "code", "notes.md", "readme")
        vm.setSort(SftpSort.SIZE)
        vm.state.value.sort shouldBe SftpSort.SIZE
    }

    @Test
    fun `navigates into folders, through links and back up`() {
        val vm = viewModel()
        vm.activate(vm.entry("src"))
        vm.state.value.path shouldBe "$HOME/src"
        vm.state.value.entries shouldBe emptyList()
        vm.open(Sftp.parent("$HOME/src"))
        vm.activate(vm.entry("code"))
        vm.state.value.path shouldBe "$HOME/code"
        vm.open(HOME)
        vm.activate(vm.entry("readme"))
        vm.state.value.editor!!
            .text shouldBe "hello"
        vm.closeEditor()
        vm.activate(vm.entry("notes.md"))
        vm.state.value.editor!!
            .path shouldBe "$HOME/notes.md"
        vm.closeEditor()
        sftp.failNext = IOException("permission denied")
        vm.open("/root")
        vm.state.value.notice shouldBe SftpNotice.Failed("permission denied")
        vm.state.value.loading shouldBe false
        vm.state.value.path shouldBe HOME
        vm.consumeNotice()
        vm.state.value.notice
            .shouldBeNull()
    }

    @Test
    fun `creates, renames, deletes and chmods`() {
        val vm = viewModel()
        vm.mkdir("build")
        sftp.exists("$HOME/build") shouldBe true
        vm.names() shouldBe listOf("build", "src", "code", "notes.md", "readme")
        vm.mkdir("a/b")
        vm.state.value.notice shouldBe SftpNotice.InvalidName
        vm.rename(vm.entry("notes.md"), "todo.md")
        sftp.text("$HOME/todo.md") shouldBe "hello"
        vm.chmod(vm.entry("todo.md"), "600")
        sftp.permissions("$HOME/todo.md") shouldBe 0b110_000_000
        vm.chmod(vm.entry("todo.md"), "9")
        vm.state.value.notice shouldBe SftpNotice.InvalidMode
        sftp.file("$HOME/build/out.o", "x")
        vm.delete(vm.entry("build"))
        sftp.exists("$HOME/build/out.o") shouldBe false
        vm.delete(vm.entry("todo.md"))
        sftp.exists("$HOME/todo.md") shouldBe false
        sftp.failNext = IOException("read-only file system")
        vm.mkdir("new")
        vm.state.value.notice shouldBe SftpNotice.Failed("read-only file system")
        sftp.failNext = IllegalStateException()
        vm.mkdir("other")
        vm.state.value.notice shouldBe SftpNotice.Failed("IllegalStateException")
    }

    @Test
    fun `edits and saves a file`() {
        val vm = viewModel()
        vm.openEditor(vm.entry("notes.md"))
        val editor = vm.state.value.editor!!
        editor.name shouldBe "notes.md"
        editor.dirty shouldBe false
        editor.binary shouldBe false
        editor.editable shouldBe true
        vm.editText("hello world")
        vm.state.value.editor!!
            .dirty shouldBe true
        vm.save()
        sftp.text("$HOME/notes.md") shouldBe "hello world"
        vm.state.value.editor!!
            .dirty shouldBe false
        vm.state.value.editor!!
            .saving shouldBe false
        vm.state.value.notice shouldBe SftpNotice.Saved
        sftp.failNext = IOException("disk full")
        vm.editText("again")
        vm.save()
        vm.state.value.notice shouldBe SftpNotice.Failed("disk full")
        vm.state.value.editor!!
            .saving shouldBe false
    }

    @Test
    fun `a file changed on the host raises a conflict`() {
        val vm = viewModel()
        vm.openEditor(vm.entry("notes.md"))
        vm.editText("mine")
        sftp.touch("$HOME/notes.md", "theirs")
        vm.save()
        vm.state.value.editor!!
            .conflict shouldBe true
        vm.dismissConflict()
        vm.state.value.editor!!
            .conflict shouldBe false
        vm.save()
        vm.save(overwrite = true)
        sftp.text("$HOME/notes.md") shouldBe "mine"
        sftp.touch("$HOME/notes.md", "theirs again")
        vm.editText("lost")
        vm.reload()
        vm.state.value.editor!!
            .text shouldBe "theirs again"
    }

    @Test
    fun `large and binary files`() {
        sftp.file("$HOME/big.iso", bytes = ByteArray((Sftp.EDITOR_LIMIT + 1).toInt()))
        sftp.file("$HOME/a.out", bytes = byteArrayOf(0x7F, 0x45, 0x00, 0x01))
        val vm = viewModel()
        vm.openEditor(vm.entry("big.iso"))
        vm.state.value.notice shouldBe SftpNotice.TooLarge(Sftp.EDITOR_LIMIT + 1, Sftp.EDITOR_LIMIT)
        vm.state.value.editor
            .shouldBeNull()
        vm.openEditor(vm.entry("a.out"))
        vm.state.value.editor!!
            .binary shouldBe true
        vm.state.value.editor!!
            .editable shouldBe false
        vm.editAnyway()
        vm.state.value.editor!!
            .editable shouldBe true
    }

    @Test
    fun `editor actions without an open editor are ignored`() {
        val vm = viewModel()
        vm.save()
        vm.reload()
        vm.editText("x")
        vm.dismissConflict()
        vm.state.value.editor
            .shouldBeNull()
    }

    @Test
    fun `reports connection failures and retries`() {
        connection.sftpFailure = IOException("subsystem request failed")
        val vm = viewModel()
        vm.state.value.failed shouldBe true
        vm.state.value.errorDetail shouldBe "subsystem request failed"
        // Nothing to act on until connected.
        vm.refresh()
        vm.mkdir("x")
        vm.upload(listOf(Uri.parse("content://docs/a")))
        vm.openEditor(RemoteFile("x", "/x", RemoteFile.Type.FILE, 0, 0, 0))
        connection.sftpFailure = null
        vm.retry()
        vm.state.value.failed shouldBe false
        vm.state.value.path shouldBe HOME
    }

    @Test
    fun `rejected credentials and disconnect all`() {
        next = { throw AuthenticationFailedException("denied") }
        val vm = viewModel()
        vm.state.value.failed shouldBe true
        vm.state.value.errorDetail shouldBe "denied"
        next = { connection }
        vm.retry()
        vm.state.value.failed shouldBe false
        runBlocking { manager.closeAll() }
        vm.state.value.failed shouldBe true
        vm.state.value.errorDetail
            .shouldBeNull()
    }

    @Test
    fun `reopens sftp after a reconnect and keeps the folder`() {
        val vm = viewModel()
        vm.open("$HOME/src")
        val second = FakeSftpSession().apply { dir("$HOME/src") }
        next = { FakeConnection(second) }
        runBlocking { manager.reconnect(7) }
        vm.state.value.path shouldBe "$HOME/src"
        sftp.closed shouldBe true
        vm.mkdir("fresh")
        second.exists("$HOME/src/fresh") shouldBe true
    }

    @Test
    fun `uploads into the current folder and refreshes when they land`() {
        val vm = viewModel()
        documents.inputs["content://docs/report.pdf"] = "pdf".toByteArray()
        documents.names["content://docs/report.pdf"] = DocumentInfo("report.pdf", 3)
        vm.upload(listOf(Uri.parse("content://docs/report.pdf")))
        sftp.text("$HOME/report.pdf") shouldBe "pdf"
        vm.transfers.value
            .single()
            .status shouldBe TransferStatus.DONE
        vm.names() shouldBe listOf("src", "code", "notes.md", "readme", "report.pdf")
        vm.clearTransfers()
        vm.transfers.value shouldBe emptyList()
    }

    @Test
    fun `upload problems are reported`() {
        val vm = viewModel()
        val bad =
            object : DocumentAccess by documents {
                override fun describe(uri: Uri): DocumentInfo = throw SecurityException("no permission")
            }
        val failing =
            SftpViewModel(
                SavedStateHandle(mapOf(SftpDestination.ARG_HOST to 7L)),
                manager,
                TransferQueue(manager, bad, unconfined, {}),
                unconfined,
                Dispatchers.Unconfined,
            )
        failing.upload(listOf(Uri.parse("content://docs/x")))
        failing.state.value.notice shouldBe SftpNotice.Failed("no permission")
        vm.state.value.notice
            .shouldBeNull()
    }

    @Test
    fun `downloads to a picked document and cancels`() {
        val vm = viewModel()
        val notes = vm.entry("notes.md")
        vm.downloadTo(Uri.parse("content://docs/ignored"))
        vm.transfers.value shouldBe emptyList()
        vm.requestDownload(notes)
        vm.downloadTo(null)
        vm.downloadTo(Uri.parse("content://docs/late")) // the cancelled request is gone
        vm.transfers.value shouldBe emptyList()
        vm.requestDownload(notes)
        vm.downloadTo(Uri.parse("content://docs/notes.md"))
        documents.outputs.getValue("content://docs/notes.md").toString() shouldBe "hello"
        sftp.transferGate = CompletableDeferred()
        vm.requestDownload(notes)
        vm.downloadTo(Uri.parse("content://docs/slow.md"))
        val running = vm.transfers.value.last()
        running.status shouldBe TransferStatus.RUNNING
        vm.cancelTransfer(running.id)
        vm.transfers.value
            .last()
            .status shouldBe TransferStatus.CANCELLED
        documents.deleted shouldBe listOf("content://docs/slow.md")
    }

    @Test
    fun `a download survives Android recreating the app while the picker is open`() {
        val saved = SavedStateHandle(mapOf(SftpDestination.ARG_HOST to 7L))
        val before = viewModel(saved)
        before.requestDownload(before.entry("notes.md"))
        clear(before)
        // The process may be gone by now; only the saved state comes back with the result.
        val after = viewModel(saved)
        after.downloadTo(Uri.parse("content://docs/after.md"))
        documents.outputs.getValue("content://docs/after.md").toString() shouldBe "hello"
        after.downloadTo(Uri.parse("content://docs/twice.md")) // one picker, one download
        documents.outputs.containsKey("content://docs/twice.md") shouldBe false
    }

    @Test
    fun `a slow listing can't replace the folder opened after it`() {
        val vm = viewModel()
        val slow = CompletableDeferred<Unit>()
        sftp.listGates["$HOME/src"] = slow
        vm.open("$HOME/src")
        vm.open(HOME)
        slow.complete(Unit)
        vm.state.value.path shouldBe HOME
        vm.state.value.loading shouldBe false
    }

    @Test
    fun `edge cases`() {
        shouldThrow<IllegalStateException> {
            SftpViewModel(SavedStateHandle(), manager, queue, unconfined, Dispatchers.Unconfined)
        }
        sftp.link("$HOME/dangling", "$HOME/gone")
        val vm = viewModel()
        vm.activate(vm.entry("dangling"))
        vm.state.value.notice
            .shouldBeInstanceOf<SftpNotice.Failed>()
        vm.consumeNotice()

        // An upload that finishes after the user moved to another folder doesn't refresh it.
        sftp.transferGate = CompletableDeferred()
        documents.inputs["content://docs/x"] = "x".toByteArray()
        vm.upload(listOf(Uri.parse("content://docs/x")))
        vm.open("$HOME/src")
        sftp.transferGate!!.complete(Unit)
        vm.transfers.value
            .single()
            .status shouldBe TransferStatus.DONE
        vm.state.value.entries shouldBe emptyList()

        vm.open(HOME)
        vm.openEditor(vm.entry("notes.md"))
        clear(vm)
        vm.save()
        vm.open(HOME)
        vm.state.value.editor!!
            .saving shouldBe false
    }

    @Test
    fun `clearing the view model closes sftp and releases the connection`() {
        val vm = viewModel()
        vm.state.value.path
            .shouldNotBeNull()
        clear(vm)
        sftp.closed shouldBe true
        vm.state.value.notice
            .shouldBeNull()
        vm.state.value.shouldBeInstanceOf<SftpUiState>()
    }
}
