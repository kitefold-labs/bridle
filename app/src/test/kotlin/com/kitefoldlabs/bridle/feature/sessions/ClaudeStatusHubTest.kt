package com.kitefoldlabs.bridle.feature.sessions

import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.settings.NotificationEvent
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.ClaudeStatusEvent
import com.kitefoldlabs.bridle.core.session.HostConnectionState
import com.kitefoldlabs.bridle.core.tmux.ClaudeState
import com.kitefoldlabs.bridle.core.tmux.StatusSource
import com.kitefoldlabs.bridle.feature.FakeSession
import com.kitefoldlabs.bridle.feature.TestWorld
import com.kitefoldlabs.bridle.feature.awaitUntil
import com.kitefoldlabs.bridle.feature.terminal.TerminalNavigator
import com.kitefoldlabs.bridle.service.ClaudeNotifier
import com.kitefoldlabs.bridle.service.Notifications
import com.kitefoldlabs.bridle.service.SessionLinks
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClaudeStatusHubTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val world = TestWorld(context, scope)
    private val host = world.host
    private val visible = MutableStateFlow(true)
    private val navigator = TerminalNavigator()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val hub =
        ClaudeStatusHub(
            world.coordinator,
            world.data.hosts,
            world.data.launchProfiles,
            world.data.settings,
            ClaudeNotifier(context, SessionLinks(context)),
            navigator,
            AppVisibility(visible),
            scope,
        )

    @Before
    fun setUp() {
        Notifications.createChannels(context)
    }

    @After
    fun tearDown() {
        runBlocking { world.coordinator.disconnectAll() }
        world.data.close()
        scope.cancel()
    }

    private fun state(
        session: String,
        value: String,
        ts: Long,
        message: String = "",
    ) = """{"v":1,"state":"$value","tmux_session":"$session","message":"$message","ts":$ts}"""

    private fun observe(id: Long): Pair<Job, CopyOnWriteArrayList<HostStatus>> {
        val seen = CopyOnWriteArrayList<HostStatus>()
        return scope.launch { hub.observe(id).collect { seen += it } } to seen
    }

    @Test
    fun `observing a host reports its connection, capabilities, sessions and previews`() =
        runBlocking<Unit> {
            host.helper = "bridle-helper 1"
            host.sessions = listOf(FakeSession("api", command = "claude"), FakeSession("logs"))
            host.helperStates = state("api", "working", 10)
            host.screens = mapOf("api" to "one\n\n  two  \nthree\nfour\n\n")
            val id = world.addHost()
            val (job, seen) = observe(id)
            awaitUntil { seen.lastOrNull()?.loaded == true }
            val status = seen.last()
            status.connection.shouldBeInstanceOf<HostConnectionState.Connected>()
            status.probe!!.helperVersion shouldBe 1
            status.sessions.map { it.session.name }.toSet() shouldBe setOf("api", "logs")
            status.sessions
                .first { it.session.name == "api" }
                .claude!!
                .source shouldBe StatusSource.HOOKS
            status.previews shouldBe mapOf("api" to "  two\nthree\nfour", "logs" to "")
            status.error shouldBe null
            job.cancel()
            awaitUntil { runBlocking { world.connections.activeHosts() }.isEmpty() }
        }

    @Test
    fun `missing tmux, failures and refreshes are reported`() =
        runBlocking<Unit> {
            host.failing = true
            val id = world.addHost()
            val (job, seen) = observe(id)
            awaitUntil { seen.lastOrNull()?.error != null }
            seen.last().probe shouldBe null

            host.failing = false
            host.tmux = ""
            visible.value = false // wakes the monitor without a refresh: it probes again anyway
            awaitUntil { seen.last().probe != null && seen.last().error == null }
            seen.last().probe!!.tmuxSupported shouldBe false
            seen.last().sessions.shouldBeEmpty()
            visible.value = true

            host.tmux = "tmux 3.4"
            host.sessions = listOf(FakeSession("api"))
            host.failPreview = true
            hub.refresh(id)
            awaitUntil { seen.last().sessions.size == 1 }
            seen.last().previews shouldBe mapOf("api" to "")
            job.cancel()
            hub.refresh(999) // not watched: ignored
        }

    @Test
    fun `claude transitions post notifications`() =
        runBlocking<Unit> {
            host.helper = "bridle-helper 1"
            host.sessions = listOf(FakeSession("api", command = "node"))
            host.helperStates = state("api", "working", 10)
            val id = world.addHost()
            world.data.launchProfiles.upsert(LaunchProfile.claude(id, "~/api", "api"))
            val (job, seen) = observe(id)
            awaitUntil {
                seen
                    .lastOrNull()
                    ?.sessions
                    ?.singleOrNull()
                    ?.claudeRunning == true
            }

            host.helperStates = state("api", "waiting_permission", 20, "Claude needs your permission to use Bash")
            hub.refresh(id)
            awaitUntil { shadowOf(manager).allNotifications.size == 1 }
            NotificationCompat.getContentTitle(shadowOf(manager).allNotifications.single()).toString() shouldContain
                "api · devbox"
            job.cancel()
        }

    @Test
    fun `notifications respect the settings and what is on screen`() =
        runBlocking<Unit> {
            val id = world.addHost()
            val other = world.addHost("gpu")

            fun event(
                session: String,
                hostId: Long = id,
                to: ClaudeState = ClaudeState.WAITING_PERMISSION,
            ) = ClaudeStatusEvent(hostId, session, ClaudeState.WORKING, to, "Allow?")

            fun posted() = shadowOf(manager).size()

            hub.notify(event("a", to = ClaudeState.WORKING))
            world.data.settings.setNotificationEventEnabled(NotificationEvent.PERMISSION_REQUEST, false)
            hub.notify(event("a"))
            posted() shouldBe 0
            world.data.settings.setNotificationEventEnabled(NotificationEvent.PERMISSION_REQUEST, true)

            val shell = world.coordinator.openTab(id, "shell", AttachSpec.Shell, { _, _ -> })
            navigator.setVisibleTab(shell.id)
            hub.notify(event("plain"))
            posted() shouldBe 1 // a shell tab is not that session
            val tab = world.coordinator.openTab(id, "api", AttachSpec.Tmux("api"), { _, _ -> })
            navigator.setVisibleTab(tab.id)
            hub.notify(event("api"))
            posted() shouldBe 1 // the user is looking at it
            hub.notify(event("web"))
            posted() shouldBe 2
            hub.notify(event("api", hostId = other))
            posted() shouldBe 3
            visible.value = false
            hub.notify(event("api"))
            posted() shouldBe 4
            visible.value = true
            navigator.setVisibleTab("closed")
            hub.notify(event("idle", to = ClaudeState.IDLE))
            posted() shouldBe 5
            hub.notify(event("gone", hostId = 999))
            posted() shouldBe 5
        }

    @Test
    fun `open tabs keep their hosts monitored`() =
        runBlocking<Unit> {
            val id = world.addHost()
            val other = world.addHost("gpu")
            val follow = scope.launch { hub.followOpenTabs() }
            val tab = world.coordinator.openTab(id, "t", AttachSpec.Shell, { _, _ -> })
            awaitUntil { host.scriptsContaining("list-sessions").isNotEmpty() }
            host.scriptsContaining("capture-pane").shouldBeEmpty()
            world.coordinator.openTab(other, "t", AttachSpec.Shell, { _, _ -> })
            awaitUntil { host.scriptsContaining("list-sessions").size >= 2 }
            world.coordinator.closeTab(tab.id)
            follow.cancel()
            follow.join()
            world.coordinator.disconnectAll()
            awaitUntil { runBlocking { world.connections.activeHosts() }.isEmpty() }
        }

    @Test
    fun `hosts are polled again after the interval, and helper status can be turned off`() =
        runBlocking<Unit> {
            world.data.settings.setUseHelperByDefault(false)
            host.helper = "bridle-helper 1"
            host.sessions = listOf(FakeSession("api", command = "claude"))
            host.helperStates = state("api", "working", 10)
            host.screens = mapOf("api" to "> ")
            val id = world.addHost()
            val (job, seen) = observe(id)
            val follow = scope.launch { hub.followOpenTabs() }
            world.coordinator.openTab(id, "t", AttachSpec.Shell, { _, _ -> }) // a second watcher
            awaitUntil { seen.lastOrNull()?.loaded == true }
            seen
                .last()
                .sessions
                .single()
                .claude!!
                .source shouldBe StatusSource.SCREEN
            host.scriptsContaining(" list 2>/dev/null").shouldBeEmpty()
            val before = host.scriptsContaining("list-sessions").size
            awaitUntil(timeoutMs = 8_000) { host.scriptsContaining("list-sessions").size > before }
            job.cancel() // the open tab still watches the host
            val after = host.scriptsContaining("list-sessions").size
            hub.refresh(id)
            awaitUntil { host.scriptsContaining("list-sessions").size > after }
            follow.cancel()
        }

    @Test
    fun `polling slows down in the background and wakes when the app is shown`() =
        runBlocking<Unit> {
            visible.value = false
            val id = world.addHost()
            val (job, seen) = observe(id)
            awaitUntil { seen.lastOrNull()?.loaded == true }
            val before = host.scriptsContaining("list-sessions").size
            visible.value = true
            awaitUntil(timeoutMs = 3_000) { host.scriptsContaining("list-sessions").size > before }
            job.cancel()
        }

    @Test
    fun `visibility follows a lifecycle`() =
        runBlocking<Unit> {
            val owner =
                object : LifecycleOwner {
                    val registry = LifecycleRegistry.createUnsafe(this)
                    override val lifecycle: Lifecycle get() = registry
                }
            owner.registry.currentState = Lifecycle.State.CREATED
            val visibility = AppVisibility.of(owner.lifecycle, scope)
            visibility.isVisible.value shouldBe false
            owner.registry.currentState = Lifecycle.State.STARTED
            awaitUntil { visibility.isVisible.value }
            SessionsModule.visibility(scope).isVisible.value shouldBe false
        }

    @Test
    fun `helpers map states to settings and keep the last lines`() {
        ClaudeStatusHub.notificationEvent(ClaudeState.WAITING_PERMISSION) shouldBe NotificationEvent.PERMISSION_REQUEST
        ClaudeStatusHub.notificationEvent(ClaudeState.WAITING_INPUT) shouldBe NotificationEvent.WAITING_FOR_INPUT
        ClaudeStatusHub.notificationEvent(ClaudeState.IDLE) shouldBe NotificationEvent.WAITING_FOR_INPUT
        ClaudeStatusHub.notificationEvent(ClaudeState.EXITED) shouldBe NotificationEvent.SESSION_EXITED
        ClaudeStatusHub.notificationEvent(ClaudeState.WORKING) shouldBe null
        ClaudeStatusHub.notificationEvent(ClaudeState.UNKNOWN) shouldBe null
        IllegalStateException("refused").describe() shouldBe "refused"
        IllegalStateException().describe() shouldBe "IllegalStateException"
        ClaudeStatusHub.lastLines("") shouldBe ""
        ClaudeStatusHub.lastLines("a\nb\nc\nd") shouldBe "b\nc\nd"
    }
}
