package com.kitefoldlabs.bridle.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.kitefoldlabs.bridle.core.terminal.BackspaceMode
import com.kitefoldlabs.bridle.core.terminal.KeyAction
import com.kitefoldlabs.bridle.core.terminal.KeyBarKey
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayoutException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(folder.root, "settings.preferences_pb")
        }

    private val customLayout = KeyBarLayout(listOf(listOf(KeyBarKey("hi", KeyAction.Text("hello")))))

    @Test
    fun `a fresh install uses the defaults`() =
        runTest {
            val settings = SettingsRepository(store()).settings.first()

            settings shouldBe Settings()
            settings.keyBarLayout shouldBe KeyBarLayout.DEFAULT
            NotificationEvent.entries.all(settings::notifies) shouldBe true
        }

    @Test
    fun `every setter is persisted`() =
        runTest {
            val dataStore = store()
            val repository = SettingsRepository(dataStore)

            repository.setTheme(ThemeMode.DYNAMIC)
            repository.setTerminalFontSize(18)
            repository.setFontFamilyId("jetbrains-mono")
            repository.setKeyBarLayout(customLayout)
            repository.setBackspaceMode(BackspaceMode.CTRL_H)
            repository.setAppLockEnabled(true)
            repository.setSecureScreenEnabled(true)
            repository.setKeepScreenOn(true)
            repository.setRememberComposeHistory(false)
            repository.setComposeEnterSends(false)
            repository.setNotificationsEnabled(false)
            repository.setNotificationEventEnabled(NotificationEvent.SESSION_EXITED, false)
            repository.setCrashReportingOptIn(true)
            repository.setUseHelperByDefault(false)

            // A second repository over the same store sees the same values.
            SettingsRepository(dataStore).settings.first() shouldBe
                Settings(
                    theme = ThemeMode.DYNAMIC,
                    terminalFontSizeSp = 18,
                    fontFamilyId = "jetbrains-mono",
                    keyBarLayout = customLayout,
                    backspaceMode = BackspaceMode.CTRL_H,
                    appLockEnabled = true,
                    secureScreenEnabled = true,
                    keepScreenOn = true,
                    rememberComposeHistory = false,
                    composeEnterSends = false,
                    notificationsEnabled = false,
                    notificationEvents = NotificationEvent.entries.toSet() - NotificationEvent.SESSION_EXITED,
                    crashReportingOptIn = true,
                    useHelperByDefault = false,
                )
        }

    @Test
    fun `notifications need the master switch and the event toggle`() =
        runTest {
            val repository = SettingsRepository(store())

            repository.setNotificationEventEnabled(NotificationEvent.CONNECTION_LOST, false)
            val settings = repository.settings.first()
            settings.notifies(NotificationEvent.CONNECTION_LOST) shouldBe false
            settings.notifies(NotificationEvent.PERMISSION_REQUEST) shouldBe true

            repository.setNotificationsEnabled(false)
            repository.settings.first().notifies(NotificationEvent.PERMISSION_REQUEST) shouldBe false
        }

    @Test
    fun `the flow emits on change only`() =
        runTest {
            val repository = SettingsRepository(store())

            repository.settings.test {
                awaitItem().theme shouldBe ThemeMode.SYSTEM
                repository.setTheme(ThemeMode.SYSTEM)
                repository.setTheme(ThemeMode.DARK)
                awaitItem().theme shouldBe ThemeMode.DARK
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `font size is clamped`() =
        runTest {
            val repository = SettingsRepository(store())

            repository.setTerminalFontSize(1)
            repository.settings.first().terminalFontSizeSp shouldBe Settings.MIN_FONT_SIZE_SP
            repository.setTerminalFontSize(500)
            repository.settings.first().terminalFontSizeSp shouldBe Settings.MAX_FONT_SIZE_SP
        }

    @Test
    fun `invalid font family ids are rejected`() =
        runTest {
            val repository = SettingsRepository(store())

            shouldThrow<IllegalArgumentException> { repository.setFontFamilyId(" ") }
            shouldThrow<IllegalArgumentException> { repository.setFontFamilyId("x".repeat(65)) }
            repository.settings.first().fontFamilyId shouldBe Settings.DEFAULT_FONT_FAMILY_ID
        }

    @Test
    fun `key-bar layouts are validated when set and can be reset`() =
        runTest {
            val repository = SettingsRepository(store())

            repository.setKeyBarLayoutJson(customLayout.toJson())
            repository.settings.first().keyBarLayout shouldBe customLayout

            shouldThrow<KeyBarLayoutException> { repository.setKeyBarLayoutJson("""{"rows": []}""") }
            repository.settings.first().keyBarLayout shouldBe customLayout

            repository.resetKeyBarLayout()
            repository.settings.first().keyBarLayout shouldBe KeyBarLayout.DEFAULT
        }

    @Test
    fun `unreadable stored values fall back to the defaults`() =
        runTest {
            val dataStore = store()
            dataStore.edit {
                it[SettingsRepository.THEME] = "NEON"
                it[SettingsRepository.BACKSPACE] = "ctrl_h"
                it[SettingsRepository.FONT_SIZE] = -3
                it[SettingsRepository.FONT_FAMILY] = ""
                it[SettingsRepository.KEY_BAR] = "not json"
            }

            SettingsRepository(dataStore).settings.first() shouldBe
                Settings(terminalFontSizeSp = Settings.MIN_FONT_SIZE_SP)
        }

    @Test
    fun `reset restores every default`() =
        runTest {
            val repository = SettingsRepository(store())
            repository.setTheme(ThemeMode.LIGHT)
            repository.setAppLockEnabled(true)

            repository.reset()

            repository.settings.first() shouldBe Settings()
        }

    @Test
    fun `read errors give the defaults, but with the privacy settings closed`() =
        runTest {
            val failing = FailingStore(IOException("disk on fire"))

            SettingsRepository(failing).settings.toList() shouldBe listOf(SettingsRepository.UNREADABLE)
            SettingsRepository.UNREADABLE shouldBe
                Settings().copy(appLockEnabled = true, secureScreenEnabled = true, rememberComposeHistory = false)
        }

    @Test
    fun `stores that answer without suspending work too`() =
        runTest {
            val store = InstantStore()

            SettingsRepository(store).setKeepScreenOn(true)

            SettingsRepository(store).settings.first().keepScreenOn shouldBe true
        }

    @Test
    fun `other errors propagate`() =
        runTest {
            val failing = FailingStore(IllegalStateException("bug"))

            shouldThrow<IllegalStateException> { SettingsRepository(failing).settings.first() }
        }

    @Test
    fun `the app store lives in the app's files directory`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val file = File(context.filesDir, "datastore/${SettingsRepository.STORE_NAME}.preferences_pb")
            file.parentFile?.mkdirs()
            file.writeText("not a protocol buffer")
            val repository = SettingsRepository(SettingsRepository.createDataStore(context, backgroundScope))

            // A corrupt file is replaced with the defaults rather than failing.
            repository.settings.first() shouldBe Settings()
            repository.setTheme(ThemeMode.DARK)

            repository.settings.first().theme shouldBe ThemeMode.DARK
            file.exists() shouldBe true
        }

    /** Completes every call without suspending. */
    private class InstantStore : DataStore<Preferences> {
        private var current: Preferences = emptyPreferences()

        override val data: Flow<Preferences> = flow { emit(current) }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(current).also { current = it }
    }

    private class FailingStore(
        private val error: Throwable,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
    }
}
