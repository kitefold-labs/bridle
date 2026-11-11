package com.kitefoldlabs.bridle.feature.settings

import android.app.Application
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.data.settings.NotificationEvent
import com.kitefoldlabs.bridle.core.data.settings.Settings
import com.kitefoldlabs.bridle.core.data.settings.SettingsRepository
import com.kitefoldlabs.bridle.core.data.settings.ThemeMode
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.terminal.BackspaceMode
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout
import com.kitefoldlabs.bridle.feature.TestMain
import com.kitefoldlabs.bridle.feature.eventually
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsViewModelTest {
    private lateinit var data: DataModule
    private var available = true

    @Before
    fun setUp() {
        TestMain.set(Dispatchers.Unconfined)
        data = testData(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        data.close()
        TestMain.reset()
    }

    private fun viewModel() = SettingsViewModel(data.settings, { available }, data.composeHistory)

    private fun SettingsViewModel.current(): Settings = settings.value!!

    @Test
    fun `a store that can't be written leaves the settings alone instead of crashing`() {
        val broken =
            object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow { throw IOException("disk on fire") }

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    throw IOException("disk on fire")
            }
        val vm = SettingsViewModel(SettingsRepository(broken), { available }, data.composeHistory)
        eventually { vm.settings.value == SettingsRepository.UNREADABLE }
        val thread = Thread.currentThread()
        val previous = thread.uncaughtExceptionHandler
        val uncaught = mutableListOf<Throwable>()
        thread.setUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            vm.setTheme(ThemeMode.DARK)
            vm.setAppLock(false)
            vm.setRememberComposeHistory(false)
        } finally {
            thread.uncaughtExceptionHandler = previous
        }
        uncaught shouldBe emptyList()
        vm.settings.value shouldBe SettingsRepository.UNREADABLE
    }

    @Test
    fun `writes every preference`() {
        val vm = viewModel()
        eventually { vm.settings.value != null }
        vm.lockAvailable shouldBe true
        vm.setTheme(ThemeMode.DARK)
        vm.setFontSize(20)
        vm.setFont(TerminalFont.SERIF_MONOSPACE)
        vm.setBackspaceMode(BackspaceMode.CTRL_H)
        vm.setKeepScreenOn(true)
        vm.setCommandLineSuggestions(false)
        vm.setAppLock(true)
        vm.setSecureScreen(true)
        vm.setNotifications(false)
        vm.setNotificationEvent(NotificationEvent.CONNECTION_LOST, false)
        vm.setUseHelper(false)
        vm.setCrashReporting(true)
        eventually { vm.current().crashReportingOptIn }
        val s = vm.current()
        s.theme shouldBe ThemeMode.DARK
        s.terminalFontSizeSp shouldBe 20
        s.fontFamilyId shouldBe "serif-monospace"
        s.backspaceMode shouldBe BackspaceMode.CTRL_H
        s.keepScreenOn shouldBe true
        s.commandLineSuggestions shouldBe false
        s.appLockEnabled shouldBe true
        s.secureScreenEnabled shouldBe true
        s.notificationsEnabled shouldBe false
        (NotificationEvent.CONNECTION_LOST in s.notificationEvents) shouldBe false
        s.useHelperByDefault shouldBe false
        vm.setAppLock(false)
        eventually { !vm.current().appLockEnabled }
    }

    @Test
    fun `turning compose history off deletes what was saved`() {
        val vm = viewModel()
        val host = runBlocking { data.hosts.upsert(Host(label = "devbox", hostname = "devbox", username = "me")) }
        runBlocking { data.composeHistory.save(host, listOf("hunter2")) }
        vm.setRememberComposeHistory(true)
        eventually { vm.settings.value?.rememberComposeHistory == true }
        runBlocking { data.composeHistory.get(host) } shouldBe listOf("hunter2")
        vm.setRememberComposeHistory(false)
        eventually { vm.settings.value?.rememberComposeHistory == false }
        eventually { runBlocking { data.composeHistory.get(host) }.isEmpty() }
    }

    @Test
    fun `app lock can't be turned on without a screen lock`() {
        available = false
        val vm = viewModel()
        vm.lockAvailable shouldBe false
        vm.setAppLock(true)
        vm.setTheme(ThemeMode.LIGHT)
        eventually { vm.settings.value?.theme == ThemeMode.LIGHT }
        vm.current().appLockEnabled shouldBe false
    }

    @Test
    fun `key bar presets and the JSON editor`() {
        val vm = viewModel()
        vm.openKeyBarEditor()
        vm.keyBarEditor.value!!.json shouldBe KeyBarLayout.DEFAULT.toJson()
        vm.closeKeyBarEditor()
        vm.applyKeyBarJson() // nothing open
        eventually { vm.settings.value != null }
        KeyBarPreset.of(vm.current().keyBarLayout) shouldBe KeyBarPreset.DEFAULT

        vm.setKeyBarPreset(KeyBarPreset.CLAUDE)
        eventually { KeyBarPreset.of(vm.current().keyBarLayout) == KeyBarPreset.CLAUDE }
        vm.setKeyBarPreset(KeyBarPreset.DEFAULT)
        eventually { KeyBarPreset.of(vm.current().keyBarLayout) == KeyBarPreset.DEFAULT }

        vm.setKeyBarPreset(KeyBarPreset.CUSTOM)
        vm.editKeyBarJson("""{"rows": []}""")
        vm.applyKeyBarJson()
        eventually { vm.keyBarEditor.value?.error != null }
        vm.keyBarEditor.value!!
            .error
            .shouldNotBeNull()
        vm.editKeyBarJson("""{"rows": [[{"label": "Esc", "action": {"type": "key", "key": "ESCAPE"}}]]}""")
        vm.keyBarEditor.value!!
            .error
            .shouldBeNull()
        vm.applyKeyBarJson()
        eventually { vm.keyBarEditor.value == null }
        eventually { KeyBarPreset.of(vm.current().keyBarLayout) == KeyBarPreset.CUSTOM }
        vm.editKeyBarJson("ignored")
        vm.keyBarEditor.value.shouldBeNull()
    }

    @Test
    fun `fonts and authenticators`() {
        TerminalFont.of("serif-monospace") shouldBe TerminalFont.SERIF_MONOSPACE
        TerminalFont.of("comic-sans") shouldBe TerminalFont.MONOSPACE
        BiometricLockAvailability.authenticators(Build.VERSION_CODES.R) shouldBe
            (Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
        BiometricLockAvailability.authenticators(Build.VERSION_CODES.Q) shouldBe Authenticators.BIOMETRIC_STRONG
        val real = BiometricLockAvailability(ApplicationProvider.getApplicationContext())
        real.isAvailable() shouldBe
            (
                BiometricManager.from(ApplicationProvider.getApplicationContext()).canAuthenticate(
                    BiometricLockAvailability.authenticators(Build.VERSION.SDK_INT),
                ) == BiometricManager.BIOMETRIC_SUCCESS
            )
    }
}
