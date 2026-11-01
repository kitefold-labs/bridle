package com.kitefoldlabs.bridle.feature.snippets

import com.kitefoldlabs.bridle.core.ssh.ExecResult
import io.kotest.matchers.shouldBe
import org.junit.Test

class ExecOutputTest {
    private fun shown(
        stdout: String,
        stderr: String = "",
    ) = ExecOutput.of(ExecResult(0, stdout, stderr))

    @Test
    fun `terminal escapes and control characters are removed`() {
        shown("\u001B[1;31merror\u001B[0m: disk full\r\n").text shouldBe "error: disk full\n"
        shown("\u001B]0;evil title\u0007ok").text shouldBe "ok"
        shown("\u001B]8;;https://example.com\u001B\\link\u001B]8;;\u001B\\").text shouldBe "link"
        shown("\u009B2Jclear\u001B(Bcharset\u001B").text shouldBe "clearcharset"
        shown("10%\r50%\r100%").text shouldBe "10%\n50%\n100%"
        shown("bell\u0007 back\bspace\tTab\u007F").text shouldBe "bell backspace\tTab"
        shown("out\n", "err\n").text shouldBe "out\nerr\n"
        shown("plain text, kept as is: ünïcödé ✓").text shouldBe "plain text, kept as is: ünïcödé ✓"
        shown("").omitted shouldBe 0
        ExecOutput.of(ExecResult(null, "", "")).exitStatus shouldBe null
    }

    @Test
    fun `long output keeps only its end`() {
        val max = ExecOutput.MAX_CHARS
        val stdoutOnly = shown("a".repeat(max) + "tail")
        stdoutOnly.omitted shouldBe 4
        stdoutOnly.text shouldBe "a".repeat(max - 4) + "tail"
        val both = shown("x".repeat(max), "end")
        both.omitted shouldBe 3
        both.text.length shouldBe max
        both.text.endsWith("end") shouldBe true
        val stderrOnly = shown("dropped", "e".repeat(max + 1))
        stderrOnly.omitted shouldBe 8
        stderrOnly.text shouldBe "e".repeat(max)
    }
}
