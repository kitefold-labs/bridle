package com.kitefoldlabs.bridle.feature.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.crash.CrashReporter
import com.kitefoldlabs.bridle.feature.eventually
import com.kitefoldlabs.bridle.feature.onboarding.OnboardingStore
import com.kitefoldlabs.bridle.feature.onboarding.OnboardingViewModel
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLockAndCrashTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var data: DataModule
    private var now = 0L
    private val lock = AppLock(timeoutMillis = 1_000, clock = { now })

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        data = testData(context)
    }

    @After
    fun tearDown() {
        data.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `locks on start and again after the timeout in the background`() {
        lock.locked.value shouldBe true
        lock.onForeground() // never backgrounded: no change
        lock.unlock()
        lock.locked.value shouldBe false
        lock.onBackground()
        now = 999
        lock.onForeground()
        lock.locked.value shouldBe false
        lock.onBackground()
        now = 2_000
        lock.onForeground()
        lock.locked.value shouldBe true
        AppLock().locked.value shouldBe true
    }

    @Test
    fun `the gate shows the lock only when app lock is on and usable`() {
        var available = true
        val vm = AppLockViewModel(data.settings, lock) { available }
        eventually { vm.screen.value == LockScreen.UNLOCKED }
        lock.locked.value shouldBe false // turning it on later never locks the current session
        runBlocking { data.settings.setAppLockEnabled(true) }
        vm.onBackground()
        now = 5_000
        vm.onForeground()
        eventually { vm.screen.value == LockScreen.LOCKED }
        vm.unlock()
        eventually { vm.screen.value == LockScreen.UNLOCKED }
        lock.onBackground()
        now = 10_000
        available = false
        lock.onForeground()
        eventually { vm.screen.value == LockScreen.UNLOCKED && !lock.locked.value }
    }

    @Test
    fun `asks before showing a crash report when not opted in`() {
        val reporter = CrashReporter(context)
        reporter.record(IllegalStateException("boom"))
        val vm = CrashReportViewModel(reporter, data.settings)
        eventually { vm.prompt.value != null }
        vm.prompt.value.shouldBeInstanceOf<CrashPrompt.AskConsent>()
        vm.accept()
        vm.prompt.value.shouldBeInstanceOf<CrashPrompt.Review>()
        eventually {
            runBlocking {
                data.settings.settings
                    .first()
                    .crashReportingOptIn
            }
        }
        val url = vm.file("  tapped connect  ")!!
        url shouldStartWith "https://github.com/"
        url shouldContain "tapped"
        vm.prompt.value.shouldBeNull()
        reporter.pending().shouldBeNull()
        vm.file("again").shouldBeNull()
        vm.accept()
        vm.prompt.value.shouldBeNull()
    }

    @Test
    fun `shows the report straight away when opted in, and discards`() {
        runBlocking { data.settings.setCrashReportingOptIn(true) }
        val reporter = CrashReporter(context)
        CrashReportViewModel(reporter, data.settings).prompt.value.shouldBeNull() // nothing pending
        reporter.record(RuntimeException("x"))
        val vm = CrashReportViewModel(reporter, data.settings)
        eventually { vm.prompt.value != null }
        vm.prompt.value.shouldBeInstanceOf<CrashPrompt.Review>()
        vm.prompt.value!!
            .report.title shouldBe "Crash: RuntimeException"
        vm.discard()
        vm.prompt.value.shouldBeNull()
        reporter.pending().shouldBeNull()
    }

    @Test
    fun `onboarding is shown once`() {
        val store = OnboardingStore(context)
        store.completed shouldBe false
        val vm = OnboardingViewModel(store)
        vm.visible.value shouldBe true
        vm.finish()
        vm.visible.value shouldBe false
        store.completed shouldBe true
        OnboardingViewModel(OnboardingStore(context)).visible.value shouldBe false
    }
}
