package com.kitefoldlabs.bridle.feature.hosts

import android.app.Application
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import com.kitefoldlabs.bridle.core.model.Host
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An activity-result registry that answers every launch with [result] at once. */
internal class ImmediateRegistry(
    private val result: Any?,
) : ActivityResultRegistry() {
    var launches = 0

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        launches++
        dispatchResult(requestCode, result)
    }
}

internal fun ownerOf(registry: ActivityResultRegistry) =
    object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

private class RecordingImportActions : SshImportActions {
    val calls = mutableListOf<String>()

    override fun setText(text: String) {
        calls += "text:$text"
    }

    override fun setDefaultUsername(username: String) {
        calls += "user:$username"
    }

    override fun loadFile(uri: Uri) {
        calls += "file:$uri"
    }

    override fun parse() {
        calls += "parse"
    }

    override fun toggle(key: String) {
        calls += "toggle:$key"
    }

    override fun import() {
        calls += "import"
    }

    override fun edit() {
        calls += "edit"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class SshConfigImportScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = RecordingImportActions()
    private var done = 0

    private fun show(
        initial: SshImportUiState,
        pickResult: Uri? = Uri.parse("content://docs/config"),
    ): (SshImportUiState) -> Unit {
        var state by mutableStateOf(initial)
        val registry = ImmediateRegistry(pickResult)
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides ownerOf(registry)) {
                SshConfigImportScreen(state, actions) { done++ }
            }
        }
        return { state = it }
    }

    @Test
    fun `input phase pastes, picks a file and previews`() {
        val update =
            show(
                SshImportUiState(
                    error = SshImportError.NOTHING_FOUND,
                    warnings = listOf("Line 1: Include is not supported"),
                ),
            )
        rule.onNodeWithText("No importable Host entries were found.").assertExists()
        rule.onNodeWithText("Line 1: Include is not supported").assertExists()
        rule.onNodeWithText("Preview").performClick() // disabled while empty
        rule.onNodeWithText("ssh config").performTextInput("Host a")
        rule.onNodeWithText("Default username").performTextInput("nate")
        rule.onNodeWithText("Choose file").performClick()
        update(SshImportUiState(text = "Host a", error = SshImportError.TOO_LARGE, busy = false))
        rule.onNodeWithText("That file is too large to be an ssh config.").assertExists()
        rule.onNodeWithText("Preview").performClick()
        update(SshImportUiState(text = "Host a", error = SshImportError.READ_FAILED, busy = true))
        rule.onNodeWithText("Couldn't read that file.").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        // Fields whose value isn't fed back may report a reset to the old (empty) text.
        actions.calls.filterNot { it == "text:" || it == "user:" } shouldBe
            listOf("text:Host a", "user:nate", "file:content://docs/config", "parse")
        done shouldBe 1
    }

    @Test
    fun `a cancelled file pick does nothing`() {
        show(SshImportUiState(), pickResult = null)
        rule.onNodeWithText("Choose file").performClick()
        actions.calls shouldBe emptyList()
    }

    @Test
    fun `review phase toggles entries, shows notes and imports`() {
        val host = Host(label = "dev", hostname = "dev.example", username = "nate")
        val plan =
            ImportPlan(
                ImportNote.entries.mapIndexed { i, note ->
                    PlannedHost("h$i", host.copy(label = "h$i"), null, fromConfig = true, notes = listOf(note))
                } + PlannedHost("hop", host, null, fromConfig = false, notes = emptyList()),
            )
        val update = show(SshImportUiState(plan = plan, selected = setOf("h3"), warnings = listOf("w1")))
        listOf(
            "A host with this name already exists.",
            "No User set: enter a default username.",
            "Its name, address or port isn't valid.",
            "IdentityFile is ignored",
            "A ProxyJump hop couldn't be resolved",
            "Only part of the ProxyJump chain",
            "The ProxyJump settings form a loop",
        ).forEach { rule.onNodeWithText(it, substring = true).assertExists() }
        rule.onNodeWithText("hop").assertDoesNotExist()
        rule.onNodeWithText("w1").assertExists()
        rule.onNodeWithText("h0").performClick() // exists: not selectable
        rule.onNodeWithText("h3").performClick()
        rule.onNodeWithText("Import 1 host").performClick()
        rule.onNodeWithText("Edit text").performClick()
        actions.calls shouldBe listOf("toggle:h3", "import", "edit")

        update(SshImportUiState(plan = plan, selected = setOf("h3", "h4")))
        rule.onNodeWithText("Import 2 hosts").assertExists()
        update(SshImportUiState(imported = 3))
        rule.onNodeWithText("Imported 3 hosts.").assertExists()
        rule.onNodeWithText("Done").performClick()
        done shouldBe 1
    }
}
