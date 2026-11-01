package com.kitefoldlabs.bridle.ui

import android.app.Activity
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kitefoldlabs.bridle.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Applies the "secure screen" setting: while it is on, the window is `FLAG_SECURE`, so terminal
 * content can't be captured in screenshots, screen recordings or the recent-apps thumbnail.
 *
 * App lock also keeps the app out of the recent-apps thumbnail (Android 13+), which is otherwise
 * captured as the app goes to the background and shown, unlocked, to whoever opens the overview.
 */
class SecureScreen
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) {
        /** Keeps [activity]'s window in step with the settings for as long as the activity lives. */
        fun bind(activity: ComponentActivity) {
            activity.lifecycleScope.launch {
                settings.settings
                    .map { it.secureScreenEnabled to it.appLockEnabled }
                    .distinctUntilChanged()
                    .collect { (secure, locked) ->
                        apply(activity.window, secure)
                        hideFromRecents(activity, locked)
                    }
            }
        }

        internal fun hideFromRecents(
            activity: Activity,
            hide: Boolean,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(!hide)
        }

        internal fun apply(
            window: Window,
            secure: Boolean,
        ) {
            if (secure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
