package com.kitefoldlabs.bridle.feature.terminal

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.connect.UnknownHostException
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.terminalui.TerminalEngine
import com.kitefoldlabs.bridle.core.terminalui.TermlibEngine
import com.kitefoldlabs.bridle.feature.FakeEngine
import com.kitefoldlabs.bridle.feature.TestWorld
import com.kitefoldlabs.bridle.feature.await
import com.kitefoldlabs.bridle.feature.awaitUntil
import com.kitefoldlabs.bridle.feature.forwards.ForwardManager
import com.kitefoldlabs.bridle.feature.forwards.ForwardStatus
import com.kitefoldlabs.bridle.service.SessionLink
import com.kitefoldlabs.bridle.service.SessionLinks
import com.kitefoldlabs.bridle.service.SessionService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TerminalLauncherTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val world = TestWorld(context, scope)
    private val engines = CopyOnWriteArrayList<FakeEngine>()
    private var serviceStarts = 0
    private val navigator = TerminalNavigator()
    private var autoStart = HostAutoStart {}
    private val launcher =
        TerminalLauncher(
            world.coordinator,
            world.data.hosts,
            { FakeEngine().also { engines += it } },
            { serviceStarts++ },
            navigator,
            { autoStart.start(it) },
            scope,
        )

    @After
    fun tearDown() {
        runBlocking { world.coordinator.disconnectAll() }
        // Background work (auto-start forwards) must end before the database closes.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        world.data.close()
    }

    @Test
    fun `opens a shell or the host's default tmux session and shows it`() =
        runBlocking<Unit> {
            val plain = world.addHost("plain")
            val dev = world.addHost("dev", defaultTmuxSession = "main")

            val shell = launcher.openTerminal(plain)
            shell.session.attach shouldBe AttachSpec.Shell
            shell.title shouldBe "plain"
            shell.attachment.shouldBeInstanceOf<TerminalAttachment>().engine shouldBe engines[0]
            serviceStarts shouldBe 1
            navigator.selectedTabId.value shouldBe shell.id
            withTimeout(1_000) { navigator.showRequests.first() }

            val tmux = launcher.openTerminal(dev)
            tmux.session.attach shouldBe AttachSpec.Tmux("main")
            tmux.title shouldBe "main · dev"
            launcher.openTerminal(dev, AttachSpec.Shell).session.attach shouldBe AttachSpec.Shell
            world.coordinator.tabs.value.size shouldBe 3
            serviceStarts shouldBe 3
        }

    @Test
    fun `output reaches the emulator and its replies reach the host`() =
        runBlocking<Unit> {
            val id = world.addHost()
            val tab = launcher.openTerminal(id)
            awaitUntil { world.host.shells.isNotEmpty() && tab.session.state.value == TerminalState.Open }
            val shell = world.host.shells.single()
            shell.pty shouldBe PtySpec(100, 30)
            shell.emit("hello")
            awaitUntil { engines[0].fed() == "hello" }
            engines[0].onRemoteInput!!.invoke("\u001b[?1;2c".toByteArray())
            awaitUntil { shell.typed() == "\u001b[?1;2c" }

            launcher.close(tab.id)
            engines[0].closed shouldBe true
            world.coordinator.tabs.value shouldBe emptyList()
        }

    @Test
    fun `attach reuses a live tab and reconnect replaces an ended one`() =
        runBlocking<Unit> {
            val id = world.addHost()
            val shell = launcher.openTerminal(id)
            val first = launcher.attachTmux(id, "api", "~/src")
            first shouldNotBe shell
            first.session.attach shouldBe AttachSpec.Tmux("api", "~/src")
            launcher.attachTmux(id, "api") shouldBe first
            launcher.attachTmux(id, "web") shouldNotBe first

            awaitUntil { world.host.shells.size == 3 }
            world.host.shells
                .first { it.command.orEmpty().contains(" bridle api ") }
                .hangUp()
            awaitUntil { first.session.state.value is TerminalState.Ended }
            val again = launcher.attachTmux(id, "api")
            again shouldNotBe first
            launcher.attachTmux(world.addHost("other"), "api") shouldNotBe again

            val replaced = launcher.reconnect(again.id)!!
            replaced.session.attach shouldBe AttachSpec.Tmux("api")
            replaced.title shouldBe again.title
            world.coordinator.tab(again.id) shouldBe null
            launcher.reconnect("missing") shouldBe null
        }

    @Test
    fun `bad session names and deleted hosts are rejected without leaking`() =
        runBlocking<Unit> {
            val id = world.addHost()
            shouldThrow<IllegalArgumentException> { launcher.openTerminal(id, AttachSpec.Tmux("bad name")) }
            engines.single().closed shouldBe true
            world.coordinator.tabs.value shouldBe emptyList()
            shouldThrow<UnknownHostException> { launcher.openTerminal(999) }
            serviceStarts shouldBe 0
        }

    @Test
    fun `any failure while opening releases the emulator and the connection`() =
        runBlocking<Unit> {
            val id = world.addHost()
            val engine = FakeEngine()
            val refusing =
                object : TerminalEngine by engine {
                    override var onRemoteInput: ((ByteArray) -> Unit)?
                        get() = null
                        set(_) = error("emulator gone")
                }
            val failing =
                TerminalLauncher(
                    world.coordinator,
                    world.data.hosts,
                    { refusing },
                    { serviceStarts++ },
                    navigator,
                    { error("not started for a tab that failed to open") },
                    scope,
                )
            shouldThrow<IllegalStateException> { failing.openTerminal(id) }
            engine.closed shouldBe true
            world.coordinator.tabs.value shouldBe emptyList()
            serviceStarts shouldBe 0
            awaitUntil { runBlocking { world.connections.activeHosts() }.isEmpty() }
        }

    @Test
    fun `opening a tab starts the host's auto-start forwards, and disconnect all stops them`() =
        runBlocking<Unit> {
            val forwards = ForwardManager(world.connections, world.data.portForwards, scope)
            autoStart = HostAutoStart { forwards.startAutoForwards(it) }
            val id = world.addHost()
            val other = world.addHost("other")
            val auto =
                world.data.portForwards.upsert(
                    PortForward(hostId = id, type = PortForwardType.DYNAMIC, bindPort = freePort(), autoStart = true),
                )
            world.data.portForwards.upsert(
                PortForward(hostId = id, type = PortForwardType.DYNAMIC, bindPort = freePort()),
            )
            world.data.portForwards.upsert(
                PortForward(hostId = other, type = PortForwardType.DYNAMIC, bindPort = freePort(), autoStart = true),
            )

            launcher.openTerminal(id)
            forwards.statuses.await { it.keys == setOf(auto) }
            launcher.openTerminal(id, AttachSpec.Tmux("api")) // already running: left alone
            forwards.statuses.await { it.values.single().phase == ForwardStatus.Phase.RUNNING }
            forwards.statuses.value.keys shouldBe setOf(auto)

            world.coordinator.disconnectAll()
            forwards.statuses.await { it.isEmpty() }
        }

    @Test
    fun `auto start runs in the background for every tab and its failures stay there`() =
        runBlocking<Unit> {
            val id = world.addHost()
            val started = CopyOnWriteArrayList<Long>()
            val never = CompletableDeferred<Unit>()
            autoStart =
                HostAutoStart { host ->
                    started += host
                    never.await() // a slow start never holds up the terminal
                }
            val tab = withTimeout(5_000) { launcher.openTerminal(id) }
            navigator.selectedTabId.value shouldBe tab.id
            awaitUntil { started == listOf(id) }

            autoStart =
                HostAutoStart { host ->
                    started += host
                    throw IOException("database gone")
                }
            val replaced = launcher.reconnect(tab.id)!!
            awaitUntil { started == listOf(id, id) }
            launcher.attachTmux(id, "api")
            awaitUntil { started == listOf(id, id, id) }
            world.coordinator.tabs.value
                .map { it.id } shouldBe listOf(replaced.id, navigator.selectedTabId.value)
            scope.isActive shouldBe true
        }

    @Test
    fun `concurrent attaches to one session open one tab`() =
        runBlocking<Unit> {
            val id = world.addHost()
            val tabs = List(4) { scope.async { launcher.attachTmux(id, "api") } }.awaitAll()
            tabs.toSet().size shouldBe 1
            world.coordinator.tabs.value.size shouldBe 1
        }

    @Test
    fun `reconnecting keeps the host leased and the tab count above zero`() =
        runBlocking<Unit> {
            val id = world.addHost()
            val tab = launcher.openTerminal(id, AttachSpec.Tmux("api"))
            val counts = CopyOnWriteArrayList<Int>()
            val watch = scope.launch { world.coordinator.tabs.collect { counts += it.size } }
            awaitUntil { counts.isNotEmpty() }
            val replaced = launcher.reconnect(tab.id)!!
            awaitUntil { counts.last() == 1 && world.coordinator.tab(tab.id) == null }
            watch.cancel()
            counts.none { it == 0 } shouldBe true
            world.coordinator.tabs.value shouldBe listOf(replaced)
        }

    @Test
    fun `notification taps attach to their session once`() =
        runBlocking<Unit> {
            val id = world.addHost()
            val sessionLinks = SessionLinks(context)
            val links = TerminalDeepLinks(launcher, sessionLinks, scope)
            links.handle(null) shouldBe null
            links.handle(Intent()) shouldBe null
            // Without Bridle's token (another app starting the exported activity): nothing opens.
            links.handle(
                Intent().putExtra(SessionLinks.EXTRA_HOST, id).putExtra(SessionLinks.EXTRA_SESSION, "api"),
            ) shouldBe null

            fun tap(
                host: Long,
                session: String,
            ) = sessionLinks.intent(SessionLink(host, session))
            val intent = tap(id, "api")
            links.handle(intent)!!.join()
            world.coordinator.tabs.value
                .single()
                .session.attach shouldBe AttachSpec.Tmux("api")
            links.handle(intent) shouldBe null // consumed
            links.handle(tap(999, "api"))!!.join()
            links.handle(tap(id, "bad name"))!!.join()
            world.coordinator.tabs.value.size shouldBe 1
        }

    @Test
    fun `hosts list taps open a terminal on the host`() =
        runBlocking<Unit> {
            val id = world.addHost()
            val links = TerminalDeepLinks(launcher, SessionLinks(context), scope)
            links.openHost(id).join()
            world.coordinator.tabs.value
                .single()
                .hostId shouldBe id
            links.openHost(999).join() // deleted host: nothing opens, nothing crashes
            world.coordinator.tabs.value.size shouldBe 1
        }

    @Test
    fun `the module provides termlib engines and starts the session service`() {
        val engine = TerminalModule.engineFactory().create()
        engine.shouldBeInstanceOf<TermlibEngine>()
        engine.close()
        TerminalModule.serviceStarter(context).ensureRunning()
        shadowOf(context).nextStartedService.component!!.className shouldContain SessionService::class.java.simpleName
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `the navigator tracks the selected and visible tabs`() =
        runBlocking<Unit> {
            navigator.select("a")
            navigator.selectedTabId.value shouldBe "a"
            navigator.showTerminals()
            withTimeout(1_000) { navigator.showRequests.first() }
            navigator.setVisibleTab("b")
            navigator.visibleTabId.value shouldBe "b"
            navigator.setVisibleTab(null)
            navigator.visibleTabId.value shouldBe null
        }
}
