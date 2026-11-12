package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.terminal.ShellEvent
import com.kitefoldlabs.bridle.core.terminal.ShellPrompt
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.random.Random

class TerminalModeTrackerTest {
    private val tracker = TerminalModeTracker()

    private fun feed(text: String) {
        tracker.feed(text.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `starts with every mode off and no title`() {
        tracker.isBracketedPasteEnabled shouldBe false
        tracker.isApplicationCursorKeys shouldBe false
        tracker.title.value shouldBe ""
    }

    @Test
    fun `follows bracketed paste and application cursor keys`() {
        feed("\u001b[?2004h")
        tracker.isBracketedPasteEnabled shouldBe true
        feed("\u001b[?1h")
        tracker.isApplicationCursorKeys shouldBe true
        feed("\u001b[?2004l\u001b[?1l")
        tracker.isBracketedPasteEnabled shouldBe false
        tracker.isApplicationCursorKeys shouldBe false
    }

    @Test
    fun `handles several modes in one sequence and ignores others`() {
        feed("\u001b[?1049;1;25;2004h")
        tracker.isBracketedPasteEnabled shouldBe true
        tracker.isApplicationCursorKeys shouldBe true
    }

    @Test
    fun `sequences split across chunks are tracked`() {
        val bytes = "text\u001b[?2004h more".toByteArray()
        bytes.forEachIndexed { i, _ -> tracker.feed(bytes, i, 1) }
        tracker.isBracketedPasteEnabled shouldBe true
    }

    @Test
    fun `offset and length select the bytes fed`() {
        val bytes = "xx\u001b[?2004hyy\u001b[?1h".toByteArray()
        tracker.feed(bytes, 2, 8)
        tracker.isBracketedPasteEnabled shouldBe true
        tracker.isApplicationCursorKeys shouldBe false
    }

    @Test
    fun `non-private and malformed sequences do not change modes`() {
        feed("\u001b[2004h") // ANSI mode, not DEC private
        feed("\u001b[>2004h") // other private marker
        feed("\u001b[2004?h") // marker after parameters
        feed("\u001b[??2004h") // repeated marker
        feed("\u001b[1;?2004h") // marker after a parameter
        feed("\u001b[?2004\$h") // intermediate byte
        feed("\u001b[?2004m")
        tracker.isBracketedPasteEnabled shouldBe false
    }

    @Test
    fun `empty and colon parameters are accepted`() {
        feed("\u001b[?;2004h")
        tracker.isBracketedPasteEnabled shouldBe true
        feed("\u001b[?1:2h")
        tracker.isApplicationCursorKeys shouldBe true
    }

    @Test
    fun `huge parameters saturate instead of overflowing`() {
        feed("\u001b[?99999999999999999999h")
        tracker.isBracketedPasteEnabled shouldBe false
    }

    @Test
    fun `parameters past the limit are dropped`() {
        val many = List(20) { "0" }.joinToString(";")
        feed("\u001b[?$many;2004h")
        tracker.isBracketedPasteEnabled shouldBe false
        feed("\u001b[?2004;$many;1h")
        tracker.isBracketedPasteEnabled shouldBe true
        tracker.isApplicationCursorKeys shouldBe false
    }

    @Test
    fun `controls inside a CSI are executed and DEL is ignored`() {
        feed("\u001b[?20\r\n\u007f04h")
        tracker.isBracketedPasteEnabled shouldBe true
    }

    @Test
    fun `CAN and SUB abort a sequence`() {
        feed("\u001b[?2004\u0018h")
        feed("\u001b[?2004\u001ah")
        tracker.isBracketedPasteEnabled shouldBe false
    }

    @Test
    fun `ESC inside a CSI starts a new sequence`() {
        feed("\u001b[?20\u001b[?2004h")
        tracker.isBracketedPasteEnabled shouldBe true
    }

    @Test
    fun `RIS and DECSTR reset both modes`() {
        feed("\u001b[?2004h\u001b[?1h\u001bc")
        tracker.isBracketedPasteEnabled shouldBe false
        tracker.isApplicationCursorKeys shouldBe false
        feed("\u001b[?2004h\u001b[?1h\u001b[!p")
        tracker.isBracketedPasteEnabled shouldBe false
        tracker.isApplicationCursorKeys shouldBe false
    }

    @Test
    fun `sequences that only look like DECSTR do not reset`() {
        feed("\u001b[?2004h\u001b[!q\u001b[?!p\u001b[p")
        tracker.isBracketedPasteEnabled shouldBe true
    }

    @Test
    fun `other escape sequences are skipped`() {
        feed("\u001b(B\u001b(c\u001b7\u001b\u001b\r[?2004h\u001b8")
        // ESC ( c is a charset, not RIS; ESC ESC restarts; a C0 inside an escape is executed.
        tracker.isBracketedPasteEnabled shouldBe true
        feed("\u001b[?1h\u001b#c")
        tracker.isApplicationCursorKeys shouldBe true
    }

    @Test
    fun `OSC 0 and 2 set the title, terminated by BEL or ST`() {
        feed("\u001b]0;first\u0007")
        tracker.title.value shouldBe "first"
        feed("\u001b]2;second\u001b\\")
        tracker.title.value shouldBe "second"
    }

    @Test
    fun `other OSC commands and malformed OSC leave the title`() {
        feed("\u001b]2;kept\u0007")
        feed("\u001b]1;icon\u0007")
        feed("\u001b]8;;https://example.com\u0007")
        feed("\u001b]no separator\u0007")
        tracker.title.value shouldBe "kept"
    }

    @Test
    fun `titles are UTF-8, stripped of controls and capped`() {
        feed("\u001b]2;café ☕\u0001\u0007")
        tracker.title.value shouldBe "café ☕"
        val long = "x".repeat(TerminalModeTracker.MAX_TITLE_LENGTH + 10)
        feed("\u001b]2;$long\u0007")
        tracker.title.value shouldBe "x".repeat(TerminalModeTracker.MAX_TITLE_LENGTH)
    }

    @Test
    fun `titles drop bidirectional formatting characters`() {
        feed("\u001b]0;\u202Eevil\u202C txt\u2066.exe\u2069\u200E\u200F\u061C\u0007")
        tracker.title.value shouldBe "evil txt.exe"
        feed("\u001b]0;\u202Am\u202Bi\u202Dx\u2067e\u2068d\u0007")
        tracker.title.value shouldBe "mixed"
        feed("\u001b]0;\u05E9\u05DC\u05D5\u05DD \u200D\u0007") // Hebrew text and ZWJ are kept
        tracker.title.value shouldBe "\u05E9\u05DC\u05D5\u05DD \u200D"
    }

    @Test
    fun `arbitrary output never breaks the tracker`() {
        val random = Random(2004)
        repeat(200) {
            val bytes = ByteArray(random.nextInt(1, 4096)).also(random::nextBytes)
            tracker.feed(bytes)
            tracker.title.value.length shouldBeLessThanOrEqual TerminalModeTracker.MAX_TITLE_LENGTH
        }
        // CAN aborts whatever sequence the garbage left open; tracking resumes.
        feed("\u0018\u001b[?2004h\u001b[?1l")
        tracker.isBracketedPasteEnabled shouldBe true
        tracker.isApplicationCursorKeys shouldBe false
    }

    @Test
    fun `a cut title never ends in half a surrogate pair`() {
        val title = "x".repeat(TerminalModeTracker.MAX_TITLE_LENGTH - 1) + "😀" + "tail"
        feed("\u001b]2;$title\u0007")
        tracker.title.value shouldBe "x".repeat(TerminalModeTracker.MAX_TITLE_LENGTH - 1)
    }

    @Test
    fun `oversized OSC strings are skipped`() {
        feed("\u001b]2;kept\u0007")
        feed("\u001b]2;" + "y".repeat(TerminalModeTracker.MAX_OSC_LENGTH) + "\u0007")
        tracker.title.value shouldBe "kept"
        feed("\u001b[?2004h")
        tracker.isBracketedPasteEnabled shouldBe true
    }

    @Test
    fun `an ESC that is not ST abandons the OSC and starts a sequence`() {
        feed("\u001b]2;lost\u001b[?2004h")
        tracker.title.value shouldBe ""
        tracker.isBracketedPasteEnabled shouldBe true
    }

    @Test
    fun `DCS, SOS, PM and APC strings are skipped, including tmux passthrough`() {
        feed("\u001bPtmux;\u001b\u001b[?2004h\u001b\\")
        feed("\u001bX\u001b[?2004h\u001b\\")
        feed("\u001b^\u001b[?2004h\u001b\\")
        feed("\u001b_\u001b]2;nope\u0007\u001b\\")
        tracker.isBracketedPasteEnabled shouldBe false
        tracker.title.value shouldBe ""
        feed("\u001b[?2004h")
        tracker.isBracketedPasteEnabled shouldBe true
    }

    @Test
    fun `shell integration codes update the prompt and reach the listener in order`() {
        val seen = mutableListOf<Pair<ShellEvent, ShellPrompt>>()
        tracker.onShellEvent = { seen += it to tracker.shellPrompt.value }
        // What tmux forwards for one bash prompt: passthrough already unwrapped, each code
        // followed by ESC ( B. Fed a byte at a time to cover codes split across reads.
        val bytes =
            (
                "\u001b]133;D;1;aid=%0\u0007\u001b(B\u001b]7;file://box/tmp/a%20b\u0007\u001b(B" +
                    "\u001b]133;A;aid=%0\u0007\u001b(B\u001b]133;B;aid=%0\u0007\u001b(Bme@box:/tmp/a b\$ "
            ).toByteArray()
        bytes.indices.forEach { tracker.feed(bytes, it, 1) }

        seen.map { it.first } shouldBe
            listOf(
                ShellEvent.CommandEnd(1, "%0"),
                ShellEvent.WorkingDirectory("box", "/tmp/a b"),
                ShellEvent.PromptStart("%0"),
                ShellEvent.InputStart("%0"),
            )
        seen.last().second shouldBe tracker.shellPrompt.value // updated before the listener runs
        tracker.shellPrompt.value.atPrompt shouldBe true
        tracker.shellPrompt.value.directory("%0") shouldBe "/tmp/a b"
    }

    @Test
    fun `shell integration codes end with ST too and leave the title alone`() {
        feed("\u001b]2;kept\u0007\u001b]133;C\u001b\\\u001b]133;Z\u0007\u001b]7;file://box/bad%zz\u0007")
        tracker.shellPrompt.value shouldBe ShellPrompt(active = true, atPrompt = false)
        tracker.title.value shouldBe "kept"
        feed("\u001bc") // RIS resets modes, not what the shell reported
        tracker.shellPrompt.value.active shouldBe true
    }
}
