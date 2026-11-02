package com.kitefoldlabs.bridle.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.data.ComposeHistoryRepository
import com.kitefoldlabs.bridle.core.data.settings.NotificationEvent
import com.kitefoldlabs.bridle.core.data.settings.Settings
import com.kitefoldlabs.bridle.core.data.settings.SettingsRepository
import com.kitefoldlabs.bridle.core.data.settings.ThemeMode
import com.kitefoldlabs.bridle.core.terminal.BackspaceMode
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayoutException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Which built-in key-bar layout is in use, or a custom one. */
enum class KeyBarPreset {
    DEFAULT,
    CLAUDE,
    CUSTOM,
    ;

    companion object {
        fun of(layout: KeyBarLayout): KeyBarPreset =
            when (layout) {
                KeyBarLayout.DEFAULT -> DEFAULT
                KeyBarLayout.CLAUDE -> CLAUDE
                else -> CUSTOM
            }
    }
}

/** The custom key-bar JSON editor. [error] is the parser's message naming the bad field. */
data class KeyBarEditorState(
    val json: String,
    val error: String? = null,
)

/** Terminal fonts offered in settings: ids the terminal maps to Android's system typefaces. */
enum class TerminalFont(
    val id: String,
) {
    MONOSPACE(Settings.DEFAULT_FONT_FAMILY_ID),
    SERIF_MONOSPACE("serif-monospace"),
    ;

    companion object {
        fun of(id: String): TerminalFont = entries.firstOrNull { it.id == id } ?: MONOSPACE
    }
}

/** App preferences. Every change is written straight to [SettingsRepository]. */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val repository: SettingsRepository,
        private val availability: LockAvailability,
        private val history: ComposeHistoryRepository,
    ) : ViewModel() {
        /** Null until the stored settings have been read. */
        val settings: StateFlow<Settings?> = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val editor = MutableStateFlow<KeyBarEditorState?>(null)
        val keyBarEditor: StateFlow<KeyBarEditorState?> = editor.asStateFlow()

        /** Whether the device can show a biometric or screen-lock prompt (needed for app lock). */
        val lockAvailable: Boolean get() = availability.isAvailable()

        fun setTheme(theme: ThemeMode) = write { setTheme(theme) }

        fun setFontSize(sp: Int) = write { setTerminalFontSize(sp) }

        fun setFont(font: TerminalFont) = write { setFontFamilyId(font.id) }

        fun setBackspaceMode(mode: BackspaceMode) = write { setBackspaceMode(mode) }

        fun setKeepScreenOn(enabled: Boolean) = write { setKeepScreenOn(enabled) }

        /** Turning it off also deletes the history already saved, for every host. */
        fun setRememberComposeHistory(enabled: Boolean) =
            write {
                setRememberComposeHistory(enabled)
                if (!enabled) history.clearAll()
            }

        fun setAppLock(enabled: Boolean) = write { setAppLockEnabled(enabled && availability.isAvailable()) }

        fun setSecureScreen(enabled: Boolean) = write { setSecureScreenEnabled(enabled) }

        fun setNotifications(enabled: Boolean) = write { setNotificationsEnabled(enabled) }

        fun setNotificationEvent(
            event: NotificationEvent,
            enabled: Boolean,
        ) = write { setNotificationEventEnabled(event, enabled) }

        fun setUseHelper(enabled: Boolean) = write { setUseHelperByDefault(enabled) }

        fun setCrashReporting(optIn: Boolean) = write { setCrashReportingOptIn(optIn) }

        /** Switches to a built-in layout, or opens the JSON editor for [KeyBarPreset.CUSTOM]. */
        fun setKeyBarPreset(preset: KeyBarPreset) {
            when (preset) {
                KeyBarPreset.DEFAULT -> write { resetKeyBarLayout() }
                KeyBarPreset.CLAUDE -> write { setKeyBarLayout(KeyBarLayout.CLAUDE) }
                KeyBarPreset.CUSTOM -> openKeyBarEditor()
            }
        }

        /** Opens the JSON editor on the current layout. */
        fun openKeyBarEditor() {
            val layout = settings.value?.keyBarLayout ?: KeyBarLayout.DEFAULT
            editor.value = KeyBarEditorState(layout.toJson())
        }

        fun editKeyBarJson(json: String) = editor.update { it?.copy(json = json, error = null) }

        fun closeKeyBarEditor() {
            editor.value = null
        }

        /** Validates and stores the edited layout, or shows why it is invalid. */
        fun applyKeyBarJson() {
            val current = editor.value ?: return
            viewModelScope.launch {
                try {
                    repository.setKeyBarLayoutJson(current.json)
                    editor.value = null
                } catch (e: KeyBarLayoutException) {
                    editor.update { it?.copy(error = e.message) }
                }
            }
        }

        /**
         * A store that can't be written (the same I/O error that gave [SettingsRepository.UNREADABLE],
         * say) leaves the setting as it was: the screen keeps showing the old value, and the app
         * doesn't crash.
         */
        private fun write(change: suspend SettingsRepository.() -> Unit) {
            viewModelScope.launch {
                try {
                    repository.change()
                } catch (ignored: IOException) {
                    // Nothing changed; the screen still shows the stored value.
                }
            }
        }
    }
