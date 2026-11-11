package com.kitefoldlabs.bridle.core.terminal

import com.kitefoldlabs.bridle.core.terminal.PromptLine.Snapshot
import com.kitefoldlabs.bridle.core.terminal.ShellEvent.CommandEnd
import com.kitefoldlabs.bridle.core.terminal.ShellEvent.CommandStart
import com.kitefoldlabs.bridle.core.terminal.ShellEvent.InputStart
import com.kitefoldlabs.bridle.core.terminal.ShellEvent.PromptStart
import com.kitefoldlabs.bridle.core.terminal.ShellEvent.WorkingDirectory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PromptLineTest {
    private val line = PromptLine()
    private val changes = mutableListOf<Snapshot>()

    init {
        line.onChange = { changes += it }
    }

    private fun type(text: String) = line.onInput(text.toByteArray(Charsets.UTF_8))

    private fun prompt(pane: String? = null) {
        line.onShellEvent(PromptStart(pane))
        line.onShellEvent(InputStart(pane))
    }

    private val text: String? get() = line.snapshot.text

    @Test
    fun `unknown until the first prompt`() {
        type("ls\r")
        text.shouldBeNull()
        line.onShellEvent(InputStart()) // a redraw says nothing about what was typed
        text.shouldBeNull()
        prompt()
        text shouldBe ""
    }

    @Test
    fun `typing, deleting and clearing are followed`() {
        prompt()
        type("cd wörk")
        text shouldBe "cd wörk"
        type("\u007f")
        text shouldBe "cd wör"
        type("\b\b")
        text shouldBe "cd w"
        type("😀\u007f") // one code point, two chars
        text shouldBe "cd w"
        type("\u000c") // Ctrl-L redraws
        line.onShellEvent(InputStart())
        text shouldBe "cd w"
        type("\u0015") // Ctrl-U
        text shouldBe ""
        type("\u007f") // nothing to delete
        text shouldBe ""
    }

    @Test
    fun `Ctrl-W deletes a word only where bash and zsh agree`() {
        prompt()
        type("cd ~/src/my-app.v2  ")
        type("\u0017")
        text shouldBe "cd "
        type("\u0017")
        text shouldBe ""
        type("echo a,b")
        type("\u0017") // zsh stops at ',' (not in WORDCHARS); bash doesn't
        text.shouldBeNull()
    }

    @Test
    fun `bracketed pastes of printable text are appended`() {
        prompt()
        type("cat ")
        type("\u001b[200~notes.txt\u001b[201~")
        text shouldBe "cat notes.txt"
        type("\u001b[200~two\rlines\u001b[201~")
        text.shouldBeNull()
    }

    @Test
    fun `other keys make the line unknown until the next prompt`() {
        for (key in listOf("\t", "\u001b[A", "\u001b[D", "\u0002o", "\u0012", "\u001b", "\u001b[200~open")) {
            prompt()
            type("ls")
            type(key)
            text.shouldBeNull()
        }
        line.onInput(byteArrayOf(0xC3.toByte())) // half a character
        prompt()
        type("x")
        line.onInput(byteArrayOf(0xC3.toByte()))
        text.shouldBeNull()
    }

    @Test
    fun `a command runs from Enter to the next prompt`() {
        prompt()
        type("ls\r")
        text.shouldBeNull()
        line.onShellEvent(CommandStart())
        type("y\r") // answering the command
        line.onShellEvent(CommandEnd(0))
        line.onShellEvent(WorkingDirectory("box", "/tmp")) // not about the line
        prompt()
        text shouldBe ""
    }

    @Test
    fun `typeahead makes the next prompt unknown`() {
        prompt()
        type("sleep 1\r")
        line.onShellEvent(CommandStart())
        type("ls") // the shell will show this at the next prompt
        line.onShellEvent(CommandEnd(0))
        prompt()
        text.shouldBeNull()
        type("\r") // submitted: nothing pending any more
        line.onShellEvent(CommandStart())
        line.onShellEvent(CommandEnd(0))
        prompt()
        text shouldBe ""

        type("x\u001b[A") // history recall at the prompt
        type("y") // still pending
        line.onShellEvent(CommandEnd(130)) // Ctrl-C from elsewhere
        prompt()
        text.shouldBeNull()
        type("\u0003")
        line.onShellEvent(CommandEnd(130))
        prompt()
        text shouldBe ""
    }

    @Test
    fun `terminal replies are not keystrokes`() {
        prompt()
        type("ab")
        for (reply in listOf(
            "\u001b[12;40R",
            "\u001b[?62;22c",
            "\u001b[>0;276;0c",
            "\u001b[?2004;1\$y",
            "\u001b[I",
            "\u001b[O",
            "\u001b[8;24;80t",
            "\u001b]11;rgb:0000/0000/0000\u001b\\",
            "\u001b]10;rgb:ffff/ffff/ffff\u0007",
            "\u001bP>|termlib 0.3\u001b\\",
        )) {
            type(reply)
        }
        text shouldBe "ab"
    }

    @Test
    fun `other panes are ignored while the line is known`() {
        prompt("%0")
        type("cd w")
        line.onShellEvent(CommandEnd(0, "%1"))
        prompt("%1") // a visible split finished a command
        line.onShellEvent(CommandStart("%1"))
        line.snapshot shouldBe Snapshot("cd w", "%0")

        type("\r")
        line.onShellEvent(CommandStart("%0"))
        prompt("%1") // %0's command is running: still %0's
        line.snapshot shouldBe Snapshot(null, "%0")
        line.onShellEvent(CommandEnd(0, "%0"))
        prompt("%0")
        line.snapshot shouldBe Snapshot("", "%0")
    }

    @Test
    fun `once unknown, the next pane to report takes over`() {
        prompt("%0")
        type("\u0002o") // tmux prefix, switch pane
        type("ls\r")
        line.onShellEvent(CommandStart("%1")) // the user ran something there
        line.snapshot shouldBe Snapshot(null, "%1")
        line.onShellEvent(CommandEnd(0, "%1"))
        prompt("%1")
        line.snapshot shouldBe Snapshot("", "%1")
    }

    @Test
    fun `changes are reported once each`() {
        prompt()
        type("a")
        type("\u001b[12;1R") // ignored: no change
        type("\t")
        type("b") // still unknown: no change
        changes shouldBe listOf(Snapshot(""), Snapshot("a"), Snapshot(null))
    }
}
