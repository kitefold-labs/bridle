package com.kitefoldlabs.bridle.feature.sessions

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.data.settings.Settings
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.LaunchMode
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.RestartMode
import com.kitefoldlabs.bridle.core.session.HostConnectionState
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.core.tmux.ClaudeState
import com.kitefoldlabs.bridle.core.tmux.ClaudeStatus
import com.kitefoldlabs.bridle.core.tmux.HostHelper
import com.kitefoldlabs.bridle.core.tmux.HostProbe
import com.kitefoldlabs.bridle.core.tmux.SessionSummary
import com.kitefoldlabs.bridle.core.tmux.StatusSource
import com.kitefoldlabs.bridle.core.tmux.TmuxPane
import com.kitefoldlabs.bridle.core.tmux.TmuxSession
import com.kitefoldlabs.bridle.core.tmux.TmuxVersion
import com.kitefoldlabs.bridle.feature.recomposeThrough
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records each action as a short string. */
class RecordingSessionsActions : SessionsActions {
    val calls = mutableListOf<String>()
    val forms = mutableListOf<ProfileForm>()

    override fun selectHost(hostId: EntityId) {
        calls += "select $hostId"
    }

    override fun refresh() {
        calls += "refresh"
    }

    override fun reconnect() {
        calls += "reconnect"
    }

    override fun showTerminals() {
        calls += "show"
    }

    override fun attach(session: String) {
        calls += "attach $session"
    }

    override fun restartSession(
        summary: SessionSummary,
        fresh: Boolean,
    ) {
        calls += "restart ${summary.session.name} fresh=$fresh"
    }

    override fun requestStop(session: String) {
        calls += "stop $session"
    }

    override fun quickReply(
        session: String,
        reply: QuickReply,
    ) {
        calls += "reply $session $reply"
    }

    override fun startProfile(profile: LaunchProfile) {
        calls += "start ${profile.name}"
    }

    override fun restartProfile(
        profile: LaunchProfile,
        fresh: Boolean,
    ) {
        calls += "restart profile ${profile.name} fresh=$fresh"
    }

    override fun newProfile() {
        calls += "new"
    }

    override fun editProfile(profile: LaunchProfile) {
        calls += "edit ${profile.name}"
    }

    override fun updateEditor(form: ProfileForm) {
        forms += form
    }

    override fun saveEditor() {
        calls += "save"
    }

    override fun cancelEditor() {
        calls += "cancel"
    }

    override fun requestDeleteProfile(profile: LaunchProfile) {
        calls += "delete ${profile.name}"
    }

    override fun requestInstallHelper() {
        calls += "install"
    }

    override fun setInstallHooks(enabled: Boolean) {
        calls += "hooks $enabled"
    }

    override fun requestUninstallHelper() {
        calls += "uninstall"
    }

    override fun confirmDialog() {
        calls += "confirm"
    }

    override fun dismissDialog() {
        calls += "dismiss"
    }

