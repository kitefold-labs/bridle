package com.kitefoldlabs.bridle.feature.hosts

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.model.Validation
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class RecordingHostsActions : HostsActions {
    val calls = mutableListOf<String>()

    override fun setQuery(query: String) {
        calls += "query:$query"
    }

    override fun toggleFavorite(host: Host) {
        calls += "favorite:${host.id}"
    }

    override fun requestDelete(host: Host) {
        calls += "delete:${host.id}"
    }

    override fun confirmDelete() {
        calls += "confirm"
    }

    override fun cancelDelete() {
        calls += "cancel"
    }
}

private class RecordingEditorActions : HostEditorActions {
    val calls = mutableListOf<String>()
    var form by mutableStateOf(HostForm())

    override fun edit(
        field: HostField?,
        change: (HostForm) -> HostForm,
    ) {
        form = change(form)
        calls += "edit:$field"
    }

    override fun save() {
        calls += "save"
    }

    override fun createGroup(name: String) {
        calls += "group:$name"
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
class HostsScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val navigationCalls = mutableListOf<String>()
    private val navigation =
        HostsNavigation(
            onOpenTerminal = { navigationCalls += "open:$it" },
            onAdd = { navigationCalls += "add" },
            onEdit = { navigationCalls += "edit:$it" },
            onImport = { navigationCalls += "import" },
        )

    private fun host(
        id: EntityId,
        label: String,
        favorite: Boolean = false,
    ) = Host(id, label, "$label.example", username = "nate", favorite = favorite)

    @Test
    fun `empty list invites adding or importing`() {
        rule.setContent {
            HostsScreen(
                HostsUiState(loading = false, noHosts = true),
                RecordingHostsActions(),
                navigation,
            )
        }
        rule.onNodeWithText("No hosts yet", substring = true).assertExists()
        rule.onNodeWithContentDescription("Add host").performClick()
        rule.onNodeWithContentDescription("Import from ~/.ssh/config").performClick()
        navigationCalls shouldBe listOf("add", "import")
    }

    @Test
    fun `sections, dots and row actions`() {
        val actions = RecordingHostsActions()
        val fav = host(1, "Fav", favorite = true)
        val sections =
            listOf(
                HostSection(SectionKind.FAVORITES, listOf(HostRow(fav, ConnectionDot.CONNECTED))),
                HostSection(SectionKind.RECENT, listOf(HostRow(host(2, "R1"), ConnectionDot.CONNECTING))),
                HostSection(SectionKind.GROUP, listOf(HostRow(host(3, "InLab"), ConnectionDot.RECONNECTING)), "Lab"),
                HostSection(SectionKind.OTHER, listOf(HostRow(host(4, "Loose"), ConnectionDot.FAILED))),
                HostSection(SectionKind.RESULTS, listOf(HostRow(host(5, "Found"), ConnectionDot.IDLE))),
            )
        var state by mutableStateOf(HostsUiState(loading = false, sections = sections))
        rule.setContent { HostsScreen(state, actions, navigation) }
        listOf(
            "Favorites",
            "Recent",
            "Lab",
            "Other hosts",
            "Search results",
        ).forEach { rule.onNodeWithText(it).assertExists() }
        listOf("Connected", "Connecting", "Reconnecting", "Connection failed", "Not connected").forEach {
            rule.onNodeWithContentDescription(it).assertExists()
        }
        rule.onNodeWithText("nate@Loose.example").assertExists()

        rule.onNodeWithText("Fav").performClick()
        rule.onNodeWithText("Search hosts").performTextInput("gpu")

        rule.onNodeWithContentDescription("More actions for Fav").performClick()
        rule.onNodeWithText("Remove from favorites").performClick()
        rule.onNodeWithContentDescription("More actions for R1").performClick()
        rule.onNodeWithText("Add to favorites").performClick()
        rule.onNodeWithText("Loose").performTouchInput { longClick() }
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithContentDescription("More actions for Found").performClick()
        rule.onNodeWithText("Delete").performClick()
        navigationCalls shouldBe listOf("open:1", "edit:4")
        actions.calls shouldBe listOf("query:gpu", "favorite:1", "favorite:2", "delete:5")

        state = state.copy(pendingDelete = fav)
        rule.onNodeWithText("Delete Fav?").assertExists()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Cancel").performClick()
        actions.calls.takeLast(2) shouldBe listOf("confirm", "cancel")
    }

    @Test
    fun `groups with the same name each get a section`() {
        // Group names aren't unique; list keys made from them would crash the LazyColumn.
        val groups = listOf(Group(1, "Prod"), Group(2, "Prod"))
        val hosts = listOf(host(1, "Web").copy(groupId = 1), host(2, "Db").copy(groupId = 2))
        val sections = HostsViewModel.sections(hosts, groups, emptyMap(), query = "")
        sections.map { it.groupId } shouldBe listOf(1L, 2L)
        rule.setContent {
            HostsScreen(
                HostsUiState(loading = false, sections = sections),
                RecordingHostsActions(),
                navigation,
            )
        }
        rule.onNodeWithText("Web").assertExists()
        rule.onNodeWithText("Db").assertExists()
    }

    @Test
    fun `search without results`() {
        rule.setContent {
            HostsScreen(
                HostsUiState(
                    loading = false,
                    query = "x",
                    sections = listOf(HostSection(SectionKind.RESULTS, emptyList())),
                ),
                RecordingHostsActions(),
                navigation,
            )
        }
        rule.onNodeWithText("No hosts match your search.").assertExists()
    }

    @Test
    fun `editor fields edit the form and show inline errors`() {
        val actions = RecordingEditorActions()
        val identity = Identity(7, "Pixel key", KeySource.KEYSTORE, KeyAlgorithm.ECDSA_P256, "k", keystoreAlias = "a")
        var state by mutableStateOf(
            HostEditorUiState(
                loading = false,
                identities = listOf(identity),
                jumpCandidates = listOf(host(8, "Bastion")),
                groups = listOf(Group(9, "Lab")),
                errors =
                    mapOf(
                        HostField.LABEL to Validation.Reason.BLANK,
                        HostField.HOSTNAME to Validation.Reason.ILLEGAL_CHARACTERS,
                        HostField.PORT to Validation.Reason.OUT_OF_RANGE,
                        HostField.USERNAME to Validation.Reason.TOO_LONG,
                    ),
            ),
        )
        var done = 0
        rule.setContent { HostEditorScreen(state.copy(form = actions.form), actions, onDone = { done++ }) }
        rule.onNodeWithText("Add host").assertExists()
        listOf("Required", "Contains characters that aren't allowed here", "Out of range", "Too long").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        mapOf(
            "Name" to "Dev",
            "Host name or IP address" to "dev.ts.net",
            "Port" to "2",
            "Username" to "nate",
            "Default tmux session" to "main",
            "Startup command" to "cd src",
            "Environment variables" to "A=1",
            "Keepalive interval (seconds)" to "5",
            "Tags" to "x",
        ).forEach { (label, text) -> rule.onNodeWithText(label).performTextInput(text) }
        actions.form.label shouldBe "Dev"
        actions.form.port shouldBe "222"
        actions.form.keepAlive.contains("5") shouldBe true
        actions.form.tags shouldBe "x"

        pick("Key", "Pixel key")
        pick("Jump host (ProxyJump)", "Bastion")
        pick("Group", "Lab")
        pick("Color", "Blue")
        actions.form.identityId shouldBe 7
        actions.form.jumpHostId shouldBe 8
        actions.form.groupId shouldBe 9
        actions.form.color shouldBe 0xFF1E88E5.toInt()

        rule.onNodeWithText("Group").performClick()
        rule.onNodeWithText("Cancel").performClick()
        pick("Group", "New group…")
        rule.onNodeWithText("Create").assertIsNotEnabled()
        rule.onNodeWithText("Group name").performTextInput("Home")
        rule.onNodeWithText("Create").performClick()
        pick("Group", "New group…")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Favorite").performClick()
        actions.form.favorite shouldBe true
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        actions.calls.filter { !it.startsWith("edit") } shouldBe listOf("group:Home", "save")
        done shouldBe 1

        state = state.copy(missingReference = true, errors = emptyMap())
        rule.onNodeWithText("The key, jump host or group you picked was deleted", substring = true).assertExists()
        state = state.copy(finished = true)
        rule.waitForIdle()
        done shouldBe 2
    }

    @Test
    fun `editing an existing host can delete it`() {
        val actions = RecordingEditorActions()
        var state by mutableStateOf(HostEditorUiState(loading = true, isNew = false))
        rule.setContent { HostEditorScreen(state, actions, onDone = {}) }
        rule.onNodeWithText("Edit host").assertExists()
        state = state.copy(loading = false, form = HostForm(label = "Dev"))
        rule.onNodeWithContentDescription("Delete").performClick()
        state = state.copy(confirmDelete = true)
        rule.onNodeWithText("Delete Dev?").assertExists()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Cancel").performClick()
        actions.calls shouldBe listOf("delete", "confirm", "cancel")
        state = state.copy(confirmDelete = false, notFound = true)
        rule.onNodeWithText("This host no longer exists.").assertExists()
    }

    private fun pick(
        field: String,
        choice: String,
    ) {
        rule.onNodeWithText(field).performClick()
        rule.onNodeWithText(choice).performClick()
    }
}
