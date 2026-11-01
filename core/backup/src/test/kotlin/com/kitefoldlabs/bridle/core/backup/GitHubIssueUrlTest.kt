package com.kitefoldlabs.bridle.core.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URLDecoder

class GitHubIssueUrlTest {
    private val header =
        "Bridle crash report\nApp version: 1.0\nOS: Android 15\nDevice: Pixel\nTime: 1970-01-01T00:00:00Z"

    private fun report(trace: String) = CrashReport("java.lang.IllegalStateException", header, trace)

    private fun params(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

    private fun bigTrace(lines: Int) =
        (1..lines).joinToString("\n") { "\tat com.kitefoldlabs.bridle.App.method$it(App.kt:$it)" }

    @Test
    fun `small reports are sent whole`() {
        val r = report("java.lang.IllegalStateException: boom\n\tat a.B.c(B.kt:1)")
        val url = GitHubIssueUrl.build(report = r, userNotes = "Tapped connect & waited + more")
        url shouldStartWith
            "https://github.com/nkoenig/terminal/issues/new?template=crash_report.yml" +
            "&title=Crash%3A%20IllegalStateException&context="
        params(url) shouldBe
            mapOf(
                "template" to "crash_report.yml",
                "title" to "Crash: IllegalStateException",
                "context" to "Tapped connect & waited + more",
                "report" to r.text,
            )
    }

    @Test
    fun `long traces are trimmed at a line break with a marker`() {
        val r = report(bigTrace(400))
        val url = GitHubIssueUrl.build("kitefoldlabs/bridle", r, "notes")
        url.length shouldBeLessThanOrEqual GitHubIssueUrl.MAX_URL_LENGTH
        url shouldStartWith "https://github.com/kitefoldlabs/bridle/issues/new?"
        val body = params(url).getValue("report")
        body shouldStartWith header + "\n\n\tat com.kitefoldlabs.bridle.App.method1(App.kt:1)\n"
        body shouldEndWith ")\n[truncated]"
        body
            .lines()
            .dropLast(1)
            .drop(7)
            .forEach { it shouldStartWith "\tat " }
    }

    @Test
    fun `notes are capped, and trimmed when even an empty trace does not fit`() {
        val r = report(bigTrace(50))
        params(GitHubIssueUrl.build(report = r, userNotes = "n".repeat(5_000))).getValue("context").length shouldBe
            GitHubIssueUrl.MAX_NOTES_CHARS

        val url = GitHubIssueUrl.build(report = r, userNotes = "漢".repeat(2_000), maxLength = 2_000)
        url.length shouldBeLessThanOrEqual 2_000
        val p = params(url)
        p.getValue("report") shouldBe header + "\n\n" + CrashReport.TRUNCATED
        p.getValue("context").all { it == '漢' } shouldBe true
    }

    @Test
    fun `notes may be dropped entirely when only the minimal report fits`() {
        val minimal =
            "https://github.com/nkoenig/terminal/issues/new?template=crash_report.yml" +
                "&title=Crash%3A%20IllegalStateException&context=&report=" +
                GitHubIssueUrl.encode(header + "\n\n" + CrashReport.TRUNCATED)
        GitHubIssueUrl.build(report = report("y".repeat(5_000)), userNotes = "abc", maxLength = minimal.length) shouldBe
            minimal
    }

    @Test
    fun `cuts never split a surrogate pair`() {
        val key = "🔑"
        val r = report("x")
        for (max in 400..440) {
            val url = GitHubIssueUrl.build(report = r, userNotes = key.repeat(300), maxLength = max)
            val notes = params(url).getValue("context")
            (notes.length % 2) shouldBe 0
            notes.replace(key, "") shouldBe ""
        }
        val url = GitHubIssueUrl.build(report = report("a\n" + key.repeat(500)), maxLength = 700)
        val trace = params(url).getValue("report")
        trace shouldBe header + "\n\na\n" + CrashReport.TRUNCATED
    }

    @Test
    fun `rejects bad repos and impossible limits`() {
        listOf("", "owner", "owner/name/extra", "own er/x", "o/x?y", "../x").forEach {
            shouldThrow<IllegalArgumentException> { GitHubIssueUrl.build(it, report("x")) }
        }
        shouldThrow<IllegalArgumentException> { GitHubIssueUrl.build(report = report("x"), maxLength = 100) }
    }

    @Test
    fun `percent-encodes everything but unreserved characters`() {
        GitHubIssueUrl.encode("AZaz09-._~") shouldBe "AZaz09-._~"
        GitHubIssueUrl.encode(" +&=?#/%\n") shouldBe "%20%2B%26%3D%3F%23%2F%25%0A"
        GitHubIssueUrl.encode("é🔑") shouldBe "%C3%A9%F0%9F%94%91"
    }

    @Test
    fun `urls always fit and decode to the report or a marked prefix of it`() =
        runBlocking<Unit> {
            checkAll(60, Arb.int(0..600), Arb.string(0..300), Arb.int(1_500..8_000)) { lines, notes, max ->
                val r = report(bigTrace(lines))
                val url = GitHubIssueUrl.build(report = r, userNotes = notes, maxLength = max)
                url.length shouldBeLessThanOrEqual max
                val body = params(url).getValue("report")
                if (body != r.text) {
                    body shouldEndWith CrashReport.TRUNCATED
                    r.text shouldStartWith body.removeSuffix(CrashReport.TRUNCATED).removeSuffix("\n")
                }
            }
        }
}
