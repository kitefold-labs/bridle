package com.kitefoldlabs.bridle.core.terminal

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class BracketedPasteTest {
    private val start = BracketedPaste.START
    private val end = BracketedPaste.END

    private fun encode(
        text: String,
        bracketed: Boolean = true,
        enter: Boolean = false,
    ) = String(BracketedPaste.encode(text, bracketed, enter), Charsets.UTF_8)

    /** Text built from fragments chosen to provoke the sanitiser, mixed with arbitrary chars. */
    private val hostile: Arb<String> =
        Arb.choice(
            Arb
                .list(
                    Arb.element(
                        end,
                        start,
                        "\u001b",
                        "[201~",
                        "\u009b201~",
                        "\r",
                        "\n",
                        "\r\n",
                        "\u0000",
                        "\t",
                        "\u007f",
                        "a",
                        "é",
                        "😀",
                    ),
                    0..30,
                ).map { it.joinToString("") },
            Arb.list(Arb.int(0..0x2ff), 0..60).map { cps -> cps.joinToString("") { it.toChar().toString() } },
        )

    @Test
    fun `wraps text in paste markers when the mode is on`() {
        encode("hello") shouldBe "${start}hello$end"
        encode("hello", bracketed = false) shouldBe "hello"
        encode("") shouldBe start + end
    }

    @Test
    fun `send enter appends CR after the end marker`() {
        encode("fix the bug", enter = true) shouldBe "${start}fix the bug$end\r"
        encode("ls", bracketed = false, enter = true) shouldBe "ls\r"
    }

    @Test
    fun `newlines become CR`() {
        BracketedPaste.sanitize("a\r\nb\nc\rd") shouldBe "a\rb\rc\rd"
        BracketedPaste.sanitize("\n\n") shouldBe "\r\r"
        BracketedPaste.sanitize("end\r") shouldBe "end\r"
        BracketedPaste.sanitize("\r\r\n") shouldBe "\r\r"
    }

    @Test
    fun `embedded paste markers are removed`() {
        BracketedPaste.sanitize("safe${end}rm -rf ~\n") shouldBe "saferm -rf ~\r"
        BracketedPaste.sanitize("${start}nested$end") shouldBe "nested"
        // Removing the inner marker must not leave a working outer one behind.
        BracketedPaste.sanitize("\u001b[20\u001b[201~1~") shouldBe "[201~"
    }

    @Test
    fun `control characters are stripped except tab and CR`() {
        BracketedPaste.sanitize("a\u0000b\u0007c\u001bd\te\u007ff\u009bg\u0085h") shouldBe "abcd\tefgh"
    }

    @Test
    fun `printable unicode is kept`() {
        val text = "héllo 世界 😀 \"quotes\" `tick` ~ | \\"
        BracketedPaste.sanitize(text) shouldBe text
        BracketedPaste.encode(text, bracketedPasteMode = false) shouldBe text.toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `multi-line paste without the mode needs confirmation`() {
        BracketedPaste.needsConfirmation("one\ntwo", bracketedPasteMode = false) shouldBe true
        BracketedPaste.needsConfirmation("one line", bracketedPasteMode = false) shouldBe false
        BracketedPaste.needsConfirmation("one\ntwo", bracketedPasteMode = true) shouldBe false
    }

    @Test
    fun `sanitised payload never contains a paste terminator or control bytes`() =
        runBlocking<Unit> {
            checkAll(hostile) { text ->
                val out = encode(text)
                out shouldStartWith start
                out shouldEndWith end
                out.indexOf(end) shouldBe out.length - end.length
                val payload = out.removePrefix(start).removeSuffix(end)
                payload shouldNotContain "\u001b"
                payload shouldNotContain "\n"
                payload.none { isTerminalControl(it) && it != '\t' && it != '\r' } shouldBe true
            }
        }

    @Test
    fun `sanitising is idempotent`() =
        runBlocking<Unit> {
            checkAll(hostile) { text ->
                val once = BracketedPaste.sanitize(text)
                BracketedPaste.sanitize(once) shouldBe once
            }
        }
}
