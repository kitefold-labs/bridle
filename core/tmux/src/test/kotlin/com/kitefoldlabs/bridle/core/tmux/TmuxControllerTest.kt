package com.kitefoldlabs.bridle.core.tmux

import com.kitefoldlabs.bridle.core.model.LaunchMode
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.RestartMode
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.RemoteCommandException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class TmuxControllerTest {
    @TempDir
    lateinit var home: Path

    private lateinit var shell: LocalShell
    private lateinit var tmux: TmuxController
    private lateinit var project: Path
    private lateinit var fakeClaude: String

    @BeforeEach
    fun setUp() {
        val bin = LocalShell.fakeClaude(home.resolve("bin"))
        fakeClaude = bin.resolve("claude").toString()
        shell = LocalShell(home, extraPath = bin)
        tmux = TmuxController(shell)
        project = Files.createDirectories(home.resolve("src/my app"))
    }

    @AfterEach
    fun tearDown() = shell.close()

    private fun profile(
        name: String = "claude-app",
        mode: LaunchMode = LaunchMode.SHELL_HOSTED,
        restart: RestartMode = RestartMode.RESPAWN_CONTINUE,
    ) = LaunchProfile
        .claude(hostId = 1, workingDir = "~/src/my app", sessionName = name)
        .copy(
            mode = mode,
            restartMode = restart,
            env = mapOf("ANTHROPIC_MODEL" to "it's-fine"),
            // Absolute path: the pane's login shell rebuilds PATH from /etc/profile.
            command = fakeClaude,
            restartCommand = "$fakeClaude --continue",
        )

    private suspend fun screenOf(
        session: String,
        contains: String,
    ): String {
        var text = ""
        withTimeout(10.seconds) {
            while (contains !in text) {
                text = tmux.preview(session)
                if (contains !in text) delay(50)
            }
        }
        return text
    }

    @Test
    fun `probe reports tmux, claude and helper`() =
        runBlocking<Unit> {
            val before = tmux.probe()
            before.tmuxSupported shouldBe true
            before.claudePath shouldBe home.resolve("bin/claude").toString()
            before.helperVersion shouldBe null
            tmux.installHelper(installHooks = false)
            tmux.probe().helperVersion shouldBe HostHelper.PROTOCOL_VERSION
        }

    @Test
    fun `launch, list, preview, send and kill a shell-hosted claude`() =
        runBlocking<Unit> {
            tmux.listSessions().shouldBeEmpty() // no server yet
            tmux.launch(profile(), columns = 100, rows = 30) shouldBe LaunchResult.CREATED
            tmux.launch(profile()) shouldBe LaunchResult.ALREADY_RUNNING

            val screen = screenOf("claude-app", "fake-claude started")
            screen shouldContain "model=[it's-fine]"
            screen shouldContain "pwd=$project"

            val sessions = tmux.listSessions(claudeHint = { it == "claude-app" })
            sessions.single().session.name shouldBe "claude-app"
            sessions.single().activePane!!.currentPath shouldBe project.toString()

            tmux.sendText("claude-app", "hello world; \$(id)")
            screenOf("claude-app", "got:hello world; \$(id)")
            tmux.sendKeys("claude-app", "C-c")
            tmux.kill("claude-app")
            tmux.listSessions().shouldBeEmpty()
        }

    @Test
    fun `restart continues the conversation in place`() =
        runBlocking<Unit> {
            tmux.restart(profile()) shouldBe RestartResult.CREATED // missing → launched
            screenOf("claude-app", "args=[]")
            tmux.restart(profile()) shouldBe RestartResult.RESTARTED
            val screen = screenOf("claude-app", "args=[--continue]")
            screen shouldContain "model=[it's-fine]"

            tmux.restart(profile(restart = RestartMode.RESPAWN_FRESH)) shouldBe RestartResult.RESTARTED
            screenOf("claude-app", "args=[]")
        }

    @Test
    fun `restart with send-keys interrupts and retypes`() =
        runBlocking<Unit> {
            val p = profile(restart = RestartMode.SEND_KEYS)
            tmux.launch(p)
            screenOf("claude-app", "fake-claude started")
            tmux.restart(p) shouldBe RestartResult.RESTARTED
            withTimeout(10.seconds) {
                while (tmux.preview("claude-app").split("fake-claude started").size < 3) delay(50)
            }
        }

    @Test
    fun `direct mode runs the command as the pane process`() =
        runBlocking<Unit> {
            val p = profile(name = "direct", mode = LaunchMode.DIRECT)
            tmux.launch(p) shouldBe LaunchResult.CREATED
            screenOf("direct", "fake-claude started")
            tmux
                .listSessions()
                .single()
                .activePane!!
                .currentCommand shouldNotBe ""
            tmux.restart(p) shouldBe RestartResult.RESTARTED
            screenOf("direct", "args=[--continue]")
        }

    @Test
    fun `helper status is merged when enabled`() =
        runBlocking<Unit> {
            tmux.installHelper(installHooks = true)
            home.resolve(".claude/settings.json").toFile().readText() shouldContain "bridle-helper' hook Notification"
            tmux.launch(profile())
            val state = Files.createDirectories(home.resolve(".local/state/bridle/sessions"))
            state.resolve("claude-app.json").toFile().writeText(
                """{"v":1,"state":"waiting_permission","tmux_session":"claude-app","message":"Allow Bash?","ts":5}""" +
                    "\n",
            )
            val row = tmux.listSessions(useHelper = true).single()
            row.claude!!.state shouldBe ClaudeState.WAITING_PERMISSION
            row.claude.message shouldBe "Allow Bash?"
            tmux.uninstallHelper()
            tmux.probe().helperVersion shouldBe null
            home.resolve(".claude/settings.json").toFile().readText() shouldNotContain "bridle-helper"
        }

    @Test
    fun `failures are typed`() =
        runBlocking<Unit> {
            val noTmux = TmuxController(FixedResult(ExecResult(127, "", "tmux is not installed")))
            shouldThrow<TmuxNotInstalledException> { noTmux.listSessions() }
            val broken = TmuxController(FixedResult(ExecResult(1, "", "boom")))
            shouldThrow<RemoteCommandException> { broken.kill("x") }.message shouldContain "boom"
            shouldThrow<RemoteCommandException> { broken.installHelper(false) }
            shouldThrow<RemoteCommandException> { broken.uninstallHelper() }
            val odd = TmuxController(FixedResult(ExecResult(0, "???", "")))
            shouldThrow<RemoteCommandException> { odd.launch(profile()) }
            shouldThrow<RemoteCommandException> { odd.restart(profile()) }
            shouldThrow<RemoteCommandException> { odd.installHelper(false) }
            odd.listSessions(useHelper = true).shouldBeEmpty()
        }
}
