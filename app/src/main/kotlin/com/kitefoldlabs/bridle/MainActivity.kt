package com.kitefoldlabs.bridle

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.kitefoldlabs.bridle.feature.settings.SettingsRoute
import com.kitefoldlabs.bridle.ui.BridleApp
import com.kitefoldlabs.bridle.ui.SecureScreen
import com.kitefoldlabs.bridle.ui.nav.TopLevel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Single activity. A FragmentActivity so BiometricPrompt can be shown for key unlocks. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var secureScreen: SecureScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        secureScreen.bind(this)
        setContent {
            BridleApp(
                screens =
                    mapOf(
                        TopLevel.SETTINGS to { SettingsRoute() },
                    ),
                routes = {},
            )
        }
    }

    companion object {
        const val EXTRA_HOST: String = "host"
        const val EXTRA_SESSION: String = "session"
    }
}
