package com.kitefoldlabs.bridle.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.kitefoldlabs.bridle.core.terminal.BackspaceMode
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Locale

/**
 * App preferences on Jetpack DataStore. Reading never fails: missing, out-of-range or unreadable
 * values (including a key-bar layout that no longer parses) fall back to the [Settings] defaults.
 * Writers validate, so bad values are rejected when they are set rather than silently dropped.
 */
public class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    /** The current settings; emits again whenever one changes. */
    public val settings: Flow<Settings> =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map(::read)
            .distinctUntilChanged()

    public suspend fun setTheme(theme: ThemeMode): Unit = update { it[THEME] = theme.name }

    /** Sets the terminal font size, clamped to [Settings.MIN_FONT_SIZE_SP]..[Settings.MAX_FONT_SIZE_SP]. */
    public suspend fun setTerminalFontSize(sp: Int): Unit =
        update { it[FONT_SIZE] = sp.coerceIn(Settings.MIN_FONT_SIZE_SP, Settings.MAX_FONT_SIZE_SP) }

    /** @throws IllegalArgumentException if [id] is blank or longer than [Settings.MAX_FONT_FAMILY_ID_LENGTH]. */
    public suspend fun setFontFamilyId(id: String): Unit =
        update {
            require(isValidFontFamilyId(id)) { "Invalid font family id" }
            it[FONT_FAMILY] = id
        }

    public suspend fun setKeyBarLayout(layout: KeyBarLayout): Unit = update { it[KEY_BAR] = layout.toJson() }

    /**
     * Parses, validates and stores a key-bar layout written by the user.
     *
     * @throws KeyBarLayoutException naming the offending field; nothing is stored.
     */
    public suspend fun setKeyBarLayoutJson(json: String): Unit = setKeyBarLayout(KeyBarLayout.parse(json))

    /** Goes back to [KeyBarLayout.DEFAULT]. */
    public suspend fun resetKeyBarLayout(): Unit = update { it.remove(KEY_BAR) }

    public suspend fun setBackspaceMode(mode: BackspaceMode): Unit = update { it[BACKSPACE] = mode.name }

    public suspend fun setAppLockEnabled(enabled: Boolean): Unit = update { it[APP_LOCK] = enabled }

    public suspend fun setSecureScreenEnabled(enabled: Boolean): Unit = update { it[SECURE_SCREEN] = enabled }

    public suspend fun setKeepScreenOn(enabled: Boolean): Unit = update { it[KEEP_SCREEN_ON] = enabled }

    public suspend fun setNotificationsEnabled(enabled: Boolean): Unit = update { it[NOTIFICATIONS] = enabled }

    public suspend fun setNotificationEventEnabled(
        event: NotificationEvent,
        enabled: Boolean,
    ): Unit = update { it[eventKey.getValue(event)] = enabled }

    public suspend fun setCrashReportingOptIn(optIn: Boolean): Unit = update { it[CRASH_REPORTING] = optIn }

    public suspend fun setUseHelperByDefault(enabled: Boolean): Unit = update { it[USE_HELPER] = enabled }

    /** Restores every default. */
    public suspend fun reset(): Unit = update { it.clear() }

    private suspend fun update(transform: (MutablePreferences) -> Unit) {
        dataStore.edit { transform(it) }
    }

    public companion object {
        /** Name of the DataStore file: `files/datastore/bridle_settings.preferences_pb`. */
        public const val STORE_NAME: String = "bridle_settings"

        /**
         * Creates the app's settings store. Call it once per process (DataStore allows a single
         * instance per file); [scope] owns the store's I/O and should live as long as the app.
         * A corrupt file is replaced with the defaults.
         */
        public fun createDataStore(
            context: Context,
            scope: CoroutineScope,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { context.preferencesDataStoreFile(STORE_NAME) },
            )

        internal val THEME = stringPreferencesKey("theme")
        internal val FONT_SIZE = intPreferencesKey("terminal_font_size_sp")
        internal val FONT_FAMILY = stringPreferencesKey("font_family_id")
        internal val KEY_BAR = stringPreferencesKey("key_bar_layout_json")
        internal val BACKSPACE = stringPreferencesKey("backspace_mode")
        internal val APP_LOCK = booleanPreferencesKey("app_lock_enabled")
        internal val SECURE_SCREEN = booleanPreferencesKey("secure_screen_enabled")
        internal val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        internal val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        internal val CRASH_REPORTING = booleanPreferencesKey("crash_reporting_opt_in")
        internal val USE_HELPER = booleanPreferencesKey("use_helper_by_default")
        internal val eventKey: Map<NotificationEvent, Preferences.Key<Boolean>> =
            NotificationEvent.entries.associateWith { event ->
                booleanPreferencesKey("notify_" + event.name.lowercase(Locale.ROOT))
            }

        private val defaults = Settings()

        private fun isValidFontFamilyId(id: String): Boolean =
            id.isNotBlank() && id.length <= Settings.MAX_FONT_FAMILY_ID_LENGTH

        private inline fun <reified E : Enum<E>> enumOrDefault(
            name: String?,
            default: E,
        ): E = enumValues<E>().firstOrNull { it.name == name } ?: default

        private fun keyBarLayout(json: String?): KeyBarLayout =
            json?.let {
                try {
                    KeyBarLayout.parse(it)
                } catch (ignored: KeyBarLayoutException) {
                    null
                }
            } ?: defaults.keyBarLayout

        internal fun read(prefs: Preferences): Settings =
            Settings(
                theme = enumOrDefault(prefs[THEME], defaults.theme),
                terminalFontSizeSp =
                    (prefs[FONT_SIZE] ?: defaults.terminalFontSizeSp)
                        .coerceIn(Settings.MIN_FONT_SIZE_SP, Settings.MAX_FONT_SIZE_SP),
                fontFamilyId = prefs[FONT_FAMILY]?.takeIf(::isValidFontFamilyId) ?: defaults.fontFamilyId,
                keyBarLayout = keyBarLayout(prefs[KEY_BAR]),
                backspaceMode = enumOrDefault(prefs[BACKSPACE], defaults.backspaceMode),
                appLockEnabled = prefs[APP_LOCK] ?: defaults.appLockEnabled,
                secureScreenEnabled = prefs[SECURE_SCREEN] ?: defaults.secureScreenEnabled,
                keepScreenOn = prefs[KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
                notificationsEnabled = prefs[NOTIFICATIONS] ?: defaults.notificationsEnabled,
                notificationEvents =
                    NotificationEvent.entries.filterTo(mutableSetOf()) { event ->
                        prefs[eventKey.getValue(event)] ?: (event in defaults.notificationEvents)
                    },
                crashReportingOptIn = prefs[CRASH_REPORTING] ?: defaults.crashReportingOptIn,
                useHelperByDefault = prefs[USE_HELPER] ?: defaults.useHelperByDefault,
            )
    }
}
