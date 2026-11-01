package com.kitefoldlabs.bridle.core.ssh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.list
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
        ShellQuote.quote("k=v") shouldBe "k=v"
    }

    @Test
    fun `everything else is single quoted`() {
        ShellQuote.quote("") shouldBe "''"
        ShellQuote.quote("a b") shouldBe "'a b'"
        ShellQuote.quote("it's") shouldBe "'it'\\''s'"
        ShellQuote.quote("\$(rm -rf ~)") shouldBe "'\$(rm -rf ~)'"
        ShellQuote.quote("`id`;x|y&z>w") shouldBe "'`id`;x|y&z>w'"
        ShellQuote.quote("~") shouldBe "'~'"
        ShellQuote.quote("a\\b") shouldBe "'a'\\\\'b'"
        ShellQuote.quote("=ls") shouldBe "'=ls'" // zsh would expand =ls to /bin/ls
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

    /** The real test: POSIX shells must reproduce every input byte-for-byte. */
    @Test
    fun `sh round-trips arbitrary input`() =
        runBlocking<Unit> {
            checkAll(300, Arb.string(0..40, Codepoint.ascii())) { input ->
                val clean = input.replace("\u0000", "")
                shEcho(ShellQuote.quote(clean)) shouldBe clean
            }
            val tricky =
                listOf("'", "''", "\\'", "\\", "a\\", "\\\\", "\n", "a\nb", "\$HOME", "*", "!", "-n", "\t", "=x")
            for (value in tricky) {
                shEcho(ShellQuote.quote(value)) shouldBe value
                shEcho(ShellQuote.quote(value), shell = "bash") shouldBe value
            }
        }

    /**
     * fish (a supported login shell, and exec commands are parsed by the login shell) treats `\'` and
     * `\\` as escapes inside single quotes. With plain `'…'` quoting, a value ending in a backslash ended
     * the word early in fish and the next argument ran as code.
     */
    @Test
    fun `fish splits quoted commands into the original words`() =
        runBlocking<Unit> {
            val attack = listOf("echo", "x\\", "; touch pwned; echo ", "y")
            fishWords(ShellQuote.join(attack)) shouldBe attack
            checkAll(500, Arb.list(Arb.string(0..20, Codepoint.ascii()), 1..4)) { args ->
                val clean = args.map { it.replace("\u0000", "") }
                fishWords(ShellQuote.join(clean)) shouldBe clean
            }
        }

    /**
     * Splits [line] into words with fish's quoting rules: inside '…' only `\'` and `\\` are escapes;
     * outside quotes a backslash escapes the next character. Only the subset [ShellQuote] emits is
     * modelled; any other unquoted character fails the test.
     */
    private fun fishWords(line: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == ' ' -> {
                    if (inWord) words += word.toString().also { word.clear() }
                    inWord = false
                }
                c == '\\' -> {
                    i++
                    word.append(line[i])
                    inWord = true
                }
                c == '\'' -> {
                    inWord = true
                    i = fishSingleQuoted(line, i + 1, word)
                }
                else -> {
                    check(c.isLetterOrDigit() || c in "_@%+=:,./-") { "unexpected unquoted '$c' in $line" }
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) words += word.toString()
        return words
    }

    /** Appends the body of a fish '…' string starting at [start]; returns the index of the closing quote. */
    private fun fishSingleQuoted(
        line: String,
        start: Int,
        word: StringBuilder,
    ): Int {
        var i = start
        while (line[i] != '\'') {
            if (line[i] == '\\' && line[i + 1] in "\\'") i++
            word.append(line[i])
            i++
        }
        return i
    }

    private fun shEcho(
        quoted: String,
        shell: String = "/bin/sh",
    ): String {
        val process = ProcessBuilder(shell, "-c", "printf '%s' $quoted").redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        return out
    }
}
