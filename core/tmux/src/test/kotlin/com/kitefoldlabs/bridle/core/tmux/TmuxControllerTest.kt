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
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.seconds

private const val SECRET = "sk-'s3cr3t\"\nvalue;"

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
            // The phone's size mustn't stick to a desktop client attached to the same window.
            shell.exec("tmux show-options -wv -t '=claude-app:' window-size").stdout.trim() shouldBe "latest"

            val screen = screenOf("claude-app", "fake-claude started")
            screen shouldContain "model=[it's-fine]"
            screen shouldContain "pwd=$project"
            val batch = tmux.previews(listOf("claude-app", "gone"), lines = 20)
            batch.getValue("claude-app") shouldContain "fake-claude started"
            batch.getValue("gone").trim() shouldBe "" // no such session: empty, the others still come

            val sessions = tmux.listSessions(claudeHint = { it == "claude-app" })
            sessions.single().session.name shouldBe "claude-app"
            sessions.single().activePane!!.currentPath shouldBe project.toString()

            tmux.sendText("claude-app", "hello world; \$(id)")
            screenOf("claude-app", "got:hello world; \$(id)")
            // tmux would drop a trailing ';' (its command separator) or turn '\;' into ';'.
            tmux.sendText("claude-app", "ls;")
            screenOf("claude-app", "got:ls;")
            tmux.sendText("claude-app", "a\\;")
            screenOf("claude-app", "got:a\\;")
            tmux.sendKeys("claude-app", ";", "Enter")
            screenOf("claude-app", "got:;")
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
    fun `a working directory ending in a semicolon cannot split the tmux command`() =
        runBlocking<Unit> {
            // Unescaped, tmux would read `-c ~/semi` and then run the command as a tmux command.
            val dir = Files.createDirectories(home.resolve("semi;"))
            val p = profile(name = "semi", mode = LaunchMode.DIRECT).copy(workingDir = "~/semi;")
            tmux.launch(p) shouldBe LaunchResult.CREATED
            screenOf("semi", "pwd=$dir")
            tmux.restart(p) shouldBe RestartResult.RESTARTED
            screenOf("semi", "args=[--continue]")
        }

    /** Everything the session could show: the whole scrollback and how each pane was started. */
    private suspend fun everythingVisible(session: String): String =
        tmux.preview(session, lines = TmuxScripts.MAX_PREVIEW_LINES) +
            shell.exec("tmux list-panes -a -F '#{pane_start_command}'").stdout

    private fun secretProfile(mode: LaunchMode) =
        profile(name = "secret", mode = mode).copy(
            // Ends in ';' (a tmux separator) and holds quotes and a newline.
            env = mapOf("BRIDLE_SECRET" to SECRET, "ANTHROPIC_MODEL" to "it's-fine"),
        )

    @Test
    fun `environment values never reach the pane, with new-session -e`() =
        runBlocking<Unit> {
            for (mode in LaunchMode.entries) {
                val p = secretProfile(mode)
                tmux.launch(p) shouldBe LaunchResult.CREATED // tmux 3.4: -e
                shell.scripts.last() shouldContain " -e "
                screenOf("secret", "secret=[${SECRET.length}]") shouldContain "model=[it's-fine]"
                tmux.restart(p) shouldBe RestartResult.RESTARTED
                screenOf("secret", "args=[--continue] model=[it's-fine] secret=[${SECRET.length}]")
                val seen = everythingVisible("secret")
                seen shouldNotContain "export"
                seen shouldNotContain "s3cr3t"
                tmux.kill("secret")
            }
        }

    @Test
    fun `environment values never reach the pane, on tmux older than 3_2`() =
        runBlocking<Unit> {
            for (mode in LaunchMode.entries) {
                val p = secretProfile(mode)
                val launch = TmuxScripts.launch(p, tmuxVersion = TmuxVersion(3, 1))
                launch shouldNotContain " -e "
                shell.runScript(launch).stdout.trim() shouldBe "created"
                screenOf("secret", "secret=[${SECRET.length}]") shouldContain "model=[it's-fine]"
                // A restart that finds the session gone launches it the same way.
                tmux.kill("secret")
                shell.runScript(TmuxScripts.restart(p, TmuxVersion(3, 1))).stdout.trim() shouldBe "created"
                screenOf("secret", "secret=[${SECRET.length}]")
                val seen = everythingVisible("secret")
                seen shouldNotContain "export"
                seen shouldNotContain "s3cr3t"
                tmux.kill("secret")
            }
        }

    @Test
    fun `a respawn restart picks up changed environment values`() =
        runBlocking<Unit> {
            val p = secretProfile(LaunchMode.SHELL_HOSTED)
            tmux.launch(p)
            screenOf("secret", "secret=[${SECRET.length}]")
            tmux.restart(p.copy(env = mapOf("BRIDLE_SECRET" to "xy"))) shouldBe RestartResult.RESTARTED
            screenOf("secret", "model=[] secret=[2]") // the removed ANTHROPIC_MODEL is unset
            tmux.restart(p.copy(env = emptyMap())) shouldBe RestartResult.RESTARTED
            screenOf("secret", "model=[] secret=[0]")
        }

    @Test
    fun `a respawn restart after a placeholder launch reruns the login shell, not the placeholder`() =
        runBlocking<Unit> {
            val old = TmuxVersion(3, 1)
            val p = secretProfile(LaunchMode.SHELL_HOSTED)
            shell.runScript(TmuxScripts.launch(p, tmuxVersion = old)).stdout.trim() shouldBe "created"
            screenOf("secret", "secret=[${SECRET.length}]")
            val changed = p.copy(env = mapOf("BRIDLE_SECRET" to "xy"))
            shell.runScript(TmuxScripts.restart(changed, old)).stdout.trim() shouldBe "restarted"
            // ANTHROPIC_MODEL was removed from the profile, so it is gone from the session too.
            screenOf("secret", "args=[--continue] model=[] secret=[2]")
            everythingVisible("secret") shouldNotContain "s3cr3t"
        }

    @Test
    fun `a placeholder that can't be respawned takes its session down`() =
        runBlocking<Unit> {
            val real = shell.exec("command -v tmux").stdout.trim()
            val bin = Files.createDirectories(home.resolve("broken-bin"))
            val wrapper = bin.resolve("tmux").toFile()
            wrapper.writeText("#!/bin/sh\n[ \"\$1\" = respawn-pane ] && exit 1\nexec '$real' \"\$@\"\n")
            wrapper.setExecutable(true)
            val p = secretProfile(LaunchMode.SHELL_HOSTED)
            val result =
                LocalShell(
                    home,
                    extraPath = bin,
                ).runScript(TmuxScripts.launch(p, tmuxVersion = TmuxVersion(3, 1)))
            result.exitStatus shouldBe 1
            result.stdout shouldNotContain "created"
            shell.exec("tmux has-session -t '=secret'").exitStatus shouldNotBe 0
        }

    @Test
    fun `helper status is merged when enabled`() =
        runBlocking<Unit> {
            tmux.installHelper(installHooks = true)
            home.resolve(".claude/settings.json").toFile().readText() shouldContain "bridle-helper hook Notification"
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
    fun `shell integration installs, shows in the probe and uninstalls`() =
        runBlocking<Unit> {
            val bashrc = home.resolve(".bashrc")
            val share = home.resolve(".local/share/bridle")
            tmux.probe().shellIntegration shouldBe emptySet()
            // No rc file and a login shell that is neither bash nor zsh: nothing to pick.
            shouldThrow<RemoteCommandException> { tmux.installShellIntegration() }.message shouldContain
                "no ~/.bashrc or ~/.zshrc"

            bashrc.toFile().writeText("alias ll='ls -l'\n")
            tmux.installShellIntegration()

            tmux.probe().shellIntegration shouldBe setOf(IntegratedShell.BASH)
            share.resolve("shell-integration.bash").toFile().readText() shouldBe
                ShellIntegration.script(IntegratedShell.BASH)
            share.resolve("shell-integration.zsh").toFile().readText() shouldBe
                ShellIntegration.script(IntegratedShell.ZSH)
            PosixFilePermissions.toString(Files.getPosixFilePermissions(share.resolve("bridle-helper"))) shouldBe
                "rwx------"
            bashrc.toFile().readText() shouldContain ShellIntegration.BLOCK_BEGIN

            tmux.installShellIntegration(setOf(IntegratedShell.ZSH))
            tmux.probe().shellIntegration shouldBe setOf(IntegratedShell.BASH, IntegratedShell.ZSH)

            tmux.uninstallShellIntegration()
            tmux.probe().shellIntegration shouldBe emptySet()
            bashrc.toFile().readText() shouldBe "alias ll='ls -l'\n"
            Files.exists(share.resolve("shell-integration.bash")) shouldBe false
            Files.exists(share.resolve("bridle-helper")) shouldBe true
        }

    @Test
    fun `removing the helper also removes the shell integration`() =
        runBlocking<Unit> {
            home.resolve(".bashrc").toFile().writeText("x\n")
            tmux.installShellIntegration()
            tmux.probe().shellIntegration shouldBe setOf(IntegratedShell.BASH)

            tmux.uninstallHelper()

            tmux.probe().shellIntegration shouldBe emptySet()
            home.resolve(".bashrc").toFile().readText() shouldBe "x\n"
            // Without the helper, uninstalling just deletes any scripts left behind.
            Files.createDirectories(home.resolve(".local/share/bridle"))
            home.resolve(".local/share/bridle/shell-integration.zsh").toFile().writeText("leftover")
            tmux.uninstallShellIntegration()
            Files.exists(home.resolve(".local/share/bridle/shell-integration.zsh")) shouldBe false
        }

    @Test
    fun `hook install edits a symlinked settings file in place and quotes the helper path`() =
        runBlocking<Unit> {
            tmux.installHelper(installHooks = false)
            val dotfiles = Files.createDirectories(home.resolve("dotfiles"))
            val real = dotfiles.resolve("claude-settings.json")
            real.toFile().writeText("""{"model": "opus", "hooks": {"Stop": [{"hooks": [{"command": "say done"}]}]}}""")
            Files.createDirectories(home.resolve(".claude"))
            val link = Files.createSymbolicLink(home.resolve(".claude/settings.json"), real)
            val helper = home.resolve(".local/share/bridle/bridle-helper").toString()

            fun helper(vararg args: String): Pair<Int, String> {
                val pb = ProcessBuilder(listOf("/bin/sh", helper) + args)
                pb.environment()["HOME"] = home.toString()
                pb.environment()["BRIDLE_HELPER_PATH"] = "$home/it's here/bridle-helper"
                pb.environment().remove("CLAUDE_CONFIG_DIR")
                val p = pb.redirectErrorStream(true).start()
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                return p.waitFor() to out
            }
            helper("install-hooks").first shouldBe 0
            Files.isSymbolicLink(link) shouldBe true // still a link, the target was edited
            val text = real.toFile().readText()
            text shouldContain "say done"
            text shouldContain "\"opus\""
            // shlex-quoted: a quote in $HOME can't break the hook command Claude Code runs.
            text shouldContain """"'$home/it'\"'\"'s here/bridle-helper' hook Stop""""

            real.toFile().writeText("""{"hooks": ["not", "an", "object"]}""")
            val (status, out) = helper("install-hooks")
            status shouldNotBe 0
            out shouldContain "not modified"
            real.toFile().readText() shouldBe """{"hooks": ["not", "an", "object"]}"""
        }

    @Test
    fun `hook state directory is private`() =
        runBlocking<Unit> {
            tmux.installHelper(installHooks = false)
            val pb =
                ProcessBuilder("/bin/sh", home.resolve(".local/share/bridle/bridle-helper").toString(), "hook", "Stop")
            pb.environment()["HOME"] = home.toString()
            pb.environment().remove("XDG_STATE_HOME")
            pb.environment().remove("TMUX")
            val p = pb.start()
            p.outputStream.use { it.write("""{"session_id":"abc"}""".toByteArray()) }
            p.waitFor() shouldBe 0
            val dir = home.resolve(".local/state/bridle/sessions")
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe "rwx------"
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("abc.json"))) shouldBe "rw-------"
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
            shouldThrow<RemoteCommandException> { broken.installShellIntegration() }
            shouldThrow<RemoteCommandException> { broken.uninstallShellIntegration() }
            val odd = TmuxController(FixedResult(ExecResult(0, "???", "")))
            shouldThrow<RemoteCommandException> { odd.launch(profile()) }
            shouldThrow<RemoteCommandException> { odd.restart(profile()) }
            shouldThrow<RemoteCommandException> { odd.installHelper(false) }
            shouldThrow<RemoteCommandException> { odd.installShellIntegration() }
            shouldThrow<RemoteCommandException> { odd.uninstallShellIntegration() }
            odd.listSessions(useHelper = true).shouldBeEmpty()
        }
}
