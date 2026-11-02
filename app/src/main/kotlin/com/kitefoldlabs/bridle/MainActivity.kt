package com.kitefoldlabs.bridle

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.kitefoldlabs.bridle.feature.hosts.HostsFeature
import com.kitefoldlabs.bridle.feature.keys.InteractionHost
import com.kitefoldlabs.bridle.feature.keys.KeysFeature
import com.kitefoldlabs.bridle.feature.keys.ResumedActivityTracker
import com.kitefoldlabs.bridle.feature.sessions.SessionsRoute
import com.kitefoldlabs.bridle.feature.terminal.TerminalDeepLinks
import com.kitefoldlabs.bridle.feature.terminal.TerminalNavigator
import com.kitefoldlabs.bridle.feature.terminal.TerminalTabsRoute
import com.kitefoldlabs.bridle.ui.SecureScreen
import com.kitefoldlabs.bridle.ui.nav.BridleNavHost
import com.kitefoldlabs.bridle.ui.nav.TopLevel
import com.kitefoldlabs.bridle.ui.theme.BridleTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Single activity. A FragmentActivity so BiometricPrompt can be shown for key unlocks. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var navigator: TerminalNavigator

    @Inject lateinit var deepLinks: TerminalDeepLinks

    @Inject lateinit var activityTracker: ResumedActivityTracker

    @Inject lateinit var secureScreen: SecureScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        activityTracker.attach(this)
        secureScreen.bind(this)
        // Only for a fresh start: a recreated activity (rotation) gets the same intent again.
        if (savedInstanceState == null) deepLinks.handle(intent)
        val screens: Map<TopLevel, @Composable () -> Unit> =
            mapOf(
                TopLevel.HOSTS to { HostsFeature(onOpenTerminal = deepLinks::openHost) },
                TopLevel.SESSIONS to { SessionsRoute() },
                TopLevel.KEYS to { KeysFeature() },
            )
        setContent {
            BridleTheme {
                BridleNavHost(
                    screens = screens,
                    terminal = { onBack -> TerminalTabsRoute(onBack) },
                    showTerminal = navigator.showRequests,
                )
                InteractionHost()
            }
        }
    }

    /** A Claude notification tapped while the app is open: attach to its session. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinks.handle(intent)
    }
}
