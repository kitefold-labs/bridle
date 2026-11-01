package com.kitefoldlabs.bridle.core.session

import app.cash.turbine.test
import com.kitefoldlabs.bridle.core.ssh.ConnectionState
import com.kitefoldlabs.bridle.core.ssh.DuplexStream
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.ssh.ShellChannel
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.core.tmux.ClaudeState
import com.kitefoldlabs.bridle.core.tmux.TmuxController
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Answers tmux list scripts with scripted session tables and helper states. */
private class ScriptedTmuxHost : SshConnection {
    var sessions: List<String> = emptyList()
    var command: String = "claude"
    var helper: String = ""
    var screen: String = ""
    var fail = false
    var failPreview = false
    var lists = 0
    override val state = MutableStateFlow(ConnectionState.CONNECTED)

    override suspend fun exec(
        command: String,
        timeout: Duration,
    ) = ExecResult(0, "", "")

    override suspend fun runScript(
        script: String,
        timeout: Duration,
    ): ExecResult {
        if (fail) return ExecResult(1, "", "server exited")
        val sep = ":|:"
        return when {
            "list-sessions" in script -> {
                lists++
                ExecResult(
                    0,
                    sessions.joinToString("\n") { "$it${sep}1${sep}0${sep}1${sep}1" } + "\n@@BRIDLE_PANES@@\n" +
                        sessions.joinToString("\n") { "$it$sep%1${sep}1${sep}1${sep}0$sep$command$sep/src" },
                    "",
                )
            }
            "bridle-helper' list" in script || "bridle-helper list" in script -> ExecResult(0, helper, "")
            "capture-pane" in script -> if (failPreview) ExecResult(1, "", "gone") else ExecResult(0, screen, "")
            else -> ExecResult(0, "", "")
        }
    }

    override suspend fun openShell(
        pty: PtySpec,
        command: String?,
    ): ShellChannel = throw UnsupportedOperationException()

    override suspend fun openDirectTcpip(
        host: String,
        port: Int,
    ): DuplexStream = throw UnsupportedOperationException()

    override fun close() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeStatusMonitorTest {
    private fun state(
        session: String,
        value: String,
        ts: Long,
        message: String = "",
    ) = """{"v":1,"state":"$value","tmux_session":"$session","message":"$message","ts":$ts}"""

    @Test
    fun `notification policy`() {
        NotificationPolicy.shouldNotify(null, ClaudeState.WAITING_PERMISSION) shouldBe false
        NotificationPolicy.shouldNotify(ClaudeState.WORKING, ClaudeState.WAITING_PERMISSION) shouldBe true
        NotificationPolicy.shouldNotify(ClaudeState.IDLE, ClaudeState.WAITING_PERMISSION) shouldBe true
        NotificationPolicy.shouldNotify(ClaudeState.WAITING_PERMISSION, ClaudeState.WAITING_PERMISSION) shouldBe false
        NotificationPolicy.shouldNotify(ClaudeState.WORKING, ClaudeState.IDLE) shouldBe true
        NotificationPolicy.shouldNotify(ClaudeState.WAITING_PERMISSION, ClaudeState.IDLE) shouldBe false
        NotificationPolicy.shouldNotify(ClaudeState.WORKING, ClaudeState.WAITING_INPUT) shouldBe true
        NotificationPolicy.shouldNotify(ClaudeState.IDLE, ClaudeState.EXITED) shouldBe true
        NotificationPolicy.shouldNotify(ClaudeState.UNKNOWN, ClaudeState.EXITED) shouldBe false
        NotificationPolicy.shouldNotify(ClaudeState.IDLE, ClaudeState.WORKING) shouldBe false
        NotificationPolicy.shouldNotify(ClaudeState.IDLE, ClaudeState.UNKNOWN) shouldBe false
        PollMode.BACKGROUND.interval shouldBe 45.seconds
    }

    @Test
    fun `helper transitions become events after the baseline`() =
        runTest {
            val host = ScriptedTmuxHost()
            host.sessions = listOf("api", "web")
            host.helper = state("api", "working", 1) + "\n" + state("web", "idle", 1)
            val monitor = ClaudeStatusMonitor(5, TmuxController(host), useHelper = { true })
            monitor.events.test {
                monitor.pollOnce() // baseline: no events
                monitor.sessions.value.size shouldBe 2

                host.helper = state("api", "waiting_permission", 2, "Allow Bash?") + "\n" + state("web", "idle", 1)
                monitor.pollOnce()
                awaitItem() shouldBe
                    ClaudeStatusEvent(5, "api", ClaudeState.WORKING, ClaudeState.WAITING_PERMISSION, "Allow Bash?")

                host.helper = state("api", "working", 3)
                monitor.pollOnce() // working: silent
                host.sessions = listOf("web")
                monitor.pollOnce() // api vanished while working → exited
                awaitItem().to shouldBe ClaudeState.EXITED
                expectNoEvents()
            }
        }

    @Test
    fun `screen guesses fill in without the helper and run loop survives errors`() =
        runTest {
            val host = ScriptedTmuxHost()
            host.sessions = listOf("api")
            host.screen = "✻ Working… (esc to interrupt)"
            val monitor = ClaudeStatusMonitor(1, TmuxController(host), useHelper = { false })
            val job = launch { monitor.run() }
            runCurrent()
            monitor.sessions.value
                .single()
                .claude!!
                .state shouldBe ClaudeState.WORKING

            monitor.events.test {
                host.screen = "Do you want to proceed?\n❯ 1. Yes"
                advanceTimeBy(PollMode.FOREGROUND.interval)
                runCurrent()
                awaitItem().to shouldBe ClaudeState.WAITING_PERMISSION

                host.fail = true
                monitor.setMode(PollMode.BACKGROUND)
                advanceTimeBy(PollMode.FOREGROUND.interval)
                runCurrent()
                host.fail = false
                host.failPreview = true
                advanceTimeBy(PollMode.BACKGROUND.interval)
                runCurrent()
                monitor.sessions.value
                    .single()
                    .claude!!
                    .state shouldBe ClaudeState.UNKNOWN
                expectNoEvents()
            }
            job.cancel()
        }

    @Test
    fun `switching to foreground polls at once instead of finishing the background sleep`() =
        runTest {
            val host = ScriptedTmuxHost()
            host.sessions = listOf("api")
            val monitor = ClaudeStatusMonitor(1, TmuxController(host), useHelper = { false })
            monitor.setMode(PollMode.BACKGROUND)
            val job = launch { monitor.run() }
            runCurrent()
            host.lists shouldBe 1
            advanceTimeBy(5.seconds)
            runCurrent()
            host.lists shouldBe 1 // asleep in background mode
            monitor.setMode(PollMode.FOREGROUND)
            runCurrent()
            host.lists shouldBe 2
            advanceTimeBy(PollMode.FOREGROUND.interval)
            runCurrent()
            host.lists shouldBe 3
            job.cancel()
        }

    @Test
    fun `non-claude panes and unknown sessions that vanish stay quiet`() =
        runTest {
            val host = ScriptedTmuxHost()
            host.sessions = listOf("shell")
            host.command = "bash"
            val monitor = ClaudeStatusMonitor(1, TmuxController(host), useHelper = { false })
            monitor.events.test {
                monitor.pollOnce()
                monitor.sessions.value
                    .single()
                    .claude shouldBe null
                host.command = "claude"
                host.screen = "plain output"
                monitor.pollOnce() // appears as UNKNOWN: no event
                host.sessions = emptyList()
                monitor.pollOnce() // UNKNOWN vanished: no event
                expectNoEvents()
            }
        }
}
