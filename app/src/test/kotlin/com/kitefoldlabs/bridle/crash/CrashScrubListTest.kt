package com.kitefoldlabs.bridle.crash

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.feature.eventually
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrashScrubListTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reporter = CrashReporter(context)

    @After
    fun tearDown() {
        scope.cancel()
        reporter.discard()
    }

    @Test
    fun `crash reports leave out saved hosts, users and sessions`() =
        runBlocking<Unit> {
            val data = testData(context)
            val following = CrashScrubList(reporter, data.hosts, data.launchProfiles, scope).start()
            val hostId =
                data.hosts.upsert(
                    Host(label = "Work box", hostname = "devbox", username = "nate", defaultTmuxSession = "night"),
                )
            data.launchProfiles.upsert(
                LaunchProfile(hostId = hostId, name = "Payments", sessionName = "pay-api", workingDir = "~/src/ledger"),
            )
            eventually { "pay-api" in reporter.sensitiveValues }
            reporter.sensitiveValues.toSet() shouldBe
                setOf("Work box", "devbox", "nate", "night", "Payments", "pay-api", "~/src/ledger")

            reporter.record(IllegalStateException("nate on devbox (Work box): pay-api and night in ledger failed"))
            val text = reporter.pending()!!.text
            for (secret in listOf("nate", "devbox", "Work box", "pay-api", "night")) text shouldNotContain secret
            text shouldContain "IllegalStateException"
            following.cancelAndJoin() // before the database closes
            data.close()
        }

    @Test
    fun `a failing source keeps the last snapshot`() =
        runBlocking<Unit> {
            val values =
                flow {
                    emit(setOf("devbox"))
                    throw IOException("database gone")
                }
            CrashScrubList(reporter, values, scope).start().join()
            reporter.sensitiveValues shouldBe setOf("devbox")
        }
}
