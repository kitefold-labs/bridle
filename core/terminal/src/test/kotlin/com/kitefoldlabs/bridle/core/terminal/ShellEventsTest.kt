package com.kitefoldlabs.bridle.core.terminal

import com.kitefoldlabs.bridle.core.terminal.ShellEvent.CommandEnd
import com.kitefoldlabs.bridle.core.terminal.ShellEvent.CommandStart
import com.kitefoldlabs.bridle.core.terminal.ShellEvent.InputStart
import com.kitefoldlabs.bridle.core.terminal.ShellEvent.PromptStart
import com.kitefoldlabs.bridle.core.terminal.ShellEvent.WorkingDirectory
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ShellEventsTest {
    private fun parse(content: String) = ShellEvents.parseOsc(content)

    @Test
    fun `prompt marks parse with and without a pane`() {
        parse("133;A") shouldBe PromptStart()
        parse("133;B") shouldBe InputStart()
        parse("133;C") shouldBe CommandStart()
        parse("133;D") shouldBe CommandEnd()
        parse("133;D;1") shouldBe CommandEnd(status = 1)
        parse("133;A;aid=%0") shouldBe PromptStart("%0")
        parse("133;D;130;aid=%7") shouldBe CommandEnd(130, "%7")
    }

    @Test
    fun `other keys are ignored and odd values are dropped`() {
        parse("133;A;k=s;cl=m;aid=%3") shouldBe PromptStart("%3")
        parse("133;D;oops;aid=%1") shouldBe CommandEnd(null, "%1")
        parse("133;D;aid=%1") shouldBe CommandEnd(null, "%1") // no status, just the pane
        parse("133;B;aid=") shouldBe InputStart(null)
        parse("133;B;aid=%1 rm -rf") shouldBe InputStart(null)
        parse("133;B;aid=" + "9".repeat(33)) shouldBe InputStart(null)
    }

    @Test
    fun `unknown codes are not events`() {
        parse("133;E") shouldBe null
        parse("133;") shouldBe null
        parse("133") shouldBe null
        parse("0;title") shouldBe null
        parse("1337;CurrentDir=/tmp") shouldBe null
        parse("7;kitty-shell-cwd://host/tmp") shouldBe null
    }

    @Test
    fun `working directory is percent-decoded as UTF-8`() {
        parse("7;file://box/home/me") shouldBe WorkingDirectory("box", "/home/me")
        parse("7;file:///tmp") shouldBe WorkingDirectory("", "/tmp")
        parse("7;file://box/home/me/dir%20%C3%A9%25") shouldBe WorkingDirectory("box", "/home/me/dir é%")
        parse("7;file://box/") shouldBe WorkingDirectory("box", "/")
    }

    @Test
    fun `malformed or unsafe directories are dropped`() {
        parse("7;file://box") shouldBe null // no path
        parse("7;file://box/a%2") shouldBe null // truncated escape
        parse("7;file://box/a%zz") shouldBe null // not hex
        parse("7;file://box/a%C3") shouldBe null // invalid UTF-8
        parse("7;file://box/a%0Ab") shouldBe null // newline in the path
        parse("7;file://box/a%7F") shouldBe null // DEL
        parse("7;file://box/a%C2%9B") shouldBe null // C1 CSI
        parse("7;file://box/a b") shouldBe null // raw space: not a URL
        parse("7;file://box/é") shouldBe null // raw non-ASCII
    }

    @Test
    fun `parsing never throws`() =
        runBlocking {
            checkAll(Arb.string(0..64)) { s ->
                ShellEvents.parseOsc("133;$s")
                ShellEvents.parseOsc("7;file://$s")
            }
        }

    @Test
    fun `the prompt follows a shell session`() {
        val events =
            listOf(
                WorkingDirectory("box", "/home/me"),
                PromptStart(),
                InputStart(),
                CommandStart(),
                CommandEnd(1),
                WorkingDirectory("box", "/tmp"),
                PromptStart(),
                InputStart(),
                InputStart(), // Ctrl-L redraw
            )
        val states = events.runningFold(ShellPrompt(), ShellPrompt::after)

        states.map { it.atPrompt } shouldBe listOf(false, false, true, true, false, false, false, true, true, true)
        states[1] shouldBe ShellPrompt(pendingDirectory = "/home/me") // OSC 7 alone: no integration yet
        states[2].directory(null) shouldBe "/home/me"
        states[2].pendingDirectory shouldBe null
        states[6].directory(null) shouldBe "/home/me" // until the next prompt says whose /tmp is
        states.last() shouldBe
            ShellPrompt(active = true, atPrompt = true, pane = null, directories = mapOf(null to "/tmp"))
    }

    @Test
    fun `directories are kept per pane`() {
        val state =
            listOf(
                WorkingDirectory("box", "/a"),
                PromptStart("%0"),
                WorkingDirectory("box", "/b"),
                PromptStart("%1"),
                CommandStart("%0"),
                InputStart("%2"), // B never binds a directory
            ).fold(ShellPrompt(), ShellPrompt::after)

        state.directories shouldContainExactly mapOf("%0" to "/a", "%1" to "/b")
        state.pane shouldBe "%2"
        state.directory("%2") shouldBe null
    }

    @Test
    fun `remembered panes are capped, oldest first`() {
        var state = ShellPrompt()
        repeat(ShellPrompt.MAX_PANES + 2) { i ->
            state = state.after(WorkingDirectory("", "/d$i")).after(PromptStart("%$i"))
        }
        state = state.after(WorkingDirectory("", "/again")).after(PromptStart("%5"))

        state.directories.size shouldBe ShellPrompt.MAX_PANES
        state.directory("%0") shouldBe null
        state.directory("%1") shouldBe null
        state.directory("%2") shouldBe "/d2"
        state.directories.keys.last() shouldBe "%5"
        state.directory("%5") shouldBe "/again"
    }
}
