package com.kitefoldlabs.bridle.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.kitefoldlabs.bridle.feature.settings.SettingsViewModel
import com.kitefoldlabs.bridle.feature.settings.rememberBiometricUnlocker
import com.kitefoldlabs.bridle.feature.sftp.sftpDestination
import com.kitefoldlabs.bridle.feature.snippets.snippetsDestination
import com.kitefoldlabs.bridle.ui.nav.BridleNavHost
import com.kitefoldlabs.bridle.ui.nav.LocalTabRequests
import com.kitefoldlabs.bridle.ui.nav.TabRequest
import com.kitefoldlabs.bridle.ui.nav.TabRequests
import com.kitefoldlabs.bridle.ui.nav.TopLevel
import com.kitefoldlabs.bridle.ui.theme.BridleTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The whole UI: the theme from settings, the app-lock gate, first-run onboarding, the
 * crash-report prompt, and navigation. [screens] are the top-level tabs; [routes] adds further
 * destinations (pass `{}` for none) next to the built-in host tools (files, snippets, forwards).
 * [terminal] and [showTerminal] are the full-screen terminal (see [BridleNavHost]); [overlay] is
 * drawn above navigation (connection dialogs), inside the lock so nothing shows while locked.
 * Onboarding's "Generate a key" and "Add a host" open those screens in the Keys and Hosts tabs
 * ([TabRequests]).
 */
@Composable
fun BridleApp(
    screens: Map<TopLevel, @Composable () -> Unit>,
    routes: NavGraphBuilder.(NavHostController) -> Unit,
    terminal: @Composable (onBack: () -> Unit) -> Unit = {},
    showTerminal: Flow<Unit> = emptyFlow(),
    overlay: @Composable () -> Unit = {},
) {
    val settings: SettingsViewModel = hiltViewModel()
    val nav = rememberNavController()
    val requests = remember { TabRequests() }
    val current by settings.settings.collectAsStateWithLifecycle()
    BridleTheme(current?.theme ?: ThemeMode.SYSTEM) {
        AppLockGate(hiltViewModel(), rememberBiometricUnlocker()) {
            OnboardingGate(
                onGenerateKey = { nav.openTab(TopLevel.KEYS, TabRequest.GENERATE_KEY, requests) },
                onAddHost = { nav.openTab(TopLevel.HOSTS, TabRequest.ADD_HOST, requests) },
                viewModel = hiltViewModel(),
            ) {
                CompositionLocalProvider(LocalTabRequests provides requests) {
                    BridleNavHost(
                        screens = screens,
                        nav = nav,
                        routes = {
                            sftpDestination(onBack = nav::popBackStack)
                            snippetsDestination(onBack = nav::popBackStack)
                            forwardsDestination(onBack = nav::popBackStack)
                            routes(nav)
                        },
                        terminal = terminal,
                        showTerminal = showTerminal,
                    )
                }
                overlay()
                CrashReportPrompt(hiltViewModel())
            }
        }
    }
}

/** Brings up [tab] and asks it for the screen [request] names (onboarding's calls to action). */
private fun NavHostController.openTab(
    tab: TopLevel,
    request: TabRequest,
    requests: TabRequests,
) {
    requests.request(request)
    // Single top: Hosts is usually showing already, and a second copy would race it for the request.
    navigate(tab.route) { launchSingleTop = true }
}
