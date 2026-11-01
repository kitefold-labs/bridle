package com.kitefoldlabs.bridle.feature.settings

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.crash.CrashReporter
import com.kitefoldlabs.bridle.feature.FakeUris
import com.kitefoldlabs.bridle.feature.TestHost
import com.kitefoldlabs.bridle.feature.onboarding.OnboardingGate
import com.kitefoldlabs.bridle.feature.onboarding.OnboardingStore
import com.kitefoldlabs.bridle.feature.onboarding.OnboardingViewModel
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GatesUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val data: DataModule = testData(context)

    @After
    fun tearDown() = data.close()

    private fun waitForText(text: String) =
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the lock screen covers the app until the user unlocks`() {
        runBlocking { data.settings.setAppLockEnabled(true) }
        val answers = ArrayDeque(listOf(false, true))
        val vm = AppLockViewModel(data.settings, AppLock()) { true }
        rule.setContent { AppLockGate(vm, unlocker = { answers.removeFirst() }) { Text("secret content") } }
        waitForText("Bridle is locked")
        rule.onNodeWithText("secret content").assertDoesNotExist() // hidden from accessibility while locked
        rule.onNodeWithText("Unlock").performClick()
        waitForText("secret content")
        rule.onNodeWithText("Bridle is locked").assertDoesNotExist()
    }

    @Test
    fun `without app lock the content shows straight away`() {
        val vm = AppLockViewModel(data.settings, AppLock()) { true }
        var unlocker: Unlocker? = null
        rule.setContent {
            unlocker = rememberBiometricUnlocker()
            AppLockGate(vm, unlocker = { false }) { Text("content") }
        }
        waitForText("content")
        runBlocking { unlocker!!.unlock() } shouldBe false // not a FragmentActivity
    }

    @Test
    fun `crash prompt asks, shows the report and opens an issue`() {
        val reporter = CrashReporter(context).apply { record(IllegalStateException("boom")) }
        val uris = FakeUris()
        val vm = CrashReportViewModel(reporter, data.settings)
        rule.setContent { TestHost(uris = uris) { CrashReportPrompt(vm) } }
        waitForText("Would you like to review it")
        rule.onNodeWithText("Review report").performClick()
        waitForText("IllegalStateException")
        rule.onNode(hasSetTextAction()).performTextInput("opened a tab")
        rule.onNodeWithText("Open GitHub issue").performClick()
        uris.opened.single() shouldStartWith "https://github.com/"
        rule.onNodeWithText("Bridle closed unexpectedly").assertDoesNotExist()
        reporter.pending() shouldBe null
    }

    @Test
    fun `crash prompt can be declined or discarded`() {
        val reporter = CrashReporter(context).apply { record(RuntimeException("x")) }
        var vm by mutableStateOf(CrashReportViewModel(reporter, data.settings))
        rule.setContent { CrashReportPrompt(vm) }
        waitForText("No thanks")
        rule.onNodeWithText("No thanks").performClick()
        reporter.pending() shouldBe null
        reporter.record(RuntimeException("y"))
        runBlocking { data.settings.setCrashReportingOptIn(true) }
        vm = CrashReportViewModel(reporter, data.settings)
        waitForText("Discard")
        rule.onNodeWithText("Discard").performClick()
        reporter.pending() shouldBe null
    }

    @Test
    fun `onboarding pages and calls to action`() {
        var keys = 0
        var hosts = 0
        val vm = OnboardingViewModel(OnboardingStore(context))
        rule.setContent {
            OnboardingGate(onGenerateKey = { keys++ }, onAddHost = { hosts++ }, viewModel = vm) { Text("hosts screen") }
        }
        rule.onNodeWithText("Your servers, in your pocket").assertExists()
        rule.onNodeWithText("1 of 3").assertExists()
        rule.onNodeWithText("hosts screen").assertDoesNotExist()
        rule.onNodeWithText("Next").performClick()
        waitForText("Keys stay on this phone")
        rule.onNodeWithText("Next").performClick()
        waitForText("tmux keeps work alive")
        rule.onNodeWithText("Generate a key").performClick()
        keys shouldBe 1
        rule.onNodeWithText("hosts screen").assertExists()
        OnboardingStore(context).completed shouldBe true
    }

    @Test
    fun `onboarding can be skipped or end on add host`() {
        var hosts = 0
        context
            .getSharedPreferences("onboarding", 0)
            .edit()
            .clear()
            .commit()
        var vm by mutableStateOf(OnboardingViewModel(OnboardingStore(context)))
        rule.setContent { OnboardingGate(onGenerateKey = {}, onAddHost = { hosts++ }, viewModel = vm) { Text("main") } }
        rule.onNodeWithText("Skip").performClick()
        rule.onNodeWithText("main").assertExists()
        context
            .getSharedPreferences("onboarding", 0)
            .edit()
            .clear()
            .commit()
        vm = OnboardingViewModel(OnboardingStore(context))
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Next").performClick()
        waitForText("Add a host")
        rule.onNodeWithText("Add a host").performClick()
        hosts shouldBe 1
    }

    @Test
    fun `secure window sets and clears FLAG_SECURE`() {
        var secure by mutableStateOf(true)
        rule.setContent { SecureWindow(secure) }
        rule.waitForIdle()
        (rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) shouldBe
            WindowManager.LayoutParams.FLAG_SECURE
        secure = false
        rule.waitForIdle()
        (rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) shouldBe 0
        context.findActivity() shouldBe null
    }

    @Test
    fun `secure window outside an activity does nothing`() {
        var secure by mutableStateOf(true)
        rule.setContent { CompositionLocalProvider(LocalContext provides context) { SecureWindow(secure) } }
        rule.waitForIdle()
        secure = false
        rule.waitForIdle()
        (rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) shouldBe 0
    }
}
