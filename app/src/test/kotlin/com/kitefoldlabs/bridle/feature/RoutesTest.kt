package com.kitefoldlabs.bridle.feature

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.feature.sessions.AppVisibility
import com.kitefoldlabs.bridle.feature.sessions.ClaudeStatusHub
import com.kitefoldlabs.bridle.feature.sessions.SessionsRoute
import com.kitefoldlabs.bridle.feature.sessions.SessionsViewModel
import com.kitefoldlabs.bridle.feature.terminal.TerminalLauncher
import com.kitefoldlabs.bridle.feature.terminal.TerminalNavigator
import com.kitefoldlabs.bridle.feature.terminal.TerminalTabsRoute
import com.kitefoldlabs.bridle.feature.terminal.TerminalTabsViewModel
import com.kitefoldlabs.bridle.feature.terminal.hold
import com.kitefoldlabs.bridle.service.ClaudeNotifier
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The routes with real ViewModels (on the main looper, as in the app) and a fake emulator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoutesTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val world = TestWorld(context, scope)
    private val navigator = TerminalNavigator()
    private val launcher = TerminalLauncher(world.coordinator, world.data.hosts, { FakeEngine() }, {}, navigator)
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        runBlocking { world.coordinator.disconnectAll() }
        world.data.close()
        scope.cancel()
    }

    private fun terminalViewModel() =
        store.hold(
            TerminalTabsViewModel(
                world.coordinator,
                launcher,
                navigator,
                world.data.settings,
                world.data.composeHistory,
                scope,
            ),
        )

    private fun awaitTag(tag: String) =
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the terminal route shows the open tab and reports it as visible`() {
        runBlocking {
            world.data.settings.setKeepScreenOn(true)
            world.data.settings.setSecureScreenEnabled(true)
            launcher.openTerminal(world.addHost())
        }
        val vm = terminalViewModel()
        var backs = 0
        rule.setContent { MaterialTheme { TerminalTabsRoute(onBack = { backs++ }, viewModel = vm) } }
        awaitTag(FakeEngine.TAG)
        navigator.visibleTabId.value shouldNotBe null
        rule.onNodeWithContentDescription("Back").performClick()
        backs shouldBe 1
    }

    @Test
    fun `the terminal route with default settings`() {
        runBlocking { launcher.openTerminal(world.addHost()) }
        val vm = terminalViewModel()
        rule.setContent { MaterialTheme { TerminalTabsRoute(onBack = {}, viewModel = vm) } }
        awaitTag(FakeEngine.TAG)
    }

    @Test
    fun `the sessions route lists the hosts`() {
        runBlocking { world.addHost("devbox") }
        val hub =
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
        val vm =
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
        rule.setContent { MaterialTheme { SessionsRoute(vm) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("devbox").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("tmux 3.4").fetchSemanticsNodes().isNotEmpty() }
    }
}
