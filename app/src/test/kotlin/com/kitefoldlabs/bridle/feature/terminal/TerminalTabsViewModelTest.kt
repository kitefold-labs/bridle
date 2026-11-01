package com.kitefoldlabs.bridle.feature.terminal

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.terminal.BackspaceMode
import com.kitefoldlabs.bridle.feature.FakeEngine
import com.kitefoldlabs.bridle.feature.TestWorld
import com.kitefoldlabs.bridle.feature.awaitUntil
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Builds [viewModel] through a [ViewModelStore], so clearing the store clears it like Android does. */
fun <T : ViewModel> ViewModelStore.hold(viewModel: T): T {
    val factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = viewModel as V
        }
    return ViewModelProvider(this, factory)[viewModel.javaClass]
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalTabsViewModelTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val world = TestWorld(context, scope)
    private val navigator = TerminalNavigator()
    private val engines = CopyOnWriteArrayList<FakeEngine>()
    private val launcher =
        TerminalLauncher(world.coordinator, world.data.hosts, { FakeEngine().also { engines += it } }, {}, navigator)
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
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

    private fun viewModel(): TerminalTabsViewModel =
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

    @Test
    fun `lists tabs with their state and keeps a valid selection`() =
        runBlocking<Unit> {
            val vm = viewModel()
            val id = world.addHost()
            vm.selectedTabId.value shouldBe null
            val a = launcher.openTerminal(id)
            val b = launcher.openTerminal(id, AttachSpec.Tmux("api"))
            awaitUntil { vm.tabs.value.size == 2 }
            vm.tabs.value.map { it.title } shouldBe listOf("devbox", "api · devbox")
            awaitUntil { vm.selectedTabId.value == b.id }
            vm.select(a.id)
            awaitUntil { vm.selectedTabId.value == a.id }
            awaitUntil { vm.tabs.value.all { it.state == TerminalState.Open } }

            vm.close(a.id)
            awaitUntil { vm.selectedTabId.value == b.id }
            vm.setVisibleTab(b.id)
            navigator.visibleTabId.value shouldBe b.id
            vm.close(b.id)
            awaitUntil { vm.tabs.value.isEmpty() && vm.selectedTabId.value == null }
        }

    @Test
    fun `screen states follow the settings`() =
        runBlocking<Unit> {
            world.data.settings.setTerminalFontSize(18)
            val vm = viewModel()
            val tab = launcher.openTerminal(world.addHost())
            awaitUntil { vm.screens.value[tab.id] != null }
            val state = vm.screens.value.getValue(tab.id)
            state.engine shouldBe engines.single()
            state.fontSizeSp shouldBe 18f
            state.fontBounds.max shouldBe 48f
            state.dispatcher.encoder.backspace shouldBe BackspaceMode.DEL

            withContext(main) { state.zoomBy(2f) }
            state.fontSizeSp shouldBe 36f
            world.data.settings.setTerminalFontSize(20)
            awaitUntil { state.fontSizeSp == 20f }

            world.data.settings.setBackspaceMode(BackspaceMode.CTRL_H)
            awaitUntil { vm.screens.value[tab.id] !== state }
            vm.screens.value
                .getValue(tab.id)
                .dispatcher.encoder.backspace shouldBe BackspaceMode.CTRL_H
        }

    @Test
    fun `tabs without an emulator get no screen`() =
        runBlocking<Unit> {
            val vm = viewModel()
            val id = world.addHost()
            val raw = world.coordinator.openTab(id, "raw", AttachSpec.Shell, { _, _ -> })
            val tab = launcher.openTerminal(id)
            awaitUntil { vm.screens.value[tab.id] != null }
            vm.screens.value.containsKey(raw.id) shouldBe false
            vm.tabs.value.size shouldBe 2
        }

    @Test
    fun `compose history is loaded, shared per host, saved and cleared`() =
        runBlocking<Unit> {
            val id = world.addHost()
            world.data.composeHistory.save(id, listOf("older"))
            val vm = viewModel()
            val tab = launcher.openTerminal(id)
            awaitUntil { vm.screens.value[tab.id] != null }
            val state = vm.screens.value.getValue(tab.id)
            state.history.entries shouldBe listOf("older")

            // Typing and sending happen in different frames, as on a device.
            withContext(main) { state.updateComposeText("fix the failing test") }
            Snapshot.sendApplyNotifications()
            awaitUntil { runBlocking { withContext(main) { state.composeText } } == "fix the failing test" }
            Thread.sleep(100)
            withContext(main) { state.submitCompose() }
            Snapshot.sendApplyNotifications()
            awaitUntil {
                runBlocking { world.data.composeHistory.get(id) } == listOf("fix the failing test", "older")
            }

            val second = launcher.openTerminal(id)
            awaitUntil { vm.screens.value[second.id] != null }
            vm.screens.value
                .getValue(second.id)
                .history shouldBeSameInstanceAs state.history

            withContext(main) { vm.clearHistory(id) }
            awaitUntil { runBlocking { world.data.composeHistory.get(id) }.isEmpty() }
            state.history.entries shouldBe emptyList()
            withContext(main) { vm.clearHistory(999) }
        }

    @Test
    fun `reconnect replaces the tab`() =
        runBlocking<Unit> {
            val vm = viewModel()
            val tab = launcher.openTerminal(world.addHost(), AttachSpec.Tmux("api"))
            vm.reconnect(tab.id)
            awaitUntil { world.coordinator.tab(tab.id) == null && world.coordinator.tabs.value.size == 1 }
            world.coordinator.tabs.value
                .single()
                .session.attach shouldBe AttachSpec.Tmux("api")
        }
}
