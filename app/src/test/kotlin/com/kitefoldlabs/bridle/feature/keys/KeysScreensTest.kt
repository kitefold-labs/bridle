package com.kitefoldlabs.bridle.feature.keys

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.ssh.KeyInstallResult
import com.kitefoldlabs.bridle.feature.hosts.ImmediateRegistry
import com.kitefoldlabs.bridle.feature.hosts.ownerOf
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private class RecordingGenerate : KeyGenerateActions {
    val calls = mutableListOf<String>()

    override fun setLabel(label: String) {
        calls += "label:$label"
    }

    override fun setAlgorithm(algorithm: KeyAlgorithm) {
        calls += "algorithm:$algorithm"
    }

    override fun setAuth(auth: AuthSetting) {
        calls += "auth:${auth.choice}:${auth.minutes}"
    }

    override fun generate() {
        calls += "generate"
    }
}

private class RecordingImport : KeyImportActions {
    val calls = mutableListOf<String>()

    override fun setLabel(label: String) {
        calls += "label:$label"
    }

    override fun setKeyText(text: String) {
        calls += "text:$text"
    }

    override fun setAuth(auth: AuthSetting) {
        calls += "auth:${auth.choice}"
    }

    override fun loadFile(uri: Uri) {
        calls += "file:$uri"
    }

    override fun submit() {
        calls += "submit"
    }

    override fun submitPassphrase(passphrase: CharArray) {
        calls += "passphrase:${String(passphrase)}"
    }

    override fun cancelPassphrase() {
        calls += "cancel"
    }
}

private class RecordingDetail : KeyDetailActions {
    val calls = mutableListOf<String>()

    override fun install(hostId: EntityId) {
        calls += "install:$hostId"
    }

    override fun dismissInstall() {
        calls += "dismiss"
    }

    override fun requestDelete() {
        calls += "delete"
    }

    override fun confirmDelete() {
        calls += "confirm"
    }

