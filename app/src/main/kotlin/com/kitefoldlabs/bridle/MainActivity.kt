package com.kitefoldlabs.bridle

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.kitefoldlabs.bridle.feature.settings.SettingsRoute
import com.kitefoldlabs.bridle.ui.BridleApp
import com.kitefoldlabs.bridle.ui.nav.TopLevel
import dagger.hilt.android.AndroidEntryPoint

/** Single activity. A FragmentActivity so BiometricPrompt can be shown for key unlocks. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BridleApp(
                screens =
                    mapOf(
                        TopLevel.SETTINGS to { SettingsRoute() },
                    ),
            )
        }
    }

    companion object {
        const val EXTRA_HOST: String = "host"
        const val EXTRA_SESSION: String = "session"
    }
}
