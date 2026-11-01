package com.kitefoldlabs.bridle.core.tmux

import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.ssh.ConnectionState
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration

/** Returns the same result for every call. */
class FixedResult(
    private val result: ExecResult,
) : SshConnection {
    override val state = MutableStateFlow(ConnectionState.CONNECTED)

    override suspend fun exec(
        command: String,
        timeout: Duration,
    ) = result

    override suspend fun runScript(
        script: String,
        timeout: Duration,
    ) = result

    override suspend fun openShell(
        pty: PtySpec,
        command: String?,
    ) = throw UnsupportedOperationException()

    override suspend fun openDirectTcpip(
        host: String,
        port: Int,
    ) = throw UnsupportedOperationException()

    override fun close() = Unit
}

class TmuxUnitTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `tmux versions`() {
        TmuxVersion.parse("tmux 3.4") shouldBe TmuxVersion(3, 4)
        TmuxVersion.parse("tmux 3.3a\n") shouldBe TmuxVersion(3, 3)
        TmuxVersion.parse("tmux next-3.6") shouldBe TmuxVersion(3, 6)
        (TmuxVersion.parse("tmux master")!! > TmuxVersion(9, 9)) shouldBe true
        TmuxVersion.parse("") shouldBe null
        TmuxVersion.parse("tmux weird") shouldBe null
        TmuxVersion.parse("screen 4.0") shouldBe null
        (TmuxVersion(2, 9) < TmuxVersion.MINIMUM) shouldBe true
        TmuxVersion(3, 1).toString() shouldBe "3.1"
        HostProbe(TmuxVersion(2, 8), null, null).tmuxSupported shouldBe false
        HostProbe(null, null, null).tmuxSupported shouldBe false
    }

    @Test
    fun `probe and list parsing tolerate garbage`() {
        val probe = TmuxParsers.parseProbe("tmux=tmux 3.4\nclaude=\nhelper=bridle-helper 1\nnoise\n")
        probe shouldBe HostProbe(TmuxVersion(3, 4), null, 1)
        TmuxParsers.parseProbe("helper=nope").helperVersion shouldBe null

        val t = TmuxScripts.FIELD_SEPARATOR
        val output =
            listOf(
                "main${t}100${t}1${t}200${t}2",
                "old${t}50${t}0${t}300${t}1",
                "bad${t}x${t}0${t}1${t}1",
                "short${t}1",
                "${t}1${t}1${t}1${t}1",
                TmuxScripts.PANES_MARKER,
                "main$t%1${t}1${t}1${t}0${t}node$t/home/n/src/a${t}b",
                "main$t%2${t}0${t}1${t}0${t}bash$t/home/n",
                "old$t%3${t}1${t}1${t}0${t}claude$t/srv",
                "bad${t}nopercent${t}1${t}1${t}0${t}x$t/",
                "",
            ).joinToString("\n")
        val (sessions, panes) = TmuxParsers.parseList(output)
        sessions.map { it.name } shouldContainExactly listOf("main", "old")
        panes.map { it.paneId } shouldContainExactly listOf("%1", "%2", "%3")
        panes[0].currentPath shouldBe "/home/n/src/a${t}b"
        TmuxParsers.parseList("main${t}1${t}0${t}1${t}1").second shouldBe emptyList()

        val idle = mapOf("old" to ClaudeStatus(ClaudeState.IDLE, StatusSource.HOOKS))
        val summary = TmuxParsers.summarize(sessions, panes, idle)
        summary.map { it.session.name } shouldContainExactly listOf("old", "main") // newest activity first
        summary[0].claudeRunning shouldBe true
        summary[0].claude!!.state shouldBe ClaudeState.IDLE
        summary[1].claudeRunning shouldBe false // node without hint
        TmuxParsers.summarize(sessions, panes) { it == "main" }[1].claudeRunning shouldBe true
        val dead = panes.map { it.copy(dead = true) }
        TmuxParsers.summarize(sessions, dead).none { it.claudeRunning } shouldBe true
        TmuxParsers.summarize(sessions, emptyList()).all { it.activePane == null } shouldBe true
    }

    @Test
    fun `claude screen heuristics`() {
        ClaudeScreen.classify("Bash(rm -rf build)\nDo you want to proceed?\n❯ 1. Yes\n  2. No").state shouldBe
            ClaudeState.WAITING_PERMISSION
        ClaudeScreen.classify("✻ Thinking… (12s · esc to interrupt)").state shouldBe ClaudeState.WORKING
        ClaudeScreen.classify("╭────╮\n│ >  │\n╰────╯").state shouldBe ClaudeState.WAITING_INPUT
        ClaudeScreen.classify("Overwrite file? (y/n)").state shouldBe ClaudeState.WAITING_PERMISSION
        ClaudeScreen.classify("").state shouldBe ClaudeState.UNKNOWN
        ClaudeScreen.classify("$ ls\nREADME.md").source shouldBe StatusSource.SCREEN
    }

    @Test
    fun `scripts validate their inputs`() {
        val p = LaunchProfile.claude(1, "~/src", "s")
        shouldThrow<IllegalArgumentException> { TmuxScripts.launch(p.copy(sessionName = "a;b")) }
        shouldThrow<IllegalArgumentException> { TmuxScripts.launch(p, columns = 80) }
        shouldThrow<IllegalArgumentException> { TmuxScripts.restart(p.copy(command = "")) }
        shouldThrow<IllegalArgumentException> { TmuxScripts.kill("x y") }
        shouldThrow<IllegalArgumentException> { TmuxScripts.capturePane("s", 0) }
        shouldThrow<IllegalArgumentException> { TmuxScripts.capturePane("s", 2_001) }
        shouldThrow<IllegalArgumentException> { TmuxScripts.capturePane("-bad") }
        shouldThrow<IllegalArgumentException> { TmuxScripts.sendText("s", "a\u0000", true) }
        shouldThrow<IllegalArgumentException> { TmuxScripts.sendText("s s", "a", true) }
        shouldThrow<IllegalArgumentException> { TmuxScripts.sendKeys("s", emptyList()) }
        shouldThrow<IllegalArgumentException> { TmuxScripts.sendKeys("s", listOf("C-c; rm")) }
        shouldThrow<IllegalArgumentException> { TmuxScripts.sendKeys("s s", listOf("y")) }
        shouldThrow<IllegalArgumentException> { TmuxScripts.attachCommand("s", "C:\\x") }
        shouldThrow<IllegalArgumentException> { TmuxScripts.attachCommand("s", "a\nb") }
        shouldThrow<IllegalArgumentException> { TmuxScripts.attachCommand("bad name") }
        TmuxScripts.sendKeys("s", listOf("Escape", "BTab", "C-M-x", "/")) shouldContain
            "send-keys -t '=s:' -- Escape BTab C-M-x /"
        TmuxScripts.sendText("s", "", enter = true) shouldContain "Enter"
        TmuxScripts.sendText("s", "hi", enter = false).contains("Enter") shouldBe false
    }

    @Test
    fun `attach command expands tilde and survives hostile directory names`() {
        val bin = Files.createDirectories(tmp.resolve("bin"))
        bin.resolve("tmux").writeText("#!/bin/sh\nprintf '%s|' \"\$@\"\n")
        bin.resolve("tmux").toFile().setExecutable(true)

        fun run(cmd: String): String {
            val pb = ProcessBuilder("/bin/sh", "-c", cmd)
            pb.environment()["HOME"] = "/home/me"
            pb.environment()["PATH"] = "$bin:/usr/bin:/bin"
            val p = pb.start()
            return p.inputStream
                .readBytes()
                .toString(Charsets.UTF_8)
                .also { p.waitFor() }
        }
        run(TmuxScripts.attachCommand("main")) shouldBe "new-session|-A|-s|main|-c|/home/me|"
        run(TmuxScripts.attachCommand("main", "~/src/it's \$(x)")) shouldBe
            "new-session|-A|-s|main|-c|/home/me/src/it's \$(x)|"
        run(TmuxScripts.attachCommand("w", "/srv/a b")) shouldBe "new-session|-A|-s|w|-c|/srv/a b|"
        // `~user` is not the current user's home: passed through rather than turned into /home/meuser.
        run(TmuxScripts.attachCommand("w", "~bob/x")) shouldBe "new-session|-A|-s|w|-c|~bob/x|"
        TmuxScripts.attachCommand("main") shouldStartWith "sh -c '"
    }

    @Test
    fun `a trailing semicolon can never end a tmux command`() {
        TmuxScripts.tmuxArg("ls;") shouldBe "ls\\;"
        TmuxScripts.tmuxArg("a\\;") shouldBe "a\\\\;"
        TmuxScripts.tmuxArg(";") shouldBe "\\;"
        TmuxScripts.tmuxArg("a;b") shouldBe "a;b"
        TmuxScripts.sendKeys("s", listOf(";")) shouldContain "-- ''\\\\';'"
    }

    @Test
    fun `helper bundle, install script and state parsing`() {
        HostHelper.script shouldContain "HELPER_VERSION=${HostHelper.PROTOCOL_VERSION}"
        HostHelper.sha256.length shouldBe 64
        HostHelper.sha256Hex(byteArrayOf()) shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        shouldThrow<IllegalArgumentException> {
            HostHelper.installScript("echo\nBRIDLE_HELPER_EOF_7f3a\n", installHooks = false)
        }
        HostHelper.installScript("echo hi", installHooks = true) shouldContain "install-hooks"
        HostHelper.listCommand() shouldContain "bridle-helper list"

        val states =
            HostHelper.parseStates(
                """
                {"v":1,"state":"working","tmux_session":"a","ts":1}
                {"v":1,"state":"waiting_permission","tmux_session":"a","message":"Allow?","ts":9}
                {"v":1,"state":"idle","tmux_session":"b"}
                {"v":1,"state":"exited","tmux_session":""}
                {broken json
                {"v":1,"tmux_session":"c"}
                not json
                """.trimIndent(),
            )
        states.keys shouldBe setOf("a", "b")
        states.getValue("a").state shouldBe ClaudeState.WAITING_PERMISSION
        states.getValue("a").message shouldBe "Allow?"
        states.getValue("b").updatedEpochSeconds shouldBe null
        val names = listOf("working", "waiting_permission", "waiting_input", "idle", "exited", "?")
        names.map(HostHelper::stateOf) shouldBe
            listOf(
                ClaudeState.WORKING,
                ClaudeState.WAITING_PERMISSION,
                ClaudeState.WAITING_INPUT,
                ClaudeState.IDLE,
                ClaudeState.EXITED,
                ClaudeState.UNKNOWN,
            )
    }

    @Test
    fun `helper hook writes state that list reports`() {
        Files.createDirectories(tmp.resolve("h"))

        fun helper(
            vararg args: String,
            stdin: String = "",
        ): Pair<Int, String> {
            val script = tmp.resolve("bridle-helper").also { it.writeText(HostHelper.script) }
            val pb = ProcessBuilder(listOf("/bin/sh", script.toString()) + args)
            pb.environment()["HOME"] = tmp.resolve("h").toString()
            pb.environment().remove("TMUX")
            pb.environment().remove("XDG_STATE_HOME")
            val p = pb.redirectErrorStream(true).start()
            p.outputStream.use { it.write(stdin.toByteArray()) }
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            return p.waitFor() to out
        }
        helper("version") shouldBe (0 to "bridle-helper 1\n")
        helper("list").first shouldBe 0
        helper("hook", "UserPromptSubmit", stdin = """{"session_id":"s-1","cwd":"/x \"y\""}""")
        val listed = HostHelper.parseStates(helper("list").second)
        listed shouldBe emptyMap() // not inside tmux → keyed by session_id, not a tmux session
        helper("list").second shouldContain "\"state\":\"working\""
        helper("list").second shouldContain "\"cwd\":\"/x \\\"y\\\"\""
        for (event in listOf("SessionStart", "PreToolUse", "Stop", "SessionEnd", "Weird")) {
            helper("hook", event, stdin = "{}").first shouldBe 0
        }
        helper("hook", "Notification", stdin = """{"message":"waiting for your input"}""")
        helper("list").second shouldContain "waiting_input"
        helper("prune", "x").first shouldBe 2
        helper("prune", "0").first shouldBe 0
        helper("hook").first shouldBe 2
        helper("bogus").first shouldBe 2
    }

    @Test
    fun `parser edge cases`() {
        val t = TmuxScripts.FIELD_SEPARATOR
        TmuxParsers.parsePane("$t%1${t}1${t}1${t}0${t}sh$t/") shouldBe null
        TmuxParsers.parsePane("s$t%1${t}1") shouldBe null
        TmuxParsers.parsePane("s$t%1${t}0${t}0${t}1${t}sh$t/")!!.dead shouldBe true
        TmuxParsers.parseSession("s${t}1${t}2${t}3${t}x") shouldBe null
        TmuxParsers.parseProbe("tmux=\nhelper=").tmuxVersion shouldBe null
        val states = HostHelper.parseStates("""{"v":1,"state":"idle","tmux_session":"a","message":"","ts":3}""")
        states.getValue("a").message shouldBe null
        HostHelper.parseStates("""{"v":1,"state":"idle","tmux_session":"a","ts":"oops"}""") shouldBe emptyMap()
    }

    @Test
    fun `controller treats a null exit status as failure`() =
        runBlocking<Unit> {
            val c = TmuxController(FixedResult(ExecResult(null, "", "signal")))
            shouldThrow<com.kitefoldlabs.bridle.core.ssh.RemoteCommandException> { c.sendText("s", "x") }
            val ok = TmuxController(FixedResult(ExecResult(0, "", "")))
            ok.sendText("s", "x", enter = false)
            ok.listSessions(useHelper = true) shouldBe emptyList()
        }
}
