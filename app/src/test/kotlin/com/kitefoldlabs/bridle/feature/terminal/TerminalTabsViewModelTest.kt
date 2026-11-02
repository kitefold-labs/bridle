package com.kitefoldlabs.bridle.feature.terminal

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.TerminalSink
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.terminal.BackspaceMode
import com.kitefoldlabs.bridle.core.terminalui.PendingSend
import com.kitefoldlabs.bridle.feature.FakeEngine
import com.kitefoldlabs.bridle.feature.TestMain
import com.kitefoldlabs.bridle.feature.TestWorld
import com.kitefoldlabs.bridle.feature.awaitUntil
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
        TerminalLauncher(
            world.coordinator,
            world.data.hosts,
            { FakeEngine().also { engines += it } },
            {},
            navigator,
            { _, _ -> },
            scope,
        )
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        TestMain.set(main)
    }

    @After
    fun tearDown() {
        store.clear()
        runBlocking { world.coordinator.disconnectAll() }
        TestMain.reset()
        main.close()
        // Fire-and-forget work (e.g. clearing history) must finish before the database closes, or
        // it fails later and the uncaught error lands in whichever test runs next.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        world.data.close()
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
    fun `with compose history off nothing is read from or written to disk`() =
        runBlocking<Unit> {
            val id = world.addHost()
            world.data.composeHistory.save(id, listOf("older"))
            world.data.settings.setRememberComposeHistory(false)
            val vm = viewModel()
            val tab = launcher.openTerminal(id)
            awaitUntil { vm.screens.value[tab.id] != null }
            val state = vm.screens.value.getValue(tab.id)
            state.history.entries shouldBe emptyList()

            withContext(main) { state.updateComposeText("hunter2") }
            Snapshot.sendApplyNotifications()
            awaitUntil { runBlocking { withContext(main) { state.composeText } } == "hunter2" }
            withContext(main) { state.submitCompose() }
            Snapshot.sendApplyNotifications()
            awaitUntil { runBlocking { withContext(main) { state.composeText } }.isEmpty() }
            Thread.sleep(200)
            state.history.entries shouldBe listOf("hunter2") // ↑ still works until Bridle closes
            world.data.composeHistory.get(id) shouldBe listOf("older")

            // Turned back on, the next line is saved, but never what was sent while it was off.
            world.data.settings.setRememberComposeHistory(true)
            awaitUntil { vm.settings.value?.rememberComposeHistory == true }
            awaitUntil { state.history.entries.isEmpty() }
            withContext(main) { state.updateComposeText("ls") }
            Snapshot.sendApplyNotifications()
            awaitUntil { runBlocking { withContext(main) { state.composeText } } == "ls" }
            withContext(main) { state.submitCompose() }
            Snapshot.sendApplyNotifications()
            awaitUntil { runBlocking { world.data.composeHistory.get(id) } == listOf("ls") }
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

    @Test
    fun `a paste that needs confirming reaches its tab's screen, which asks first`() =
        runBlocking<Unit> {
            val vm = viewModel()
            navigator.requestPaste("closed", "never shown") // no such tab: dropped
            awaitUntil { navigator.pendingPaste.value == null }
            val tab = launcher.openTerminal(world.addHost())
            awaitUntil { vm.screens.value[tab.id] != null && tab.session.state.value == TerminalState.Open }
            val screen = vm.screens.value.getValue(tab.id)
            engines.single().isBracketedPasteEnabled = false

            navigator.requestPaste(tab.id, "cd /srv\nmake")
            awaitUntil { navigator.pendingPaste.value == null }
            val asked = PendingSend("cd /srv\nmake", submit = false)
            awaitUntil { runBlocking { withContext(main) { screen.pendingSend } } == asked }
            val shell = world.host.shells.single()
            shell.typed() shouldBe ""
            withContext(main) { screen.confirmPendingSend() }
            awaitUntil { shell.typed() == "cd /srv\rmake" }
        }

    @Test
    fun `a paste whose tab closes before its screen exists is dropped, not pasted later`() =
        runBlocking<Unit> {
            val vm = viewModel()
            val dev = world.addHost()
            // Without an emulator, so it never gets a screen: the paste waits for one.
            val bare = world.coordinator.openTab(dev, "bare", AttachSpec.Tmux("bare"), TerminalSink { _, _ -> })
            navigator.requestPaste(bare.id, "cd /srv\nmake")
            val tab = launcher.openTerminal(dev)
            awaitUntil { vm.screens.value[tab.id] != null }
            navigator.pendingPaste.value shouldBe PendingPaste(bare.id, "cd /srv\nmake")

            world.coordinator.closeTab(bare.id)
            awaitUntil { navigator.pendingPaste.value == null }
            val screen = vm.screens.value.getValue(tab.id)
            withContext(main) { screen.pendingSend }.shouldBeNull()
        }
}
