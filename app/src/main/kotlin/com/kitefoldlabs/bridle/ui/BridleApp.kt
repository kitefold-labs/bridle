package com.kitefoldlabs.bridle.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.kitefoldlabs.bridle.core.data.settings.ThemeMode
import com.kitefoldlabs.bridle.feature.forwards.forwardsDestination
import com.kitefoldlabs.bridle.feature.onboarding.OnboardingGate
import com.kitefoldlabs.bridle.feature.settings.AppLockGate
import com.kitefoldlabs.bridle.feature.settings.CrashReportPrompt
import com.kitefoldlabs.bridle.feature.settings.SecureWindow
import com.kitefoldlabs.bridle.feature.settings.SettingsViewModel
import com.kitefoldlabs.bridle.feature.settings.rememberBiometricUnlocker
import com.kitefoldlabs.bridle.feature.sftp.sftpDestination
import com.kitefoldlabs.bridle.feature.snippets.snippetsDestination
import com.kitefoldlabs.bridle.ui.nav.BridleNavHost
import com.kitefoldlabs.bridle.ui.nav.TopLevel
import com.kitefoldlabs.bridle.ui.theme.BridleTheme

/**
 * The whole UI: theme and screenshot blocking from settings, the app-lock gate, first-run
 * onboarding, the crash-report prompt, and navigation. [screens] are the top-level tabs;
 * [routes] adds further destinations (pass `{}` for none) next to the built-in host tools (files, snippets, forwards).
 */
@Composable
fun BridleApp(
    screens: Map<TopLevel, @Composable () -> Unit>,
    routes: NavGraphBuilder.(NavHostController) -> Unit,
) {
    val settings: SettingsViewModel = hiltViewModel()
    val nav = rememberNavController()
    val current by settings.settings.collectAsStateWithLifecycle()
    BridleTheme(current?.theme ?: ThemeMode.SYSTEM) {
        SecureWindow(current?.secureScreenEnabled == true)
        AppLockGate(hiltViewModel(), rememberBiometricUnlocker()) {
            OnboardingGate(
                onGenerateKey = { nav.navigate(TopLevel.KEYS.route) },
                onAddHost = { nav.navigate(TopLevel.HOSTS.route) },
                viewModel = hiltViewModel(),
            ) {
                BridleNavHost(screens, nav) {
                    sftpDestination(onBack = nav::popBackStack)
                    snippetsDestination(onBack = nav::popBackStack)
                    forwardsDestination(onBack = nav::popBackStack)
                    routes(nav)
                }
                CrashReportPrompt(hiltViewModel())
            }
        }
    }
}
