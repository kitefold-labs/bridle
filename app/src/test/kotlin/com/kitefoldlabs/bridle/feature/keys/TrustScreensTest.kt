package com.kitefoldlabs.bridle.feature.keys

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.kitefoldlabs.bridle.connect.Interaction
import com.kitefoldlabs.bridle.core.model.KnownHost
import com.kitefoldlabs.bridle.core.ssh.HostKeyChangedException
import com.kitefoldlabs.bridle.core.ssh.HostKeyPrompt
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class RecordingInteraction : InteractionActions {
    val calls = mutableListOf<String>()

    override fun trust(
        question: Interaction.TrustHost,
        trusted: Boolean,
    ) {
        calls += "trust:$trusted"
    }

    override fun password(
        question: Interaction.Password,
        password: CharArray?,
    ) {
        calls += "password:${password?.let(::String)}"
    }

    override fun forgetOldKey() {
        calls += "forget"
    }

    override fun dismissKeyChanged() {
        calls += "dismiss"
    }
}

private class RecordingKnownHosts : KnownHostsActions {
    val calls = mutableListOf<String>()

    override fun requestForget(host: KnownHost) {
        calls += "request:${host.host}"
    }

    override fun confirmForget() {
        calls += "confirm"
    }

    override fun cancelForget() {
        calls += "cancel"
    }
}

/** Records the passphrases the import screen hands over. */
private class RecordingPassphrase : KeyImportActions {
    val passphrases = mutableListOf<String>()

    override fun setLabel(label: String) = Unit

    override fun setKeyText(text: String) = Unit

    override fun setAuth(auth: AuthSetting) = Unit

    override fun loadFile(uri: Uri) = Unit

    override fun submit() = Unit

    override fun submitPassphrase(passphrase: CharArray) {
        passphrases += String(passphrase)
    }

    override fun cancelPassphrase() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class TrustScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = RecordingInteraction()

    @Test
    fun `trust dialog shows the fingerprint`() {
        val prompt = HostKeyPrompt("dev.example", 2222, "ssh-ed25519", "SHA256:abcdef")
        var pending by mutableStateOf<Interaction?>(Interaction.TrustHost(prompt))
        rule.setContent { InteractionDialogs(pending, null, actions) }
        rule.onNodeWithText("This is the first connection to dev.example:2222", substring = true).assertExists()
        rule.onNodeWithText("ssh-ed25519").assertExists()
        rule.onNodeWithText("SHA256:abcdef").assertExists()
        rule.onNodeWithText("Trust and connect").performClick()
        rule.onNodeWithText("Cancel").performClick()
        actions.calls shouldBe listOf("trust:true", "trust:false")
        pending = null
        rule.onNodeWithText("Trust this host?").assertDoesNotExist()
    }

    @Test
    fun `password dialog hands over the typed characters`() {
        var pending by mutableStateOf<Interaction?>(Interaction.Password("Dev", "nate"))
        rule.setContent { InteractionDialogs(pending, null, actions) }
        rule.onNodeWithText("Password for nate on Dev").assertExists()
        rule.onNodeWithText("Password").performTextInput("hunter2")
        rule.onNodeWithText("Log in").performClick()
        rule.onNodeWithText("Password").performTextInput("again")
        rule.onNodeWithText("Password").performImeAction()
        rule.onNodeWithText("Cancel").performClick()
        actions.calls shouldBe listOf("password:hunter2", "password:again", "password:null")
        pending = Interaction.Password("Dev", "root")
        rule.onNodeWithText("Password for root on Dev").assertExists()
    }

    @Test
    fun `typed passwords and passphrases are never saved to the instance state`() {
        val restoration = StateRestorationTester(rule)
        val imports = RecordingPassphrase()
        restoration.setContent {
            InteractionDialogs(Interaction.Password("Dev", "nate"), null, actions)
            KeyImportScreen(KeyImportUiState(askPassphrase = true), imports, onBack = {}, onCreated = {})
        }
        rule.onNodeWithText("Password").performTextInput("hunter2")
        rule.onNodeWithText("Passphrase").performTextInput("s3cret")
        // Saving and restoring the state (rotation, process death) must not bring the secrets back.
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Log in").performClick()
        rule.onAllNodesWithText("Import").onLast().performClick()
        actions.calls shouldBe listOf("password:")
        imports.passphrases shouldBe listOf("")
    }

    @Test
    fun `host key changed warning keeps the connection blocked unless the old key is forgotten`() {
        val error =
            HostKeyChangedException(
                "dev.example",
                22,
                "ssh-ed25519",
                listOf("SHA256:old1", "SHA256:old2"),
                "SHA256:new",
            )
        rule.setContent { InteractionDialogs(null, error, actions) }
        rule.onNodeWithText("Host key changed!").assertExists()
        rule.onNodeWithText("dev.example presented a different key", substring = true).assertExists()
        listOf("SHA256:old1", "SHA256:old2", "SHA256:new", "Now presented (ssh-ed25519)").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        rule.onNodeWithText("Keep blocked").performClick()
        rule.onNodeWithText("Forget old key").performClick()
        rule.onNodeWithText("The old key of dev.example is removed", substring = true).assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Forget old key").performClick()
        rule.onNodeWithText("Forget").performClick()
        actions.calls shouldBe listOf("dismiss", "forget")
    }

    @Test
    fun `known hosts list and forget`() {
        val known = RecordingKnownHosts()
        val host = KnownHost("dev.example", 2222, "ssh-ed25519", "AAAA", "SHA256:abc")
        var state by mutableStateOf(KnownHostsUiState(loading = false))
        var back = 0
        rule.setContent { KnownHostsScreen(state, known) { back++ } }
        rule.onNodeWithText("No trusted host keys yet", substring = true).assertExists()
        state = KnownHostsUiState(loading = false, hosts = listOf(host))
        rule.onNodeWithText("dev.example:2222").assertExists()
        rule.onNodeWithText("SHA256:abc").assertExists()
        rule.onNodeWithContentDescription("Forget the key of dev.example:2222").performClick()
        state = state.copy(pendingForget = host)
        rule.onNodeWithText("Forget dev.example:2222?").assertExists()
        rule.onNodeWithText("Forget").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        known.calls shouldBe listOf("request:dev.example", "confirm", "cancel")
        back shouldBe 1
    }
}
