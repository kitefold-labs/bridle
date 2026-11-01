package com.kitefoldlabs.bridle.feature.hosts

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.feature.MainDispatcherRule
import com.kitefoldlabs.bridle.feature.TestEnv
import com.kitefoldlabs.bridle.feature.await
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Registers [text] (or a failing stream) as the content of a document URI. */
internal fun TestEnv.document(
    name: String,
    text: String?,
): Uri {
    val uri = Uri.parse("content://docs.test/$name")
    val stream: InputStream =
        if (text == null) {
            object : InputStream() {
                override fun read(): Int = throw IOException("unreadable")
            }
        } else {
            ByteArrayInputStream(text.toByteArray())
        }
    shadowOf(context.contentResolver).registerInputStream(uri, stream)
    return uri
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SshConfigImportViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val env = TestEnv()
    private val documents = DocumentReader(env.context)

    @After
    fun tearDown() = env.close()

    private val config = "Host dev\n  HostName dev.example\n  User nate\nHost nouser\n  HostName n.example\nMatch all\n"

    @Test
    fun `document reader reads, limits and reports failures`() =
        runBlocking<Unit> {
            documents.readText(env.document("ok", "hello")) shouldBe "hello"
            documents.readText(env.document("exact", "abc"), maxChars = 3) shouldBe "abc"
            shouldThrow<DocumentTooLargeException> { documents.readText(env.document("big", "abcd"), maxChars = 3) }
            shouldThrow<IOException> { documents.readText(env.document("bad", null)) }
            val resolver = mockk<ContentResolver> { every { openInputStream(any()) } returns null }
            val context = mockk<Context> { every { contentResolver } returns resolver }
            shouldThrow<IOException> { DocumentReader(context).readText(Uri.EMPTY) }
        }

    @Test
    fun `paste, preview, adjust and import`() =
        runBlocking<Unit> {
            env.data.hosts.upsert(Host(label = "existing", hostname = "e.example", username = "u"))
            val vm = SshConfigImportViewModel(env.data.hosts, documents)
            vm.import() // no plan yet: ignored
            vm.setText(config)
            vm.parse()
            val planned = vm.state.await { it.plan != null }
            planned.selected shouldBe setOf("dev")
            planned.warnings.size shouldBe 1
            vm.setDefaultUsername("admin")
            vm.state.await { s -> s.plan?.selectable?.size == 2 }.selected shouldBe setOf("dev")
            vm.toggle("nouser")
            vm.toggle("dev")
            vm.state.value.selected shouldBe setOf("nouser")
            vm.toggle("dev")
            vm.import()
            vm.import() // already importing: ignored
            vm.state.await { it.imported != null }.imported shouldBe 2
            env.data.hosts
                .observeAll()
                .first()
                .map { it.label }
                .toSet() shouldBe setOf("existing", "dev", "nouser")
        }

    @Test
    fun `nothing selected, edit and errors`() =
        runBlocking<Unit> {
            val vm = SshConfigImportViewModel(env.data.hosts, documents)
            vm.setText("Include ~/.ssh/other\n")
            vm.parse()
            vm.state.value.error shouldBe SshImportError.NOTHING_FOUND
            vm.state.value.warnings.size shouldBe 1
            vm.setDefaultUsername("x") // no plan: nothing to redo
            vm.setText("x".repeat(1_000_001))
            vm.state.value.error shouldBe null
            vm.parse()
            vm.state.value.error shouldBe SshImportError.TOO_LARGE

            vm.setText(config)
            vm.parse()
            vm.state.await { it.plan != null }
            vm.toggle("dev")
            vm.import() // nothing selected: ignored
            vm.state.value.imported shouldBe null
            vm.edit()
            vm.state.value.plan shouldBe null
            vm.state.value.text shouldBe config
        }

    @Test
    fun `a jump host deleted before the import is reported, not a crash`() =
        runBlocking<Unit> {
            val bastion = env.data.hosts.upsert(Host(label = "bastion", hostname = "b.example", username = "u"))
            val vm = SshConfigImportViewModel(env.data.hosts, documents)
            vm.setText("Host dev\n  HostName dev.example\n  User nate\n  ProxyJump bastion\n")
            vm.parse()
            vm.state.await { it.plan != null }
            env.data.hosts.delete(bastion)
            vm.import()
            vm.state.await { it.error == SshImportError.SAVE_FAILED }.busy shouldBe false
        }

    @Test
    fun `loading a file parses it`() =
        runBlocking<Unit> {
            val vm = SshConfigImportViewModel(env.data.hosts, documents)
            vm.loadFile(env.document("config", config))
            vm.state.await { it.plan != null }.text shouldBe config
            vm.loadFile(env.document("broken", null))
            vm.state.await { it.error == SshImportError.READ_FAILED }.busy shouldBe false
            vm.loadFile(env.document("huge", "x".repeat(DocumentReader.MAX_CHARS + 1)))
            vm.state.await { it.error == SshImportError.TOO_LARGE }
        }
}
