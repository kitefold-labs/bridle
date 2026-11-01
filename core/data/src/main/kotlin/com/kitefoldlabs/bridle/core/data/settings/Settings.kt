package com.kitefoldlabs.bridle.core.data.settings

import com.kitefoldlabs.bridle.core.terminal.BackspaceMode
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout

public enum class ThemeMode {
    /** Follow the system light/dark setting with Bridle's own colours. */
    SYSTEM,
    LIGHT,
    DARK,

    /** Material You colours from the wallpaper (Android 12+), following the system light/dark setting. */
    DYNAMIC,
}

/** Events that can post a local notification while a connection is open. */
public enum class NotificationEvent {
    /** Claude is waiting for a permission decision. */
    PERMISSION_REQUEST,

    /** Claude finished working and is waiting for input. */
    WAITING_FOR_INPUT,

    /** A Claude session exited. */
    SESSION_EXITED,

    /** A connection dropped and could not be re-established. */
    CONNECTION_LOST,
}

/** App preferences. Defaults are the values a fresh install uses. */
public data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Terminal font size in sp, within [MIN_FONT_SIZE_SP]..[MAX_FONT_SIZE_SP]. */
    val terminalFontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    /** Id of the terminal font; the UI maps ids to bundled fonts and falls back for unknown ones. */
    val fontFamilyId: String = DEFAULT_FONT_FAMILY_ID,
    val keyBarLayout: KeyBarLayout = KeyBarLayout.DEFAULT,
    val backspaceMode: BackspaceMode = BackspaceMode.DEL,
    /** Require biometric / device credential unlock when the app is opened. */
    val appLockEnabled: Boolean = false,
    /** Set FLAG_SECURE on terminal screens (no screenshots, blank in the recents list). */
    val secureScreenEnabled: Boolean = false,
    /** Keep the screen on while a terminal is visible. */
    val keepScreenOn: Boolean = false,
    /** Master switch for local notifications; see [notifies]. */
    val notificationsEnabled: Boolean = true,
    val notificationEvents: Set<NotificationEvent> = NotificationEvent.entries.toSet(),
    /** Offer to file a scrubbed crash report after a crash. Off until the user opts in. */
    val crashReportingOptIn: Boolean = false,
    /** Read Claude status from bridle-helper where it is installed, rather than guessing from the screen. */
    val useHelperByDefault: Boolean = true,
) {
    /** Whether [event] should post a notification: the master switch and the event's own toggle are both on. */
    public fun notifies(event: NotificationEvent): Boolean = notificationsEnabled && event in notificationEvents

    public companion object {
        public const val MIN_FONT_SIZE_SP: Int = 6
        public const val MAX_FONT_SIZE_SP: Int = 48
        public const val DEFAULT_FONT_SIZE_SP: Int = 14
        public const val DEFAULT_FONT_FAMILY_ID: String = "monospace"
        public const val MAX_FONT_FAMILY_ID_LENGTH: Int = 64
    }
}
