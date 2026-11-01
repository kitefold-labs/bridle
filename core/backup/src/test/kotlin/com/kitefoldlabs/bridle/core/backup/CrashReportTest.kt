package com.kitefoldlabs.bridle.core.backup

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.IOException

class CrashReportTest {
    private fun report(
        throwable: Throwable,
        sensitive: Collection<String> = emptyList(),
    ) = CrashReport.from(throwable, "1.2.0 (42)", "Android 15 (API 35)", "Pixel 8", 0, sensitive)

    private fun app(
        method: String,
        line: Int = 10,
    ) = StackTraceElement("com.kitefoldlabs.bridle.App", method, "App.kt", line)

    private fun fw(
        i: Int,
        cls: String = "android.os.Handler",
    ) = StackTraceElement(cls, "m$i", "Handler.java", i)

    private fun Throwable.withFrames(vararg frames: StackTraceElement) = apply { stackTrace = arrayOf(*frames) }

    @Test
    fun `header carries versions and time, and nothing else`() {
        val r = report(IllegalStateException("boom").withFrames(app("run")))
        r.header shouldBe
            """
            Bridle crash report
            App version: 1.2.0 (42)
            OS: Android 15 (API 35)
            Device: Pixel 8
            Time: 1970-01-01T00:00:00Z
            """.trimIndent()
        r.trace shouldBe "java.lang.IllegalStateException: boom\n\tat com.kitefoldlabs.bridle.App.run(App.kt:10)"
        r.text shouldBe r.header + "\n\n" + r.trace
        r.toString() shouldBe r.text
        r.title shouldBe "Crash: IllegalStateException"
        r.exceptionType shouldBe "java.lang.IllegalStateException"
    }

    @Test
    fun `header fields are cleaned and capped`() {
        val r = CrashReport.from(RuntimeException(), "1\u0000.0", " Android\r\n15 ", "x".repeat(300), 1_000)
        r.header shouldContain "App version: 1 .0\n"
        r.header shouldContain "OS: Android  15\n"
        r.header shouldContain "Device: ${"x".repeat(100)}\n"
        r.header shouldEndWith "Time: 1970-01-01T00:00:01Z"
    }

    @Test
    fun `messages are scrubbed and causes included`() {
        val root =
            IOException("connect to 10.1.2.3 as nate@devbox: /home/nate/.ssh/id_ed25519")
                .withFrames(fw(1), app("io", 3))
        val top =
            IllegalStateException("session on devbox.tail1234.ts.net failed for prod-api", root)
                .withFrames(app("connect"), app("io", 3))
        val r = report(top, sensitive = listOf("prod-api"))
        r.trace shouldBe
            """
            java.lang.IllegalStateException: session on <host> failed for <redacted>
            ${"\t"}at com.kitefoldlabs.bridle.App.connect(App.kt:10)
            ${"\t"}at com.kitefoldlabs.bridle.App.io(App.kt:3)
            Caused by: java.io.IOException: connect to <ip> as <user>@<host>: <path>
            ${"\t"}at android.os.Handler.m1(Handler.java:1)
            ${"\t"}... 1 more
            """.trimIndent()
        listOf("10.1.2.3", "nate", "devbox", "prod-api", "/home").forEach { r.text shouldNotContain it }
    }

    @Test
    fun `null messages and odd frames are rendered safely`() {
        val r =
            report(
                RuntimeException().withFrames(
                    StackTraceElement("java.lang.Thread", "sleep", null, -2),
                    StackTraceElement("com.kitefoldlabs.X", "a", null, 4),
                    StackTraceElement("com.kitefoldlabs.X", "b", "X.kt", -1),
                    StackTraceElement("com.kitefoldlabs.X", "c", "../../home/nate/evil file.kt", 5),
                ),
            )
        r.trace shouldBe
            """
            java.lang.RuntimeException
            ${"\t"}at java.lang.Thread.sleep(Native Method)
            ${"\t"}at com.kitefoldlabs.X.a(Unknown Source)
            ${"\t"}at com.kitefoldlabs.X.b(X.kt)
            ${"\t"}at com.kitefoldlabs.X.c(Unknown Source)
            """.trimIndent()
    }

