package com.kitefoldlabs.bridle.feature.terminal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.TerminalSink
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.terminal.BracketedPaste
import com.kitefoldlabs.bridle.feature.FakeEngine
import com.kitefoldlabs.bridle.feature.FakeShell
import com.kitefoldlabs.bridle.feature.TestWorld
import com.kitefoldlabs.bridle.feature.awaitUntil
import com.kitefoldlabs.bridle.service.TerminalTab
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TerminalSnippetTargetTest {
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
    private val target = TerminalSnippetTarget(world.coordinator, navigator)

    @After
    fun tearDown() {
        runBlocking { world.coordinator.disconnectAll() }
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        world.data.close()
    }

    /** Opens a tab and waits until it is open; returns it with its remote shell. */
    private suspend fun open(
        hostId: Long,
        attach: AttachSpec = AttachSpec.Tmux("t${world.host.shells.size}"),
    ): Pair<TerminalTab, FakeShell> {
        val shells = world.host.shells.size
        val tab = launcher.openTerminal(hostId, attach)
        awaitUntil { world.host.shells.size > shells && tab.session.state.value == TerminalState.Open }
        return tab to world.host.shells.last()
    }

    private fun bracketed(text: String) = BracketedPaste.START + text + BracketedPaste.END

    private suspend fun awaitShown() = withTimeout(1_000) { navigator.showRequests.first() }

    @Test
    fun `pastes into the host's tab on screen, else the selected one, else its newest, and shows it`() =
        runBlocking<Unit> {
            val dev = world.addHost("dev")
            val (first, firstShell) = open(dev)
            val (second, secondShell) = open(dev)
            val (other, otherShell) = open(world.addHost("other"))
            awaitShown()
            navigator.selectedTabId.value shouldBe other.id

            // Neither the selected nor a visible tab is on the host: its newest tab.
            target.paste(dev, "ls -la") shouldBe true
            awaitUntil { secondShell.typed() == bracketed("ls -la") }
            navigator.selectedTabId.value shouldBe second.id
            awaitShown()

            navigator.setVisibleTab(first.id)
            target.paste(dev, "pwd") shouldBe true
            awaitUntil { firstShell.typed() == bracketed("pwd") }
            navigator.selectedTabId.value shouldBe first.id

            // The visible tab is on another host; the selected one (first) is on this one.
            navigator.setVisibleTab(other.id)
            // Sanitised like any paste: no early end of the paste, newlines as CR, never Enter.
            target.paste(dev, "echo a${BracketedPaste.END}\u001b[2J\nb\n") shouldBe true
            awaitUntil { firstShell.typed() == bracketed("pwd") + bracketed("echo a[2J\rb\r") }
            secondShell.typed() shouldBe bracketed("ls -la")
            otherShell.typed() shouldBe ""
            navigator.pendingPaste.value shouldBe null
        }

    @Test
    fun `without bracketed paste a single line is typed, and several lines wait for the screen to confirm`() =
        runBlocking<Unit> {
            val dev = world.addHost()
            val (tab, shell) = open(dev)
            engines.single().isBracketedPasteEnabled = false

            target.paste(dev, "uptime") shouldBe true
            awaitUntil { shell.typed() == "uptime" }

            target.paste(dev, "cd /srv\nmake") shouldBe true
            navigator.pendingPaste.value shouldBe PendingPaste(tab.id, "cd /srv\nmake")
            navigator.selectedTabId.value shouldBe tab.id
            awaitShown()
            shell.typed() shouldBe "uptime" // nothing runs before the user confirms

            // Taken by the screen; a newer request for the same tab is not cleared by the old one.
            val first = navigator.pendingPaste.value!!
            navigator.requestPaste(tab.id, "other")
            navigator.pasteTaken(first)
            navigator.pendingPaste.value shouldBe PendingPaste(tab.id, "other")
            navigator.pasteTaken(navigator.pendingPaste.value!!)
            navigator.pendingPaste.value shouldBe null
        }

    @Test
    fun `nothing is pasted without an open tab on the host`() =
        runBlocking<Unit> {
            val dev = world.addHost()
            target.paste(dev, "ls") shouldBe false

            // On another host.
            open(world.addHost("other"))
            target.paste(dev, "ls") shouldBe false

            // Ended.
            val (shellTab, shell) = open(dev, AttachSpec.Shell)
            shell.hangUp()
            awaitUntil { shellTab.session.state.value is TerminalState.Ended }
            target.paste(dev, "ls") shouldBe false

            // Without an emulator (not opened by the launcher), so the paste mode is unknown.
            val bare = world.coordinator.openTab(dev, "bare", AttachSpec.Tmux("bare"), TerminalSink { _, _ -> })
            awaitUntil { bare.session.state.value == TerminalState.Open }
            target.paste(dev, "ls") shouldBe false
            world.host.shells
                .last()
                .typed() shouldBe ""
        }
}
