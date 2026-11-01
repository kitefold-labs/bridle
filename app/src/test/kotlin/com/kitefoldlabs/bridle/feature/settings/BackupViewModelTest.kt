package com.kitefoldlabs.bridle.feature.settings

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.backup.Argon2Params
import com.kitefoldlabs.bridle.core.backup.BackupCrypto
import com.kitefoldlabs.bridle.core.backup.BackupPayload
import com.kitefoldlabs.bridle.core.backup.EntityKind
import com.kitefoldlabs.bridle.core.backup.ImportIssue
import com.kitefoldlabs.bridle.core.backup.ImportPlan
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.model.Snippet
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.feature.FakeDocuments
import com.kitefoldlabs.bridle.feature.eventually
import com.kitefoldlabs.bridle.feature.sftp.clear
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var source: DataModule
    private lateinit var target: DataModule
    private val documents = FakeDocuments()
    private val cheap = Argon2Params(Argon2Params.MIN_MEMORY_KIB, 1, 1)
    private val passphrase = "correct horse battery"

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        source = testData(context)
        target = testData(context)
        runBlocking {
            source.hosts.upsert(Host(label = "dev", hostname = "dev.example.com", username = "me"))
            source.snippets.upsert(Snippet(name = "up", body = "uptime"))
        }
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(data: DataModule) =
        BackupViewModel(data.backup, documents, cheap, Dispatchers.Unconfined) { 0L }

    private fun uri(name: String) = Uri.parse("content://docs/$name")

    private fun exportFile(): ByteArray {
        val vm = viewModel(source)
        vm.startExport()
        vm.submitExportPassphrase(passphrase.toCharArray(), passphrase.toCharArray())
        vm.exportTo(uri("backup"))
        eventually { vm.state.value.result != null }
        return documents.outputs.getValue(uri("backup").toString()).toByteArray()
    }

    @Test
    fun `export checks the passphrase and writes an encrypted file`() {
        val vm = viewModel(source)
        vm.startExport()
        vm.state.value.exportDialog shouldBe true
        vm.submitExportPassphrase("short".toCharArray(), "short".toCharArray())
        vm.state.value.passphraseProblem shouldBe PassphraseProblem.TOO_SHORT
        vm.submitExportPassphrase(passphrase.toCharArray(), "different passphrase".toCharArray())
        vm.state.value.passphraseProblem shouldBe PassphraseProblem.MISMATCH
        val typed = passphrase.toCharArray()
        vm.submitExportPassphrase(typed, passphrase.toCharArray())
        vm.state.value.exportDialog shouldBe false
        vm.state.value.pickExportFile shouldBe true
        vm.state.value.suggestedName shouldMatch Regex("bridle-1970-01-01\\.bridle")
        vm.exportPickerShown()
        vm.state.value.pickExportFile shouldBe false
        vm.exportTo(uri("backup"))
        eventually { vm.state.value.result != null }
        vm.state.value.result shouldBe BackupResult.Exported
        typed.all { it == '\u0000' } shouldBe true // wiped
        val payload =
            BackupCrypto.decrypt(
                documents.outputs.getValue(uri("backup").toString()).toByteArray(),
                passphrase.toCharArray(),
            )
        payload.hosts.single().label shouldBe "dev"
        vm.exportTo(uri("again")) // passphrase already used
        documents.outputs.containsKey(uri("again").toString()) shouldBe false
        vm.dismissResult()
        vm.state.value.result
            .shouldBeNull()
    }

    @Test
    fun `cancelling or failing an export forgets the passphrase`() {
        val vm = viewModel(source)
        vm.startExport()
        vm.submitExportPassphrase(passphrase.toCharArray(), passphrase.toCharArray())
        vm.exportTo(null)
        vm.state.value.working shouldBe false
        vm.startExport()
        vm.submitExportPassphrase(passphrase.toCharArray(), passphrase.toCharArray())
        vm.submitExportPassphrase(passphrase.toCharArray(), passphrase.toCharArray()) // replaces the first
        vm.exportTo(uri("readonly"))
        eventually { vm.state.value.result != null }
        vm.state.value.result
            .shouldBeInstanceOf<BackupResult.Failed>()
        vm.startExport()
        vm.cancelExport()
        vm.state.value.exportDialog shouldBe false
        vm.startExport()
        vm.submitExportPassphrase(passphrase.toCharArray(), passphrase.toCharArray())
        clear(vm)
    }

    @Test
    fun `import reviews the plan, then applies it`() {
        documents.inputs[uri("backup").toString()] = exportFile()
        val vm = viewModel(target)
        vm.importFrom(null)
        vm.submitImportPassphrase("x".toCharArray()) // nothing read yet
        vm.applyImport() // nothing to apply
        vm.importFrom(uri("backup"))
        eventually { vm.state.value.importDialog }
        vm.submitImportPassphrase("wrong passphrase!".toCharArray())
        eventually { vm.state.value.importProblem != null }
        vm.state.value.importProblem shouldBe ImportProblem.WRONG_PASSPHRASE
        vm.submitImportPassphrase(passphrase.toCharArray())
        eventually { vm.state.value.review != null }
        val review = vm.state.value.review!!
        review.plan.hosts.size shouldBe 1
        review.duplicates shouldBe 0
        vm.state.value.importDialog shouldBe false
        vm.applyImport()
        eventually { vm.state.value.result != null }
        vm.state.value.result shouldBe BackupResult.Imported(hosts = 1, total = 2)
        runBlocking {
            target.hosts
                .observeAll()
                .first()
                .single()
                .label
        } shouldBe "dev"
        clear(vm)
    }

    @Test
    fun `import problems`() {
        val good = exportFile()
        val vm = viewModel(target)
        vm.importFrom(uri("missing"))
        eventually { vm.state.value.importProblem != null }
        vm.state.value.importProblem shouldBe ImportProblem.UNREADABLE
        vm.dismissResult()
        documents.inputs[uri("huge").toString()] = ByteArray(BackupCrypto.MAX_BACKUP_BYTES + 1)
        vm.importFrom(uri("huge"))
        eventually { vm.state.value.importProblem == ImportProblem.TOO_LARGE }
        documents.inputs[uri("junk").toString()] = "not a backup".toByteArray()
        vm.importFrom(uri("junk"))
        eventually { vm.state.value.importDialog }
        vm.submitImportPassphrase(passphrase.toCharArray())
        eventually { vm.state.value.importProblem == ImportProblem.NOT_A_BACKUP }
        documents.inputs[uri("newer").toString()] = good.copyOf().also { it[8] = 9 }
        vm.importFrom(uri("newer"))
        eventually { vm.state.value.importDialog && vm.state.value.importProblem == null }
        vm.submitImportPassphrase(passphrase.toCharArray())
        eventually { vm.state.value.importProblem == ImportProblem.NEWER_VERSION }
        vm.cancelImport()
        vm.state.value.importDialog shouldBe false
    }

    @Test
    fun `review groups the plan's issues`() {
        val invalid = Validation.Invalid("hostname", Validation.Reason.BLANK)
        val issues =
            listOf(
                ImportIssue.Duplicate(EntityKind.HOST, "dev"),
                ImportIssue.Duplicate(EntityKind.SNIPPET, "up"),
                ImportIssue.KnownHostKeyConflict("dev", 22, "ssh-ed25519", "SHA256:a", "SHA256:b"),
                ImportIssue.MissingIdentity(
                    "laptop",
                    KeyAlgorithm.ED25519,
                    KeySource.KEYSTORE,
                    "ssh-ed25519 AAAA",
                    listOf("dev"),
                ),
                ImportIssue.Invalid(EntityKind.HOST, "bad", invalid),
                ImportIssue.Orphaned(EntityKind.PORT_FORWARD, "8080"),
                ImportIssue.ReferenceCleared(EntityKind.HOST, "web", "jumpHostId"),
            )
        val review =
            ImportReview(
                BackupPayload(createdAtEpochMs = 0, appVersion = "1"),
                ImportPlan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), issues),
            )
        review.duplicates shouldBe 2
        review.conflicts.single().backupFingerprint shouldBe "SHA256:b"
        review.missingKeys.single().usedBy shouldBe listOf("dev")
        review.skipped.map { it.label } shouldBe listOf("bad", "8080", "web")
        BackupViewModel(source.backup, documents).state.value.shouldNotBeNull()
    }
}