    @Test
    fun `long runs of framework frames are elided, app frames kept`() {
        val frames = (1..10).map { fw(it) } + app("main") + (11..16).map { fw(it) }
        val r = report(Error("x").withFrames(*frames.toTypedArray()))
        r.trace.lines() shouldBe
            listOf(
                "java.lang.Error: x",
                "\tat android.os.Handler.m1(Handler.java:1)",
                "\tat android.os.Handler.m2(Handler.java:2)",
                "\tat android.os.Handler.m3(Handler.java:3)",
                "\t... 6 framework frames",
                "\tat android.os.Handler.m10(Handler.java:10)",
                "\tat com.kitefoldlabs.bridle.App.main(App.kt:10)",
            ) + (11..16).map { "\tat android.os.Handler.m$it(Handler.java:$it)" }
    }

    @Test
    fun `frames per exception are capped`() {
        val r = report(StackOverflowError().withFrames(*Array(100) { app("recurse", it) }))
        r.trace.lines().size shouldBe 1 + CrashReport.MAX_FRAMES_PER_THROWABLE + 1
        r.trace shouldEndWith "\t... 40 more frames"
    }

    @Test
    fun `suppressed exceptions, cycles and deep nesting are bounded`() {
        val a = IllegalStateException("a").withFrames(app("a"))
        val b = IllegalArgumentException("b").withFrames(app("b"))
        a.initCause(b)
        b.initCause(a)
        a.addSuppressed(IOException("closing").withFrames(app("close")))
        report(a).trace.lines() shouldBe
            listOf(
                "java.lang.IllegalStateException: a",
                "\tat com.kitefoldlabs.bridle.App.a(App.kt:10)",
                "Suppressed: java.io.IOException: closing",
                "\tat com.kitefoldlabs.bridle.App.close(App.kt:10)",
                "Caused by: java.lang.IllegalArgumentException: b",
                "\tat com.kitefoldlabs.bridle.App.b(App.kt:10)",
                "Caused by: [circular reference: java.lang.IllegalStateException]",
            )

        var deep: Throwable = RuntimeException("level 12").withFrames()
        for (level in 11 downTo 0) deep = RuntimeException("level $level", deep).withFrames()
        val trace = report(deep).trace
        trace shouldContain "level ${CrashReport.MAX_NESTING}"
        trace shouldNotContain "level ${CrashReport.MAX_NESTING + 1}"
        trace shouldEndWith "\t... 1 nested exceptions omitted"
        report(RuntimeException("leaf").withFrames()).trace shouldBe "java.lang.RuntimeException: leaf"
    }

    @Test
    fun `long messages are capped after scrubbing`() {
        val long = report(RuntimeException("word ".repeat(200)).withFrames()).trace
        long shouldEndWith " [truncated]"
        long.length shouldBeLessThanOrEqual 600

        val huge = "x ".repeat(1_500) + "10.20.30.40"
        report(RuntimeException(huge).withFrames()).trace shouldNotContain "10.20"
        report(RuntimeException("a".repeat(5_000) + "10.20.30.40").withFrames()).trace shouldBe
            "java.lang.RuntimeException: <redacted>"
        report(RuntimeException("bell\u0007 and\r\nnew\tline").withFrames()).trace shouldBe
            "java.lang.RuntimeException: bell  and \nnew\tline"
    }

    @Test
    fun `whole trace is capped with a marker`() {
        val top = RuntimeException("top").withFrames(app("top"))
        repeat(60) { i ->
            val frames = Array(20) { app("s$i", it) }
            top.addSuppressed(RuntimeException("item $i " + "word ".repeat(80)).withFrames(*frames))
        }
        val r = report(top)
        r.trace.length shouldBeLessThanOrEqual CrashReport.MAX_TRACE_CHARS
        r.trace shouldEndWith "\n[truncated]"
        CrashReport.cap("no newline at all", 15) shouldBe "no \n[truncated]"
        CrashReport.cap("short", 15) shouldBe "short"
    }

    @Test
    fun `reports survive being saved and loaded`() {
        val r = report(IllegalStateException("x").withFrames(app("a")))
        val json = Json.encodeToString(CrashReport.serializer(), r)
        val back = Json.decodeFromString(CrashReport.serializer(), json)
        back.text shouldBe r.text
        back.exceptionType shouldBe r.exceptionType
        json shouldStartWith "{\"exceptionType\""
    }

    @Test
    fun `generated addresses in messages never reach the report`() =
        runBlocking<Unit> {
            val ip = Arb.list(Arb.int(0..255), 4..4).map { it.joinToString(".") }
            checkAll(200, ip, Arb.int(0..3)) { address, depth ->
                var t: Throwable = IOException("connect $address: refused").withFrames(app("x"))
                repeat(depth) { t = RuntimeException("wrapped ($address)", t).withFrames(app("y")) }
                report(t).text shouldNotContain address
            }
        }
}