    override fun cancelDelete() {
        calls += "cancel"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class KeysScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val hardware =
        Identity(
            1,
            "Pixel",
            KeySource.KEYSTORE,
            KeyAlgorithm.ECDSA_P256,
            "ecdsa-sha2-nistp256 AAAA bridle@pixel",
            keystoreAlias = "a",
        )
    private val software = Identity(2, "Laptop", KeySource.IMPORTED, KeyAlgorithm.ED25519, "ssh-ed25519 BBBB")

    @Test
    fun `keys list shows badges, algorithms and fingerprints`() {
        val calls = mutableListOf<String>()
        val navigation =
            KeysNavigation(
                onOpen = { calls += "open:$it" },
                onGenerate = { calls += "generate" },
                onImport = { calls += "import" },
                onKnownHosts = { calls += "known" },
            )
        var state by mutableStateOf(KeysUiState(loading = false))
        rule.setContent { KeysScreen(state, navigation) }
        rule.onNodeWithText("No keys yet", substring = true).assertExists()
        state =
            KeysUiState(
                loading = false,
                keys =
                    listOf(
                        KeyRow(hardware, "SHA256:abc"),
                        KeyRow(software, null),
                        KeyRow(hardware.copy(id = 3, alias = "P384", algorithm = KeyAlgorithm.ECDSA_P384), "f3"),
                        KeyRow(software.copy(id = 4, alias = "P521", algorithm = KeyAlgorithm.ECDSA_P521), "f4"),
                        KeyRow(hardware.copy(id = 5, alias = "Old", algorithm = KeyAlgorithm.RSA), "f5"),
                    ),
            )
        listOf(
            "Hardware key",
            "Software key",
            "ECDSA P-256",
            "Ed25519",
            "ECDSA P-384",
            "ECDSA P-521",
            "RSA",
            "SHA256:abc",
        ).forEach {
            rule.onAllNodesWithText(it, useUnmergedTree = true).onFirst().assertExists()
        }
        rule.onNodeWithText("Fingerprint unavailable", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Laptop", substring = true).performClick()
        rule.onNodeWithContentDescription("Generate key").performClick()
        rule.onNodeWithContentDescription("Import key").performClick()
        rule.onNodeWithContentDescription("Known hosts").performClick()
        calls shouldBe listOf("open:2", "generate", "import", "known")
    }

    @Test
    fun `generate screen`() {
        val actions = RecordingGenerate()
        var created: EntityId? = null
        var back = 0
        var state by mutableStateOf(
            KeyGenerateUiState(
                algorithms = listOf(KeyAlgorithm.ECDSA_P256, KeyAlgorithm.RSA),
                labelError = Validation.Reason.BLANK,
            ),
        )
        rule.setContent { KeyGenerateScreen(state, actions, onBack = { back++ }, onCreated = { created = it }) }
        rule.onNodeWithText("Required").assertExists()
        rule.onNodeWithText("Key name").performTextInput("Pixel")
        rule.onNodeWithText("Algorithm").performClick()
        rule.onNodeWithText("RSA").performClick()
        rule.onNodeWithText("Require biometrics").performClick()
        rule.onNodeWithText("Yes, for every connection").performClick()
        rule.onNodeWithText("Require biometrics").performClick()
        rule.onNodeWithText("Yes, then stay unlocked for a while").performClick()
        state = state.copy(auth = AuthSetting(AuthChoice.TIMED, 5), failed = true, busy = true)
        rule.onNodeWithText("The key couldn't be generated", substring = true).assertExists()
        rule.onNodeWithText("Stay unlocked for").performClick()
        rule.onNodeWithText("1 minute").performClick()
        rule.onNodeWithText("Generate").assertIsNotEnabled()
        state = state.copy(busy = false)
        rule.onNodeWithText("Generate").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        actions.calls shouldBe
            listOf("label:Pixel", "algorithm:RSA", "auth:EVERY_USE:5", "auth:TIMED:5", "auth:TIMED:1", "generate")
        back shouldBe 1
        state = state.copy(createdId = 42)
        rule.waitForIdle()
        created shouldBe 42
    }

    @Test
    fun `import screen, errors and the passphrase dialog`() {
        val actions = RecordingImport()
        var created: EntityId? = null
        var state by mutableStateOf(KeyImportUiState())
        val registry = ImmediateRegistry(Uri.parse("content://docs/key"))
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides ownerOf(registry)) {
                KeyImportScreen(state, actions, onBack = {}, onCreated = { created = it })
            }
        }
        rule.onNodeWithText("Key name").performTextInput("Laptop")
        rule.onNodeWithText("Private key").performTextInput("key")
        rule.onNodeWithText("Choose file").performClick()
        rule.onNodeWithText("Require biometrics").performClick()
        rule.onNodeWithText("Yes, for every connection").performClick()
        rule.onNodeWithText("Import").performClick()
        val errors =
            mapOf(
                KeyImportError.EMPTY to "Paste a private key or choose a file.",
                KeyImportError.UNSUPPORTED_TYPE to "ssh-dss keys aren't supported",
                KeyImportError.UNSUPPORTED_FORMAT to "This key format isn't supported",
                KeyImportError.MALFORMED to "This doesn't look like a private key",
                KeyImportError.VAULT_UNAVAILABLE to "This phone's secure key storage can't protect the key.",
                KeyImportError.TOO_LARGE to "That file is too large to be a private key.",
                KeyImportError.READ_FAILED to "Couldn't read that file.",
            )
        errors.forEach { (error, text) ->
            state = KeyImportUiState(error = error, unsupportedType = "ssh-dss")
            rule.onNodeWithText(text, substring = true).assertExists()
        }
        state = KeyImportUiState(busy = true)
        rule.onNodeWithText("Import").assertIsNotEnabled()
        state = KeyImportUiState(askPassphrase = true)
        rule.onNodeWithText("Passphrase").performTextInput("s3cret")
        rule.onNodeWithText("Cancel").performClick()
        state = KeyImportUiState(askPassphrase = true, wrongPassphrase = true)
        rule.onNodeWithText("Wrong passphrase.").assertExists()
        actions.calls.filterNot { it.endsWith(":") } shouldBe
            listOf("label:Laptop", "text:key", "file:content://docs/key", "auth:EVERY_USE", "submit", "cancel")
        state = KeyImportUiState(createdId = 7)
        rule.waitForIdle()
        created shouldBe 7
    }

