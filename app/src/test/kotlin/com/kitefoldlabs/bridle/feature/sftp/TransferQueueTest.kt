package com.kitefoldlabs.bridle.feature.sftp

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.feature.FakeConnection
import com.kitefoldlabs.bridle.feature.FakeDocuments
import com.kitefoldlabs.bridle.feature.FakeSftpSession
import com.kitefoldlabs.bridle.feature.FakeSftpSession.Companion.HOME
import com.kitefoldlabs.bridle.feature.connections
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TransferQueueTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sftp = FakeSftpSession()
    private val connection = FakeConnection(sftp)
    private val documents = FakeDocuments()
    private var serviceStarts = 0
    private val queue = TransferQueue(connections({ connection }, scope), documents, scope) { serviceStarts++ }

    private fun uri(name: String) = Uri.parse("content://docs/$name")

    @Test
    fun `uploads with progress and never overwrites`() {
        documents.inputs[uri("a.txt").toString()] = "abcd".toByteArray()
        documents.names[uri("a.txt").toString()] = DocumentInfo("a/b.txt", 4)
        val transfer = queue.upload(1, uri("a.txt"), HOME)
        transfer.name shouldBe "a_b.txt"
        serviceStarts shouldBe 1 // each transfer keeps the foreground service up
        transfer.remotePath shouldBe "$HOME/a_b.txt"
        sftp.text("$HOME/a_b.txt") shouldBe "abcd"
        val done = queue.transfers.value.single()
        done.status shouldBe TransferStatus.DONE
        done.transferred shouldBe 4
        done.total shouldBe 4
        queue.hasActiveWork shouldBe false

        queue.upload(1, uri("a.txt"), HOME)
        val failed = queue.transfers.value.last()
        failed.status shouldBe TransferStatus.FAILED
        failed.error!! shouldContain "already exists"
        documents.deleted shouldBe emptyList() // uploads leave no local cleanup
    }

    @Test
    fun `a provider's display name can't escape the folder`() {
        documents.inputs[uri("dots").toString()] = "x".toByteArray()
        documents.names[uri("dots").toString()] = DocumentInfo("..", 1)
        queue.upload(1, uri("dots"), HOME).remotePath shouldBe "$HOME/upload"
        sftp.text("$HOME/upload") shouldBe "x"
    }

    @Test
    fun `failures without a message name the exception`() {
        sftp.failNext = IllegalStateException()
        documents.inputs[uri("b").toString()] = ByteArray(1)
        queue.upload(1, uri("b"), HOME)
        queue.transfers.value
            .single()
            .error shouldBe "IllegalStateException"
    }

    @Test
    fun `a transfer cancelled before it starts is not left queued`() {
        val dispatcher = StandardTestDispatcher()
        val lazy = TransferQueue(connections({ connection }, scope), documents, CoroutineScope(dispatcher)) {}
        documents.inputs[uri("c").toString()] = ByteArray(1)
        val transfer = lazy.upload(1, uri("c"), HOME)
        lazy.hasActiveWork shouldBe true
        lazy.cancel(transfer.id)
        dispatcher.scheduler.runCurrent()
        lazy.transfers.value
            .single()
            .status shouldBe TransferStatus.CANCELLED
        lazy.hasActiveWork shouldBe false
        sftp.exists("$HOME/c") shouldBe false

        // A download cancelled that early removes the document the user created for it.
        val download = lazy.download(1, "$HOME/d", uri("d"))
        lazy.cancel(download.id)
        dispatcher.scheduler.runCurrent()
        lazy.transfers.value
            .last()
            .status shouldBe TransferStatus.CANCELLED
        documents.deleted shouldBe listOf(uri("d").toString())
    }

    @Test
    fun `cancelling an upload leaves nothing on the host`() {
        sftp.transferGate = CompletableDeferred()
        documents.inputs[uri("big").toString()] = ByteArray(10)
        val transfer = queue.upload(1, uri("big"), HOME)
        queue.hasActiveWork shouldBe true
        queue.transfers.value
            .single()
            .status shouldBe TransferStatus.RUNNING
        queue.transfers.value
            .single()
            .transferred shouldBe 5
        queue.cancel(transfer.id)
        queue.cancel(transfer.id) // already gone
        queue.transfers.value
            .single()
            .status shouldBe TransferStatus.CANCELLED
        sftp.exists("$HOME/big") shouldBe false
    }

    @Test
    fun `transfers run one at a time`() {
        val gate = CompletableDeferred<Unit>()
        sftp.transferGate = gate
        sftp.file("$HOME/one", "1")
        sftp.file("$HOME/two", "2")
        queue.download(1, "$HOME/one", uri("one"))
        queue.download(1, "$HOME/two", uri("two"))
        queue.transfers.value.map { it.status } shouldBe listOf(TransferStatus.RUNNING, TransferStatus.QUEUED)
        gate.complete(Unit)
        queue.transfers.value.map { it.status } shouldBe listOf(TransferStatus.DONE, TransferStatus.DONE)
        documents.outputs.getValue(uri("two").toString()).toString() shouldBe "2"
        queue.clearFinished()
        queue.transfers.value shouldBe emptyList()
    }

    @Test
    fun `a failed download removes the partial document`() {
        queue.download(1, "$HOME/missing", uri("missing"))
        queue.transfers.value
            .single()
            .status shouldBe TransferStatus.FAILED
        documents.deleted shouldBe listOf(uri("missing").toString())
        queue.download(1, "$HOME/missing", Uri.parse("content://docs/readonly"))
        queue.transfers.value
            .last()
            .error shouldBe "content://docs/readonly"
    }

    @Test
    fun `content resolver documents`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val docs = ContentResolverDocuments(context)
        val file = File.createTempFile("bridle", ".txt").apply { writeText("from disk") }
        val fileUri = Uri.fromFile(file)
        docs.describe(fileUri) shouldBe DocumentInfo(file.name, null)
        docs.describe(Uri.parse("content://nothing")) shouldBe DocumentInfo("upload", null)
        docs.openInput(fileUri).use { String(it.readBytes()) } shouldBe "from disk"
        docs.openOutput(fileUri).use { it.write("replaced".toByteArray()) }
        file.readText() shouldBe "replaced"
        docs.delete(fileUri) // not a document provider: ignored

        Robolectric.setupContentProvider(Docs::class.java, "docs")
        docs.describe(Uri.parse("content://docs/1")) shouldBe DocumentInfo("named.txt", 42)
        docs.describe(Uri.parse("content://docs/unsized")) shouldBe DocumentInfo("named.txt", null)
        docs.describe(Uri.parse("content://docs/empty")) shouldBe DocumentInfo("empty", null)
        shouldThrow<FileNotFoundException> { docs.openInput(Uri.parse("content://docs/1")) }
    }

    /** Answers OpenableColumns queries; opening documents fails. */
    class Docs : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            when (uri.lastPathSegment) {
                "empty" -> Unit
                "unsized" -> cursor.addRow(arrayOf<Any?>("named.txt", null))
                else -> cursor.addRow(arrayOf<Any?>("named.txt", 42L))
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }
}