    override fun messageShown() {
        calls += "shown"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w420dp-h4000dp")
class SessionsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = RecordingSessionsActions()
    private val now = 1_700_000_600_000L

    private val hosts =
        listOf(
            Host(id = 1, label = "devbox", hostname = "d", username = "me"),
            Host(id = 2, label = "gpu", hostname = "g", username = "me"),
        )
    private val running = LaunchProfile(id = 10, hostId = 1, name = "API", sessionName = "api", workingDir = "~/api")
    private val idle = LaunchProfile(id = 11, hostId = 1, name = "Web", sessionName = "web", workingDir = "~/web")

    private fun summary(
        name: String,
        command: String,
        claude: ClaudeStatus? = null,
        attached: Int = 0,
        windows: Int = 1,
    ) = SessionSummary(
        TmuxSession(name, 0, attached, 1_700_000_000, windows),
        TmuxPane(name, "%1", true, true, false, command, "/srv/$name"),
        claudeRunning = command == "claude",
        claude = claude,
    )

    private val connected = HostConnectionState.Connected(mockk<SshConnection>(relaxed = true))
    private val probe = HostProbe(TmuxVersion(3, 4), "/usr/local/bin/claude", helperVersion = null)

    private val full =
        SessionsUiState(
            hosts = hosts,
            selectedHostId = 1,
            status =
                HostStatus(
                    connection = connected,
                    probe = probe,
                    sessions =
                        listOf(
                            summary(
                                "api",
                                "claude",
                                ClaudeStatus(ClaudeState.WAITING_PERMISSION, StatusSource.HOOKS, "Allow Bash?"),
                            ),
                            summary("logs", "bash", attached = 1, windows = 2),
                            summary("bot", "node", ClaudeStatus(ClaudeState.WORKING, StatusSource.SCREEN)),
                        ),
                    previews = mapOf("api" to "Do you want to proceed?", "logs" to ""),
                    loaded = true,
                ),
            profiles = listOf(running, idle),
            openTabs = 2,
        )

    private fun show(state: SessionsUiState) {
        rule.setContent { MaterialTheme { SessionsScreen(state, actions, now = now) } }
    }

    @Test
    fun `recomposes through every kind of state`() {
        val form = ProfileForm.claudePreset(1, emptySet())
        val states =
            listOf(
                full,
                full.copy(
                    status =
                        full.status.copy(
                            sessions =
                                ClaudeState.entries.map { state ->
                                    summary(
                                        "s-${state.name.lowercase()}",
                                        "claude",
                                        ClaudeStatus(state, StatusSource.SCREEN),
                                    )
                                } + summary("api", "bash"),
                            previews = mapOf("api" to "\$ ls"),
                        ),
                    profiles = listOf(running),
                    openTabs = 1,
                    busy = true,
                ),
                full.copy(status = HostStatus(connected, HostProbe(null, null, 1), loaded = true)),
                full.copy(dialog = SessionsDialog.ConfirmStop("api")),
                full.copy(dialog = SessionsDialog.ConfirmDeleteProfile(running)),
                full.copy(dialog = SessionsDialog.InstallHelper(installHooks = true)),
                full.copy(dialog = SessionsDialog.InstallHelper(installHooks = false)),
                full.copy(dialog = SessionsDialog.ConfirmUninstallHelper),
                full.copy(editor = form),
                full.copy(editor = form.copy(name = "", env = listOf(EnvVar("A"), EnvVar("")))),
                full.copy(editor = form.copy(id = 3, env = listOf(EnvVar("B", "2")))),
                full.copy(message = UserMessage(R.string.sessions_helper_installed)),
                SessionsUiState(),
            )
        rule.recomposeThrough(states, listOf(actions, RecordingSessionsActions())) { state, current, tick ->
            MaterialTheme { SessionsScreen(state, current, Modifier.testTag("tick-$tick"), now = now + tick) }
        }
        rule.onNodeWithText(string(R.string.sessions_no_hosts)).assertExists()
    }

    @Test
    fun `every claude state has a label and colours`() {
        show(
            full.copy(
                status =
                    full.status.copy(
                        sessions =
                            ClaudeState.entries.map { state ->
                                summary("s-${state.name.lowercase()}", "bash", ClaudeStatus(state, StatusSource.HOOKS))
                            } + summary("api", "bash"),
                    ),
            ),
        )
        for (label in listOf("Working", "Needs permission", "Waiting for input", "Idle", "Exited", "Unknown")) {
            rule.onNodeWithText("$label · hooks", useUnmergedTree = true).assertExists()
        }
        // The shell session "api" has a profile, so it can be restarted.
        rule.onAllNodesWithText("Restart").fetchSemanticsNodes().size shouldBe 2 + ClaudeState.entries.size + 1
    }

    @Test
    fun `without hosts it points to the hosts tab`() {
        show(SessionsUiState())
        rule.onNodeWithText(string(R.string.sessions_no_hosts)).assertExists()
    }

    @Test
    fun `sessions show claude status, previews and actions`() {
        show(full)
        rule.onNodeWithText("Needs permission · hooks", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Claude status: Needs permission, reported by hooks").assertExists()
        rule.onNodeWithContentDescription("Claude status: Working, guessed from the screen").assertExists()
        rule.onNodeWithText("Allow Bash?").assertExists()
        rule.onNodeWithText("Do you want to proceed?").assertExists()
        rule.onAllNodesWithContentDescription("Claude Code is running").fetchSemanticsNodes().size shouldBe 2
        rule.onNodeWithText("2 windows", substring = true).assertExists()
        rule.onNodeWithText("attached", substring = true).assertExists()
        rule.onNodeWithText("Running").assertExists()
        rule.onNodeWithText("Not running").assertExists()

        rule.onNodeWithText("gpu").performClick()
        rule.onNodeWithText("Show").performClick()
        rule.onNodeWithText("Install bridle-helper").performClick()
        rule.onNodeWithContentDescription("New profile").performClick()
        rule.onNodeWithContentDescription("Refresh").performClick()
        rule.onNodeWithText("Start").performClick()
        rule.onAllNodesWithText("Attach")[0].performClick() // the running profile
        rule.onAllNodesWithText("Attach")[1].performClick() // the api session
        rule.onNodeWithContentDescription("Edit API").performClick()
        rule.onNodeWithContentDescription("Delete Web").performClick()
        rule.onAllNodesWithText("Stop")[1].performClick()
        rule.onAllNodesWithContentDescription("Send y (yes)")[0].performClick()
        rule.onAllNodesWithContentDescription("Send /compact")[1].performClick()

        // Restart menus: two profiles, then the claude sessions (the shell session has none).
        rule.onAllNodesWithText("Restart").fetchSemanticsNodes().size shouldBe 4
        rule.onAllNodesWithText("Restart")[0].performClick()
        rule.onNodeWithText("Restart fresh").performClick()
        rule.onAllNodesWithText("Restart")[2].performClick()
        rule.onNodeWithText("Restart (continue)").performClick()

        actions.calls shouldBe
            listOf(
                "select 2",
                "show",
                "install",
                "new",
                "refresh",
                "start Web",
                "start API",
                "attach api",
                "edit API",
                "delete Web",
                "stop logs",
                "reply api YES",
                "reply bot COMPACT",
                "restart profile API fresh=true",
                "restart api fresh=false",
            )
    }

    @Test
    fun `connection problems and empty lists are explained`() {
        var state by mutableStateOf(full.copy(status = HostStatus(connection = HostConnectionState.Connecting)))
        rule.setContent { MaterialTheme { SessionsScreen(state, actions, now = now) } }
        rule.onNodeWithText("Connecting…").assertExists()
        rule.onNodeWithText("Loading…").assertExists()

        state = full.copy(status = HostStatus(HostConnectionState.Reconnecting(2, kotlin.time.Duration.ZERO, null)))
        rule.onNodeWithText("Connection lost. Reconnecting (attempt 2)…").assertExists()

        state = full.copy(status = HostStatus(HostConnectionState.Failed(IllegalStateException("refused"), true)))
        rule.onNodeWithText("Couldn't connect: refused").assertExists()
        rule.onNodeWithText("Retry").performClick()
        state = full.copy(status = HostStatus(HostConnectionState.Failed(IllegalStateException(), false)))
        rule.onNodeWithText("Couldn't connect: IllegalStateException").assertExists()

        state =
            full.copy(
                status = HostStatus(connected, loaded = true, error = "tmux command failed"),
                profiles = emptyList(),
                openTabs = 0,
                busy = true,
            )
        rule.onNodeWithText("Couldn't read sessions: tmux command failed").assertExists()
        rule.onNodeWithText("No tmux sessions on this host.").assertExists()
        rule.onNodeWithText(string(R.string.sessions_no_profiles)).assertExists()
        rule.onNodeWithText("Show").assertDoesNotExist()
        actions.calls shouldBe listOf("reconnect")
    }

    @Test
    fun `the host card explains missing tmux and the helper`() {
        var state by mutableStateOf(
            full.copy(status = HostStatus(connected, HostProbe(null, null, null), loaded = true)),
        )
        rule.setContent { MaterialTheme { SessionsScreen(state, actions, now = now) } }
        rule.onNodeWithText("tmux isn't installed.").assertExists()
        rule.onNodeWithText("sudo apt install tmux", substring = true).assertExists()
        rule.onNodeWithText(string(R.string.host_claude_missing)).assertExists()

        state = full.copy(status = HostStatus(connected, HostProbe(TmuxVersion(2, 9), "/bin/claude", 1), loaded = true))
        rule.onNodeWithText("tmux 2.9 is too old: Bridle needs 3.0 or newer.").assertExists()
        rule.onNodeWithText("Claude Code: /bin/claude").assertExists()
        rule.onNodeWithText("Installed: bridle-helper (protocol 1). Claude status comes from hooks.").assertExists()
        rule.onNodeWithText(string(R.string.host_helper_disabled)).assertDoesNotExist()

        state = state.copy(settings = Settings(useHelperByDefault = false))
        rule.onNodeWithText(string(R.string.host_helper_disabled)).assertExists()
        rule.onNodeWithText("Remove helper").performClick()
        state = state.copy(status = state.status.copy(probe = probe))
        rule.onNodeWithText("tmux 3.4").assertExists()
        actions.calls shouldBe listOf("uninstall")
    }

    @Test
    fun `dialogs confirm or cancel`() {
        var state by mutableStateOf(full.copy(dialog = SessionsDialog.ConfirmStop("api")))
        rule.setContent { MaterialTheme { SessionsScreen(state, actions, now = now) } }
        rule.onNodeWithText("Stop api?").assertExists()
        rule.onNodeWithText(string(R.string.stop_body)).assertExists()
        rule.onAllNodesWithText("Stop").fetchSemanticsNodes().size shouldBe 4 // three cards + the dialog
        rule.onNodeWithText("Cancel").performClick()

        state = full.copy(dialog = SessionsDialog.ConfirmDeleteProfile(idle))
        rule.onNodeWithText("Delete Web?").assertExists()
        rule.onNodeWithText("Delete").performClick()

        state = full.copy(dialog = SessionsDialog.InstallHelper(installHooks = true))
        rule.onNodeWithText("Install bridle-helper?").assertExists()
        rule.onNodeWithText(HostHelper.sha256).assertExists()
        rule.onNodeWithText(string(R.string.install_hooks)).performClick()
        rule.onNodeWithText("Install").performClick()

        state = full.copy(dialog = SessionsDialog.ConfirmUninstallHelper)
        rule.onNodeWithText("Remove bridle-helper?").assertExists()
        rule.onNodeWithText("Remove").performClick()
        actions.calls shouldBe listOf("dismiss", "confirm", "hooks false", "confirm", "confirm")
    }

    @Test
    fun `messages show in a snackbar once`() {
        show(full.copy(message = UserMessage(R.string.sessions_restarted, "api")))
        rule.onNodeWithText("Restarted api").assertExists()
        rule.waitUntil(timeoutMillis = 10_000) { "shown" in actions.calls }
        actions.calls shouldContain "shown"
    }

    @Test
    fun `the profile editor validates every field`() {
        val form = ProfileForm.claudePreset(1, emptySet())
        var state by mutableStateOf(full.copy(editor = form))
        var activity: ComponentActivity? = null
        rule.setContent {
            activity = LocalActivity.current as ComponentActivity
            MaterialTheme { SessionsScreen(state, actions, now = now) }
        }
        rule.onNodeWithText("New launch profile").assertExists()
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Cancel").performClick()
        rule.runOnIdle { activity!!.onBackPressedDispatcher.onBackPressed() }

        rule.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("API")
        rule.onNode(hasSetTextAction() and hasText("tmux session name")).performTextReplacement("api")
        rule.onNode(hasSetTextAction() and hasText("Working directory")).performTextReplacement("~/api")
        rule.onNode(hasSetTextAction() and hasText("Command")).performTextReplacement("claude --model opus")
        rule.onNode(hasSetTextAction() and hasText("Restart command")).performTextReplacement("claude -c")
        rule.onNodeWithText(string(R.string.restart_mode_fresh)).performClick()
        rule.onNodeWithText("Run the command directly").performClick()
        rule.onNodeWithText("Pin to the top").performClick()
        rule.onNodeWithText("Add variable").performClick()
        actions.forms.map { it.copy() } shouldContainAll
            listOf(
                form.copy(name = "API"),
                form.copy(sessionName = "api"),
                form.copy(workingDir = "~/api"),
                form.copy(command = "claude --model opus"),
                form.copy(restartCommand = "claude -c"),
                form.copy(restartMode = RestartMode.RESPAWN_FRESH),
                form.copy(mode = LaunchMode.DIRECT),
                form.copy(pinned = true),
                form.copy(env = listOf(EnvVar())),
            )

        actions.forms.clear()
        state =
            full.copy(
                editor =
                    form.copy(
                        id = 4,
                        name = "",
                        sessionName = "a b",
                        workingDir = "",
                        command = "",
                        env = listOf(EnvVar("A"), EnvVar("A"), EnvVar(""), EnvVar("1x")),
                    ),
            )
        rule.onNodeWithText("Edit launch profile").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onAllNodesWithText("Required").fetchSemanticsNodes().size shouldBe 4
        rule.onNodeWithText("Use letters, digits, - and _ only").assertExists()
        rule.onAllNodesWithText("Duplicate name").fetchSemanticsNodes().size shouldBe 2
        rule.onNodeWithText(string(R.string.profile_error_env_chars)).assertExists()
        rule.onAllNodesWithText("A")[0].performTextReplacement("B")
        rule.onAllNodes(hasSetTextAction() and hasText("Value"))[0].performTextReplacement("1")
        rule.onAllNodesWithContentDescription("Remove variable")[3].performClick()
        val start = listOf(EnvVar("A"), EnvVar("A"), EnvVar(""), EnvVar("1x"))
        // Text fields also report selection changes, with the text unchanged.
        actions.forms.map { it.env }.filter { it != start } shouldBe
            listOf(
                listOf(EnvVar("B"), EnvVar("A"), EnvVar(""), EnvVar("1x")),
                listOf(EnvVar("A", "1"), EnvVar("A"), EnvVar(""), EnvVar("1x")),
                listOf(EnvVar("A"), EnvVar("A"), EnvVar("")),
            )
        actions.calls shouldBe listOf("save", "cancel", "cancel")
    }
}

private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Application>().getString(id)
