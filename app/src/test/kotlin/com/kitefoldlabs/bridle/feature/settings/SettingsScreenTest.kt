package com.kitefoldlabs.bridle.feature.settings

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.backup.Argon2Params
import com.kitefoldlabs.bridle.core.backup.BackupCrypto
import com.kitefoldlabs.bridle.core.backup.BackupPayload
import com.kitefoldlabs.bridle.core.backup.EntityKind
import com.kitefoldlabs.bridle.core.backup.ImportIssue
import com.kitefoldlabs.bridle.core.backup.ImportPlan
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.data.settings.ThemeMode
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.terminal.BackspaceMode
import com.kitefoldlabs.bridle.feature.EPOCH_UTC
import com.kitefoldlabs.bridle.feature.FakeDocuments
import com.kitefoldlabs.bridle.feature.FakeResults
import com.kitefoldlabs.bridle.feature.FakeUris
import com.kitefoldlabs.bridle.feature.TestHost
import com.kitefoldlabs.bridle.feature.eventually
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val data: DataModule = testData(ApplicationProvider.getApplicationContext())
    private val documents = FakeDocuments()
    private val results = FakeResults()
    private val uris = FakeUris()
    private var available = true
    private lateinit var backup: BackupViewModel

    @After
    fun tearDown() = data.close()

    private fun show(memory: () -> Long = { Long.MAX_VALUE }): SettingsViewModel {
        val vm = SettingsViewModel(data.settings, { available }, data.composeHistory)
        backup =
            BackupViewModel(
                data.backup,
                documents,
                Argon2Params(Argon2Params.MIN_MEMORY_KIB, 1, 1),
                Dispatchers.Default,
                memory,
                clock = EPOCH_UTC,
            )
        rule.setContent { TestHost(results, uris) { SettingsScreen(vm, backup) } }
        waitForText("Appearance")
        return vm
    }

    private fun settings() = runBlocking { data.settings.settings.first() }

    private fun waitForText(text: String) =
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun row(text: String): SemanticsNodeInteraction {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        return rule.onNodeWithText(text)
    }

    private fun click(text: String) = row(text).performClick()

    private fun clickInDialog(text: String) = rule.onNode(hasText(text) and hasAnyAncestor(isDialog())).performClick()

    private fun waitForField() =
        rule.waitUntil(20_000) {
            rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun `appearance and keyboard choices`() {
        show()
        click("Theme")
        clickInDialog("Dark")
        eventually { settings().theme == ThemeMode.DARK }
        click("Theme")
        clickInDialog("Cancel")
        click("Terminal font")
        clickInDialog("Serif monospace")
        eventually { settings().fontFamilyId == "serif-monospace" }
        rule
            .onNodeWithContentDescription(
                "Terminal font size: 14 sp",
            ).performSemanticsAction(SemanticsActions.SetProgress) {
                it(19.999f)
            }
        eventually { settings().terminalFontSizeSp == 20 }
        rule.onNodeWithText("Terminal font size: 20 sp").assertExists()
        click("Keep the screen on in terminals")
        eventually { settings().keepScreenOn }
        click("Remember compose history")
        eventually { !settings().rememberComposeHistory }
        click("Extra keys bar")
        clickInDialog("Claude Code")
        waitForText("Claude Code")
        click("Backspace sends")
        clickInDialog("Ctrl-H (^H)")
        eventually { settings().backspaceMode == BackspaceMode.CTRL_H }
    }

    @Test
    fun `the key bar JSON editor validates before saving`() {
        show()
        click("Edit key bar JSON")
        rule.onNodeWithText("Layout JSON").assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("{")
        rule.onNodeWithText("Save").performClick()
        waitForText("Not saved:")
        rule.onNodeWithContentDescription("Back").performClick()
        waitForText("Appearance")
        click("Extra keys bar")
        clickInDialog("Custom (JSON)")
        rule.onNode(hasSetTextAction()).performTextReplacement(
            """{"rows": [[{"label": "Esc", "action": {"type": "key", "key": "Escape"}}]]}""",
        )
        rule.onNodeWithText("Save").performClick()
        waitForText("Appearance")
        row("Custom (JSON)").assertExists()
    }

    @Test
    fun `security, notifications, Claude and privacy switches`() {
        show()
        click("App lock")
        eventually { settings().appLockEnabled }
        click("Block screenshots")
        eventually { settings().secureScreenEnabled }
        click("Claude needs permission")
        eventually { settings().notificationEvents.size == 3 }
        click("Notifications while connected")
        eventually { !settings().notificationsEnabled }
        row("A connection was lost").assertIsNotEnabled()
        click("Use bridle-helper when installed")
        eventually { !settings().useHelperByDefault }
        click("Offer to report crashes")
        eventually { settings().crashReportingOptIn }
    }

    @Test
    fun `app lock needs a screen lock`() {
        available = false
        runBlocking { data.settings.setAppLockEnabled(true) }
        show()
        row("Set up a screen lock or fingerprint on this phone first").assertExists()
        row("App lock").assertIsNotEnabled()
    }

    @Test
    fun `about pages and links`() {
        show()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Version", substring = true))
        click("Source code")
        uris.opened shouldContain Licences.SOURCE_URL
        click("Open-source licences")
        click("sshj")
        uris.opened shouldContain "https://github.com/hierynomus/sshj"
        rule.onNodeWithContentDescription("Back").performClick()
        click("Privacy statement")
        rule.onNodeWithText("Bridle collects no data", substring = true).assertExists()
        rule.onNodeWithText("Read the full privacy policy").performClick()
        uris.opened shouldContain Licences.PRIVACY_URL
        rule.onNodeWithContentDescription("Back").performClick()
        waitForText("Appearance")
    }

    @Test
    fun `exports and imports an encrypted backup`() {
        runBlocking { data.hosts.upsert(Host(label = "dev", hostname = "dev.example.com", username = "me")) }
        show()
        click("Export encrypted backup")
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("short")
        fields[1].performTextInput("short")
        clickInDialog("Choose file")
        rule.onNodeWithText("Too short. The minimum length is 10.").assertExists()
        fields[0].performTextReplacement("a long passphrase")
        clickInDialog("Choose file")
        rule.onNodeWithText("The passphrases don't match.").assertExists()
        fields[1].performTextReplacement("a long passphrase")
        results.queue.add(Uri.parse("content://docs/backup"))
        clickInDialog("Choose file")
        waitForText("Backup saved")
        results.launched.last() shouldBe "bridle-1970-01-01.bridle"
        clickInDialog("OK")

        click("Export encrypted backup")
        clickInDialog("Cancel")

        documents.inputs["content://docs/backup"] = documents.outputs.getValue("content://docs/backup").toByteArray()
        runBlocking {
            data.hosts.delete(
                data.hosts
                    .observeAll()
                    .first()
                    .single()
                    .id,
            )
        }
        results.queue.add(Uri.parse("content://docs/backup"))
        click("Import backup")
        waitForField()
        rule.onNode(hasSetTextAction()).performTextInput("the wrong one")
        clickInDialog("Open")
        waitForText("Wrong passphrase, or the backup file is damaged.")
        rule.onNode(hasSetTextAction()).performTextReplacement("a long passphrase")
        clickInDialog("Open")
        waitForText("Import this backup?")
        rule.onNodeWithText("Hosts: 1").assertExists()
        clickInDialog("Import")
        waitForText("Backup imported")
        rule.onNodeWithText("Added 1 entry, including 1 hosts.").assertExists()
        clickInDialog("OK")

        results.queue.add(Uri.parse("content://docs/backup"))
        click("Import backup")
        waitForField()
        clickInDialog("Cancel")

        results.queue.add(Uri.parse("content://docs/missing"))
        click("Import backup")
        waitForText("Couldn't import")
        rule.onNodeWithText("The file couldn't be read.").assertExists()
        clickInDialog("OK")
        results.queue.add(null)
        click("Import backup")
    }

    @Test
    fun `backup problems are explained`() {
        show()
        click("Export encrypted backup")
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("a long passphrase")
        fields[1].performTextInput("a long passphrase")
        results.queue.add(Uri.parse("content://docs/readonly"))
        clickInDialog("Choose file")
        waitForText("Backup failed")
        clickInDialog("OK")
        val good = documents.outputs.values.firstOrNull()

        documents.inputs["content://docs/huge"] = ByteArray(BackupCrypto.MAX_BACKUP_BYTES + 1)
        results.queue.add(Uri.parse("content://docs/huge"))
        click("Import backup")
        waitForText("This file is too large to be a Bridle backup.")
        clickInDialog("OK")

        documents.inputs["content://docs/junk"] = "junk".toByteArray()
        results.queue.add(Uri.parse("content://docs/junk"))
        click("Import backup")
        waitForField()
        rule.onNode(hasSetTextAction()).performTextInput("whatever it is")
        clickInDialog("Open")
        waitForText("This file isn't a Bridle backup.")
        clickInDialog("Cancel")
        good shouldBe null
    }

    @Test
    fun `memory and interrupted exports are explained`() {
        documents.inputs["content://docs/big-kdf"] =
            BackupCrypto.encrypt(
                BackupPayload(createdAtEpochMs = 0, appVersion = "1"),
                "a long passphrase".toCharArray(),
                Argon2Params(Argon2Params.MIN_MEMORY_KIB, 1, 1),
            )
        show(memory = { 0L })
        click("Export encrypted backup")
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("a long passphrase")
        fields[1].performTextInput("a long passphrase")
        results.queue.add(Uri.parse("content://docs/out"))
        clickInDialog("Choose file")
        waitForText("doesn't have enough free memory")
        clickInDialog("OK")
        results.queue.add(Uri.parse("content://docs/big-kdf"))
        click("Import backup")
        waitForField()
        rule.onNode(hasSetTextAction()).performTextInput("a long passphrase")
        clickInDialog("Open")
        waitForText("doesn't have enough free memory")
        clickInDialog("Cancel")
        // A picker result that arrives after Android recreated the screen: the passphrase is gone.
        rule.runOnIdle { backup.exportTo(Uri.parse("content://docs/orphan")) }
        waitForText("so nothing was saved")
        documents.deleted shouldContain "content://docs/orphan"
    }

    @Test
    fun `typed passphrases are never put in the saved instance state`() {
        val vm =
            BackupViewModel(
                data.backup,
                documents,
                Argon2Params(Argon2Params.MIN_MEMORY_KIB, 1, 1),
                Dispatchers.Default,
                clock = EPOCH_UTC,
            )
        val restoration = StateRestorationTester(rule)
        restoration.setContent { TestHost(results, uris) { BackupSection(vm) } }
        rule.onNodeWithText("Export encrypted backup").performClick()
        val typed = "a long passphrase"
        rule.onAllNodes(hasSetTextAction())[0].performTextInput(typed)
        val masked = hasSetTextAction() and hasText("•".repeat(typed.length))
        rule.onNode(masked).assertExists()
        // Saving and restoring state, as when Android recreates the activity or the process.
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Choose a long passphrase", substring = true).assertExists() // still open
        rule.onAllNodes(masked).assertCountEquals(0)
    }

    @Test
    fun `a backup from a newer version is refused`() {
        val payload = BackupPayload(createdAtEpochMs = 0, appVersion = "9")
        val bytes =
            BackupCrypto.encrypt(
                payload,
                "a long passphrase".toCharArray(),
                Argon2Params(Argon2Params.MIN_MEMORY_KIB, 1, 1),
            )
        bytes[8] = 9
        documents.inputs["content://docs/newer"] = bytes
        show()
        results.queue.add(Uri.parse("content://docs/newer"))
        click("Import backup")
        waitForField()
        rule.onNode(hasSetTextAction()).performTextInput("a long passphrase")
        clickInDialog("Open")
        waitForText("This backup was made by a newer version of Bridle.")
    }

    @Test
    fun `the import review lists every kind of issue`() {
        val issues =
            listOf(
                ImportIssue.Duplicate(EntityKind.HOST, "dev"),
                ImportIssue.KnownHostKeyConflict("dev", 22, "ssh-ed25519", "SHA256:aaa", "SHA256:bbb"),
                ImportIssue.MissingIdentity(
                    "laptop",
                    KeyAlgorithm.ED25519,
                    KeySource.KEYSTORE,
                    "ssh-ed25519 AAAA",
                    listOf("dev", "web"),
                ),
                ImportIssue.Invalid(EntityKind.HOST, "bad", Validation.Invalid("hostname", Validation.Reason.BLANK)),
            )
        val review =
            ImportReview(
                BackupPayload(createdAtEpochMs = 0, appVersion = "1"),
                ImportPlan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), issues),
            )
        val backup = BackupViewModel(data.backup, documents)
        rule.setContent { ImportReviewDialog(review, working = true, viewModel = backup) }
        rule.onNodeWithText("1 entry is already on this phone and will be kept as it is.").assertExists()
        rule.onNodeWithText("Host key conflict for dev:22 ssh-ed25519", substring = true).assertExists()
        rule.onNodeWithText("SHA256:bbb", substring = true).assertExists()
        rule.onNodeWithText("Key “laptop” isn't on this phone", substring = true).assertExists()
        rule.onNodeWithText("dev, web", substring = true).assertExists()
        rule.onNodeWithText("Skipped or changed: bad").assertExists()
        rule.onNodeWithText("Import").assertIsNotEnabled()
        clickInDialog("Cancel")
    }
}
