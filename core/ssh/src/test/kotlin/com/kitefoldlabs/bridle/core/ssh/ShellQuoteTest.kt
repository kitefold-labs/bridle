package com.kitefoldlabs.bridle.core.ssh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ShellQuoteTest {
    @Test
    fun `safe words are left alone`() {
        ShellQuote.quote("tmux") shouldBe "tmux"
        ShellQuote.quote("-t=main:0.1") shouldBe "-t=main:0.1"
        ShellQuote.quote("/usr/local/bin/claude") shouldBe "/usr/local/bin/claude"
    }

    @Test
    fun `everything else is single quoted`() {
        ShellQuote.quote("") shouldBe "''"
        ShellQuote.quote("a b") shouldBe "'a b'"
        ShellQuote.quote("it's") shouldBe "'it'\\''s'"
        ShellQuote.quote("\$(rm -rf ~)") shouldBe "'\$(rm -rf ~)'"
        ShellQuote.quote("`id`;x|y&z>w") shouldBe "'`id`;x|y&z>w'"
        ShellQuote.quote("~") shouldBe "'~'"
    }

    @Test
    fun `NUL is rejected`() {
        shouldThrow<IllegalArgumentException> { ShellQuote.quote("a\u0000b") }
    }

    @Test
    fun `join quotes each argument`() {
        ShellQuote.join("tmux", "new-session", "-c", "my dir") shouldBe "tmux new-session -c 'my dir'"
        ShellQuote.join(listOf("echo", "a'b")) shouldBe "echo 'a'\\''b'"
    }

    @Test
    fun `paths keep tilde expansion`() {
        ShellQuote.quotePath("~") shouldBe "~"
        ShellQuote.quotePath("~/") shouldBe "~/"
        ShellQuote.quotePath("~/src/my app") shouldBe "~/'src/my app'"
        ShellQuote.quotePath("~/src") shouldBe "~/src"
        ShellQuote.quotePath("/opt/x y") shouldBe "'/opt/x y'"
        ShellQuote.quotePath("~user/x") shouldBe "'~user/x'"
    }

    /** The real test: a POSIX shell must reproduce every input byte-for-byte. */
    @Test
    fun `sh round-trips arbitrary input`() =
        runBlocking<Unit> {
            checkAll(300, Arb.string(0..40, Codepoint.ascii())) { input ->
                val clean = input.replace("\u0000", "")
                shEcho(ShellQuote.quote(clean)) shouldBe clean
            }
            for (tricky in listOf("'", "''", "\\'", "\n", "a\nb", "\$HOME", "*", "!", "-n", "\t")) {
                shEcho(ShellQuote.quote(tricky)) shouldBe tricky
            }
        }

    private fun shEcho(quoted: String): String {
        val process = ProcessBuilder("/bin/sh", "-c", "printf '%s' $quoted").redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        return out
    }
}
