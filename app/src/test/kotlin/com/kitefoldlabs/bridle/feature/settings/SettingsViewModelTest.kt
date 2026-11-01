package com.kitefoldlabs.bridle.feature.settings

import android.app.Application
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.data.settings.NotificationEvent
import com.kitefoldlabs.bridle.core.data.settings.Settings
import com.kitefoldlabs.bridle.core.data.settings.ThemeMode
import com.kitefoldlabs.bridle.core.terminal.BackspaceMode
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout
import com.kitefoldlabs.bridle.feature.eventually
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class SettingsViewModelTest {
    private lateinit var data: DataModule
    private var available = true

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        data = testData(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        data.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = SettingsViewModel(data.settings, { available })

    private fun SettingsViewModel.current(): Settings = settings.value!!

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
        s.appLockEnabled shouldBe true
        s.secureScreenEnabled shouldBe true
        s.notificationsEnabled shouldBe false
        (NotificationEvent.CONNECTION_LOST in s.notificationEvents) shouldBe false
        s.useHelperByDefault shouldBe false
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
