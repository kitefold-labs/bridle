package com.kitefoldlabs.bridle.feature.sessions

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.RestartMode
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.tmux.SessionSummary
import com.kitefoldlabs.bridle.core.tmux.TmuxPane
import com.kitefoldlabs.bridle.core.tmux.TmuxSession
import com.kitefoldlabs.bridle.feature.FakeEngine
import com.kitefoldlabs.bridle.feature.FakeSession
import com.kitefoldlabs.bridle.feature.TestWorld
import com.kitefoldlabs.bridle.feature.awaitUntil
import com.kitefoldlabs.bridle.feature.terminal.TerminalLauncher
import com.kitefoldlabs.bridle.feature.terminal.TerminalNavigator
import com.kitefoldlabs.bridle.feature.terminal.hold
import com.kitefoldlabs.bridle.service.ClaudeNotifier
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val world = TestWorld(context, scope)
    private val host = world.host
    private val navigator = TerminalNavigator()
    private val launcher = TerminalLauncher(world.coordinator, world.data.hosts, { FakeEngine() }, {}, navigator)
    private val hub =
        ClaudeStatusHub(
            world.coordinator,
            world.data.hosts,
            world.data.launchProfiles,
            world.data.settings,
            ClaudeNotifier(context),
            navigator,
            AppVisibility(MutableStateFlow(true)),
            scope,
        )
    private val store = ViewModelStore()
    private lateinit var vm: SessionsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        vm =
            store.hold(
                SessionsViewModel(
                    world.data.hosts,
                    world.data.launchProfiles,
                    world.data.settings,
                    hub,
                    world.coordinator,
                    launcher,
                    navigator,
                    SavedStateHandle(),
                ),
            )
        scope.launch { vm.uiState.collect {} }
    }

    @After
    fun tearDown() {
        store.clear()
        runBlocking { world.coordinator.disconnectAll() }
        Dispatchers.resetMain()
        main.close()
        world.data.close()
        scope.cancel()
    }

    private val state get() = vm.uiState.value

    private fun lastScript(text: String): String = host.scriptsContaining(text).last()

    private fun awaitMessage(): UserMessage {
        awaitUntil { state.message != null && !state.busy }
        val message = state.message!!
        vm.messageShown()
        awaitUntil { state.message == null }
        return message
    }

    private suspend fun selectedHost(): Long {
        val id = world.addHost()
        awaitUntil { state.selectedHostId == id && state.status.loaded }
        return id
    }

    private fun summary(
        name: String,
        path: String?,
    ) = SessionSummary(
        TmuxSession(name, 0, 0, 0, 1),
        path?.let { TmuxPane(name, "%1", true, true, false, "claude", it) },
        claudeRunning = true,
        claude = null,
    )

    @Test
    fun `without hosts nothing happens`() {
        state.hosts.shouldBeEmpty()
        vm.refresh()
        vm.reconnect()
        vm.newProfile()
        vm.attach("api")
        vm.restartSession(summary("api", null), fresh = false)
        vm.confirmDialog()
        state.editor shouldBe null
        state.busy shouldBe false
        host.scripts.shouldBeEmpty()
    }

    @Test
    fun `picks a host and shows its status, profiles and tabs`() =
        runBlocking<Unit> {
            host.sessions = listOf(FakeSession("api", command = "claude"))
            val first = world.addHost("alpha")
            val second = world.addHost("beta")
            awaitUntil { state.hosts.size == 2 && state.status.loaded }
            state.selectedHostId shouldBe first
            vm.selectHost(second)
            awaitUntil { state.selectedHostId == second }
            world.data.launchProfiles.upsert(LaunchProfile.claude(second, "~/api", "api"))
            awaitUntil { state.profiles.size == 1 && state.status.sessions.size == 1 }
            state.settings.useHelperByDefault shouldBe true

            vm.attach("api")
            awaitUntil { state.openTabs == 1 }
            world.coordinator.tabs.value
                .single()
                .session.attach shouldBe AttachSpec.Tmux("api")
            vm.showTerminals()
            withTimeout(1_000) { navigator.showRequests.first() }
            vm.refresh()
            vm.reconnect()
        }

    @Test
    fun `session actions run over tmux`() =
        runBlocking<Unit> {
            selectedHost()
            vm.quickReply("api", QuickReply.YES)
            awaitUntil { host.scriptsContaining("-- y").isNotEmpty() }
            vm.quickReply("api", QuickReply.COMPACT)
            awaitUntil { host.scriptsContaining("compact").isNotEmpty() }
            lastScript("compact") shouldContain "-l --"

            vm.restartSession(summary("api", "/srv/api"), fresh = false)
            awaitMessage() shouldBe UserMessage(R.string.sessions_restarted, "api")
            lastScript("echo restarted") shouldContain "claude --continue"
            lastScript("echo restarted") shouldContain "/srv/api"
            vm.restartSession(summary("api", ""), fresh = true)
            awaitMessage()
            lastScript("echo restarted") shouldNotContain "--continue"
            vm.restartSession(summary("api", null), fresh = false)
            awaitMessage()

            vm.requestStop("api")
            awaitUntil { state.dialog == SessionsDialog.ConfirmStop("api") }
            vm.confirmDialog()
            awaitMessage() shouldBe UserMessage(R.string.sessions_stopped, "api")
            state.dialog shouldBe null
            lastScript("kill-session") shouldContain "=api"
        }

    @Test
    fun `profiles are created, edited, started, restarted and deleted`() =
        runBlocking<Unit> {
            val id = selectedHost()
            vm.newProfile()
            awaitUntil { state.editor != null }
            val preset = state.editor!!
            preset.sessionName shouldBe "claude"
            vm.updateEditor(preset.copy(name = ""))
            vm.saveEditor()
            awaitUntil { state.editor?.name == "" }
            vm.updateEditor(preset.copy(name = "API", sessionName = "api", workingDir = "~/api"))
            vm.saveEditor()
            awaitMessage() shouldBe UserMessage(R.string.sessions_profile_saved, "API")
            state.editor shouldBe null
            awaitUntil { state.profiles.size == 1 }
            val profile = state.profiles.single()
            vm.newProfile()
            awaitUntil { state.editor?.sessionName == "claude" }
            vm.cancelEditor()
            vm.editProfile(profile)
            awaitUntil { state.editor?.id == profile.id }
            vm.cancelEditor()
            awaitUntil { state.editor == null }

            vm.startProfile(profile)
            awaitUntil { state.openTabs == 1 && !state.busy }
            lastScript("echo exists") shouldContain "new-session"
            world.coordinator.tabs.value
                .single()
                .session.attach shouldBe AttachSpec.Tmux("api", "~/api")

            vm.restartSession(summary("api", "/elsewhere"), fresh = false) // uses the profile
            awaitMessage()
            lastScript("echo restarted") shouldNotContain "/elsewhere"
            vm.restartProfile(profile, fresh = true)
            awaitMessage()
            lastScript("echo restarted") shouldNotContain "--continue"
            vm.restartProfile(profile.copy(restartMode = RestartMode.SEND_KEYS), fresh = true)
            awaitMessage()
            lastScript("echo restarted") shouldContain "C-c"

            vm.requestDeleteProfile(profile)
            vm.confirmDialog()
            awaitMessage() shouldBe UserMessage(R.string.sessions_profile_deleted, "API")
            awaitUntil { state.profiles.isEmpty() }
            world.data.launchProfiles.get(profile.id) shouldBe null
            id shouldBe profile.hostId
        }

    @Test
    fun `the helper is installed with consent and removed`() =
        runBlocking<Unit> {
            selectedHost()
            vm.setInstallHooks(false) // no dialog: ignored
            state.dialog shouldBe null
            vm.requestInstallHelper()
            awaitUntil { state.dialog == SessionsDialog.InstallHelper(installHooks = true) }
            vm.setInstallHooks(false)
            awaitUntil { state.dialog == SessionsDialog.InstallHelper(installHooks = false) }
            vm.confirmDialog()
            awaitMessage() shouldBe UserMessage(R.string.sessions_helper_installed)
            lastScript("BRIDLE_HELPER_INSTALLED") shouldNotContain "|| exit 3"

            vm.requestInstallHelper()
            vm.confirmDialog()
            awaitMessage()
            lastScript("BRIDLE_HELPER_INSTALLED") shouldContain "|| exit 3"

            vm.requestUninstallHelper()
            awaitUntil { state.dialog == SessionsDialog.ConfirmUninstallHelper }
            vm.confirmDialog()
            awaitMessage() shouldBe UserMessage(R.string.sessions_helper_removed)

            vm.requestStop("api")
            awaitUntil { state.dialog != null }
            vm.dismissDialog()
            awaitUntil { state.dialog == null }
        }

    @Test
    fun `failures become messages`() =
        runBlocking<Unit> {
            selectedHost()
            host.noTmux = true
            vm.quickReply("api", QuickReply.ESCAPE)
            awaitMessage() shouldBe UserMessage(R.string.sessions_error_no_tmux)
            host.noTmux = false
            host.failing = true
            vm.quickReply("api", QuickReply.CTRL_C)
            val failed = awaitMessage()
            failed.text shouldBe R.string.sessions_error
            failed.arg!! shouldContain "tmux command failed"
            host.failing = false
            vm.attach("bad name")
            awaitMessage().text shouldBe R.string.sessions_error
        }
}