    @Test
    fun `passphrase is handed over as characters and the field cleared`() {
        val actions = RecordingImport()
        rule.setContent {
            KeyImportScreen(
                KeyImportUiState(askPassphrase = true),
                actions,
                onBack = {},
                onCreated = {},
            )
        }
        rule.onNodeWithText("Passphrase").performTextInput("s3cret")
        rule.onNodeWithText("Passphrase required").assertExists()
        // Two "Import" buttons (screen and dialog); the dialog's is the last one composed.
        rule.onAllNodesWithText("Import").onLast().performClick()
        actions.calls shouldBe listOf("passphrase:s3cret")
    }

    @Test
    fun `key details copy, share, install and delete`() {
        val actions = RecordingDetail()
        var back = 0
        val host = Host(9, "Dev", "dev.example", username = "nate")
        var state by mutableStateOf(
            KeyDetailUiState(loading = false, identity = hardware, fingerprint = "SHA256:abc", hosts = listOf(host)),
        )
        rule.setContent { KeyDetailScreen(state, actions, onBack = { back++ }) }
        rule.onNodeWithText("Stored in this phone's secure hardware", substring = true).assertExists()
        rule.onNodeWithText("Copy").performClick()
        val clipboard =
            ApplicationProvider.getApplicationContext<Application>().getSystemService(
                ClipboardManager::class.java,
            )
        clipboard.primaryClip!!.getItemAt(0).text shouldBe hardware.publicKeyOpenSsh
        rule.onNodeWithText("Share").performClick()
        val chooser = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        chooser.action shouldBe Intent.ACTION_CHOOSER

        rule.onNodeWithText("Install").assertIsNotEnabled()
        rule.onNodeWithText("Host").performClick()
        rule.onNodeWithText("Dev").performClick()
        rule.onNodeWithText("Install").performClick()
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        actions.calls shouldBe listOf("install:9", "delete")
        back shouldBe 1

        state = state.copy(install = InstallState.Running("Dev"))
        rule.onNodeWithText("Installing on Dev…").assertExists()
        val outcomes =
            mapOf(
                InstallOutcome.Installed(KeyInstallResult.ADDED) to "The key was added on Dev",
                InstallOutcome.Installed(KeyInstallResult.ALREADY_PRESENT) to "Dev already had this key",
                InstallOutcome.Failed(InstallFailure.AUTH_FAILED) to "Dev didn't accept the login",
                InstallOutcome.Failed(InstallFailure.UNREACHABLE) to "Couldn't reach Dev",
                InstallOutcome.Failed(InstallFailure.HOST_NOT_TRUSTED) to "You didn't trust Dev's host key",
                InstallOutcome.Failed(InstallFailure.HOST_KEY_CHANGED) to "Dev's host key has changed",
                InstallOutcome.Failed(InstallFailure.REMOTE_FAILED) to "Dev couldn't update",
                InstallOutcome.Failed(InstallFailure.HOST_MISSING) to "Dev was deleted.",
                InstallOutcome.Failed(InstallFailure.OTHER) to "Couldn't install the key on Dev.",
            )
        outcomes.forEach { (outcome, text) ->
            state = state.copy(install = InstallState.Done("Dev", outcome))
            rule.onNodeWithText(text, substring = true).assertExists()
        }
        rule.onNodeWithText("OK").performClick()

        state =
            state.copy(
                install = InstallState.Idle,
                hosts = emptyList(),
                identity = software,
                fingerprint = null,
                confirmDelete = true,
            )
        rule.onNodeWithText("Add a host first", substring = true).assertExists()
        rule.onNodeWithText("Imported, and stored encrypted", substring = true).assertExists()
        rule.onNodeWithText("The imported key is erased", substring = true).assertExists()
        rule.onNodeWithText("Delete").performClick()
        state = state.copy(identity = hardware)
        rule.onNodeWithText("The key is erased from this phone's secure hardware", substring = true).assertExists()
        rule.onNodeWithText("Cancel").performClick()
        actions.calls.takeLast(3) shouldBe listOf("dismiss", "confirm", "cancel")

        state = KeyDetailUiState(loading = false, confirmDelete = true)
        rule.onNodeWithText("Delete Pixel?").assertDoesNotExist()
        state = KeyDetailUiState(loading = false, gone = true)
        rule.waitForIdle()
        back shouldBe 2
    }
}
