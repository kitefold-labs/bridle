package com.kitefoldlabs.bridle.crash

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrashReporterTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val reporter = CrashReporter(context)

    @Test
    fun `records a scrubbed report and offers it once`() {
        reporter.pending() shouldBe null
        reporter.sensitiveValues = listOf("devbox")
        reporter.record(IllegalStateException("cannot reach devbox at 10.1.2.3"), now = 42)
        val report = reporter.pending()
        report shouldNotBe null
        report!!.text shouldNotContain "devbox"
        report.text shouldNotContain "10.1.2.3"
        report.title shouldBe "Crash: IllegalStateException"
        reporter.issueUrl(report, "tapped connect") shouldStartWith "https://github.com/"
        reporter.discard()
        reporter.pending() shouldBe null
    }

    @Test
    fun `corrupt files are discarded and the handler chains`() {
        File(context.noBackupFilesDir, "pending-crash.json").writeText("{not json")
        reporter.pending() shouldBe null
        File(context.noBackupFilesDir, "pending-crash.json").exists() shouldBe false
        File(context.noBackupFilesDir, "pending-crash.json").writeText("""{"unexpected":1}""")
        reporter.pending() shouldBe null

        var chained: Throwable? = null
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            reporter.install { _, e -> chained = e }
            val boom = RuntimeException("boom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            chained shouldBe boom
            reporter.pending()!!.text shouldContain "RuntimeException"
            reporter.install(previous = null)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `a crash while reporting still reaches the previous handler`() {
        var chained: Throwable? = null
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val hostile =
            object : RuntimeException() {
                override val message: String get() = error("reporting failed")
            }
        try {
            reporter.install { _, e -> chained = e }
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), hostile)
            (chained === hostile) shouldBe true
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `write failures are swallowed`() {
        val dir = context.noBackupFilesDir
        File(dir, "pending-crash.json").mkdirs() // a directory where the file should be
        reporter.record(RuntimeException("x"))
        reporter.pending() shouldBe null
        File(dir, "pending-crash.json").delete()
    }
}
