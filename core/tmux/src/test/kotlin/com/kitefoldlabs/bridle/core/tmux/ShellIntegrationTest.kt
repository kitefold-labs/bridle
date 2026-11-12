package com.kitefoldlabs.bridle.core.tmux

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.appendText
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `bridle-helper install-shell` / `uninstall-shell`, and the shell integration scripts running in
 * real interactive shells. Shells read commands from a pipe: they still run their prompt hooks
 * and print prompts (to stderr), so no pseudo-terminal is needed.
 */
class ShellIntegrationTest {
    @TempDir
    lateinit var tmp: Path

    private val home: Path get() = tmp.resolve("home")
    private val share: Path get() = home.resolve(".local/share/bridle")
    private val bashrc: Path get() = home.resolve(".bashrc")
    private val zshrc: Path get() = home.resolve(".zshrc")

    private data class Run(
        val exit: Int,
        val out: String,
    )

    @BeforeEach
    fun installFiles() {
        Files.createDirectories(share)
        share.resolve("bridle-helper").writeText(HostHelper.script)
        for (shell in listOf("bash", "zsh")) {
            share.resolve("shell-integration.$shell").writeText(HostHelper.resource("shell-integration.$shell"))
        }
    }

    private fun run(
        command: List<String>,
        stdin: String = "",
        env: Map<String, String> = emptyMap(),
    ): Run {
        val pb = ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", home.toString())
            put("SHELL", "/bin/sh")
            listOf("TMUX", "TMUX_PANE", "ZDOTDIR", "PROMPT_COMMAND", "TMPDIR").forEach(::remove)
            putAll(env)
        }
        val p = pb.start()
        p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "timed out: $command" }
        return Run(p.exitValue(), out)
    }

    private fun helper(
        vararg args: String,
        env: Map<String, String> = emptyMap(),
    ): Run = run(listOf("/bin/sh", share.resolve("bridle-helper").toString()) + args, env = env)

    private fun backups(): List<String> = home.listDirectoryEntries(".*.bridle-backup-*").map { it.name }

    @Test
    fun `install-shell appends one block at the end and is idempotent`() {
        bashrc.writeText("alias ll='ls -l'\n")

        helper("install-shell").let {
            it.exit shouldBe 0
            it.out shouldContain "BRIDLE_SHELL_CHANGED"
        }
        val installed = bashrc.readText()
        installed shouldStartWith "alias ll='ls -l'\n\n$BEGIN\n"
        installed shouldContain """. "${'$'}HOME/.local/share/bridle/shell-integration.bash""""
        installed shouldEndWith "$END\n"
        backups() shouldHaveSize 1
        home.resolve(backups().single()).readText() shouldBe "alias ll='ls -l'\n"
        zshrc.exists() shouldBe false // neither present nor the login shell

        helper("install-shell").out shouldContain "BRIDLE_SHELL_UNCHANGED"
        bashrc.readText() shouldBe installed
        backups() shouldHaveSize 1
    }

    @Test
    fun `install-shell moves the block back to the end`() {
        bashrc.writeText("a\n")
        helper("install-shell")
        bashrc.appendText("export LATER=1\n")

        helper("install-shell").out shouldContain "BRIDLE_SHELL_CHANGED"

        val text = bashrc.readText()
        text shouldStartWith "a\nexport LATER=1\n\n$BEGIN\n"
        text shouldEndWith "$END\n"
        text.lines().count { it == BEGIN } shouldBe 1
    }

    @Test
    fun `install-shell picks existing rc files and the login shell`() {
        helper("install-shell").let {
            it.exit shouldBe 2
            it.out shouldContain "no ~/.bashrc or ~/.zshrc"
        }

        helper("install-shell", env = mapOf("SHELL" to "/usr/bin/zsh")).exit shouldBe 0
        zshrc.readText() shouldContain "shell-integration.zsh"
        bashrc.exists() shouldBe false

        helper("install-shell", "bash").exit shouldBe 0
        bashrc.readText() shouldContain "shell-integration.bash"

        val zdotdir = Files.createDirectories(home.resolve("zdot"))
        helper("install-shell", "zsh", env = mapOf("ZDOTDIR" to zdotdir.toString())).exit shouldBe 0
        zdotdir.resolve(".zshrc").readText() shouldContain "shell-integration.zsh"
    }

    @Test
    fun `install-shell dry run prints the result without writing`() {
        bashrc.writeText("a\n")

        val dry = helper("install-shell", "--dry-run")

        dry.exit shouldBe 0
        dry.out shouldContain "==> $bashrc\na\n\n$BEGIN\n"
        dry.out shouldNotContain "BRIDLE_SHELL_"
        bashrc.readText() shouldBe "a\n"
        backups() shouldHaveSize 0
    }

    @Test
    fun `a symlinked rc file stays a symlink`() {
        val target = Files.createDirectories(home.resolve("dotfiles")).resolve("bashrc")
        target.writeText("a\n")
        Files.createSymbolicLink(bashrc, target)

        helper("install-shell").exit shouldBe 0

        bashrc.isSymbolicLink() shouldBe true
        target.readText() shouldContain BEGIN
    }

    @Test
    fun `uninstall-shell restores the rc files and removes the scripts`() {
        bashrc.writeText("a\n\nb\n")
        zshrc.writeText("z\n")
        helper("install-shell", "bash", "zsh").exit shouldBe 0

        helper("uninstall-shell").let {
            it.exit shouldBe 0
            it.out shouldContain "BRIDLE_SHELL_CHANGED"
        }

        bashrc.readText() shouldBe "a\n\nb\n"
        zshrc.readText() shouldBe "z\n"
        share.resolve("shell-integration.bash").exists() shouldBe false
        share.resolve("shell-integration.zsh").exists() shouldBe false
        share.resolve("bridle-helper").exists() shouldBe true
        helper("uninstall-shell").out shouldContain "BRIDLE_SHELL_UNCHANGED"
    }

    @Test
    fun `a damaged block is refused and left alone`() {
        for (damaged in listOf("x\n$BEGIN\ny\n", "x\n$END\ny\n", "$BEGIN\n$BEGIN\n$END\n")) {
            bashrc.writeText(damaged)

            helper("install-shell", "bash").let {
                it.exit shouldBe 2
                it.out shouldContain "is damaged"
            }
            helper("uninstall-shell").exit shouldBe 2

            bashrc.readText() shouldBe damaged
        }
        backups() shouldHaveSize 0
    }

    @Test
    fun `install-shell rejects unknown shells and missing scripts`() {
        helper("install-shell", "fish").let {
            it.exit shouldBe 2
            it.out shouldContain "unknown argument: fish"
        }
        share.resolve("shell-integration.zsh").deleteExisting()
        helper("install-shell", "zsh").let {
            it.exit shouldBe 2
            it.out shouldContain "shell-integration.zsh is missing"
        }
        zshrc.exists() shouldBe false
    }

    @Test
    fun `bash marks prompts and reports the directory`() {
        assumeTrue(onPath("bash"), "bash is not installed")
        bashrc.writeText("PS1='$ '\nPROMPT_COMMAND='echo \"user saw \$?\"'\n")
        helper("install-shell", "bash").exit shouldBe 0

        val run = interactive(listOf("bash", "--rcfile", bashrc.toString(), "-i"))

        markers(run.out) shouldContainExactly expectedMarkers()
        run.out shouldContain "user saw 1" // the user's PROMPT_COMMAND still sees the real $?
    }

    @Test
    fun `bash wraps the codes for tmux passthrough`() {
        assumeTrue(onPath("bash"), "bash is not installed")
        bashrc.writeText("PS1='$ '\n")
        helper("install-shell", "bash").exit shouldBe 0

        val run = interactive(listOf("bash", "--rcfile", bashrc.toString(), "-i"), tmux = true)

        run.out shouldContain passthrough("133;A;aid=%7")
        run.out shouldContain passthrough("133;B;aid=%7") // from PS1: its backslash survived
        run.out shouldContain passthrough("133;C;aid=%7") // from PS0
        run.out shouldContain passthrough("133;D;1;aid=%7")
        run.out shouldNotContain "\\]"
    }

    @Test
    fun `zsh marks prompts and reports the directory`() {
        assumeTrue(onPath("zsh"), "zsh is not installed")
        zshrc.writeText("PROMPT='%# '\nuser_hook() { echo \"user saw \$?\" }\nprecmd_functions+=(user_hook)\n")
        helper("install-shell", "zsh").exit shouldBe 0

        val run = interactive(listOf("zsh", "-i"))

        markers(run.out) shouldContainExactly expectedMarkers()
        run.out shouldContain "user saw 1"
    }

    @Test
    fun `zsh wraps the codes for tmux passthrough`() {
        assumeTrue(onPath("zsh"), "zsh is not installed")
        zshrc.writeText("PROMPT='%# '\n")
        helper("install-shell", "zsh").exit shouldBe 0

        val run = interactive(listOf("zsh", "-i"), tmux = true)

        run.out shouldContain passthrough("133;A;aid=%7")
        run.out shouldContain passthrough("133;B;aid=%7") // from PROMPT: its % survived
        run.out shouldContain passthrough("133;C;aid=%7")
        run.out shouldContain passthrough("133;D;1;aid=%7")
    }

    /** Runs `false`, changes into a directory that needs percent-encoding, and exits. */
    private fun interactive(
        command: List<String>,
        tmux: Boolean = false,
    ): Run {
        Files.createDirectories(home.resolve(ENCODED_DIR))
        // A TMUX value with no server behind it: the script's `tmux set` fails quietly.
        val env =
            if (tmux) {
                mapOf("TMUX" to "${tmp.resolve("no-server")},1,0", "TMUX_PANE" to "%7")
            } else {
                emptyMap()
            }
        return run(command, stdin = "false\ncd '$ENCODED_DIR'\nexit\n", env = env)
    }

    /** Each prompt reports the directory, then A and B; typing a command sends C, finishing it D. */
    private fun expectedMarkers(): List<String> {
        val cwd = "7;file://HOST$home"

        fun prompt(dir: String) = listOf(dir, "133;A", "133;B", "133;C")
        // The commands are `false`, `cd`, `exit`.
        return prompt(cwd) + "133;D;1" + prompt(cwd) + "133;D;0" + prompt("$cwd/dir%20%C3%A9%25")
    }

    private fun markers(output: String): List<String> =
        Regex("\u001b\\]((?:133|7);[^\u0007]*)\u0007")
            .findAll(output)
            .map { it.groupValues[1].replace(Regex("^7;file://[^/]*"), "7;file://HOST") }
            .toList()

    private fun passthrough(body: String) = "\u001bPtmux;\u001b\u001b]$body\u0007\u001b\\"

    private fun onPath(program: String): Boolean =
        System
            .getenv("PATH")
            .orEmpty()
            .split(':')
            .any { Files.isExecutable(Path.of(it, program)) }

    private companion object {
        const val BEGIN = "# >>> bridle shell integration >>>"
        const val END = "# <<< bridle shell integration <<<"
        const val ENCODED_DIR = "dir é%"
        const val TIMEOUT_SECONDS = 30L
    }
}
