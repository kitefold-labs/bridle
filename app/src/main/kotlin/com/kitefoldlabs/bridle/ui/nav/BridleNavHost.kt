package com.kitefoldlabs.bridle.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Route of the full-screen terminal destination (shown without the bottom bar). */
const val TERMINAL_ROUTE: String = "terminal"

/** The app's [NavHostController], for screens that open other destinations (e.g. a host's files). */
val LocalNavController = staticCompositionLocalOf<NavHostController?> { null }

/**
 * App navigation. [screens] supplies each top-level destination's content, so feature screens are
 * plugged in from one place and the scaffold can be tested on its own. [routes] registers the
 * other destinations (full-screen, without the bottom bar), such as a host's files or snippets.
 * [terminal] is the full-screen terminal destination (given a callback that goes back); it is
 * brought up each time [showTerminal] emits, returning to the one on the back stack if there is
 * one (closing the screens opened above it).
 */
@Composable
fun BridleNavHost(
    screens: Map<TopLevel, @Composable () -> Unit> = emptyMap(),
    nav: NavHostController = rememberNavController(),
    routes: NavGraphBuilder.() -> Unit = {},
    terminal: @Composable (onBack: () -> Unit) -> Unit = {},
    showTerminal: Flow<Unit> = emptyFlow(),
) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val topLevel = current == null || TopLevel.entries.any { it.route == current }
    LaunchedEffect(nav, showTerminal) {
        // Back to the terminal already on the stack, if any (e.g. under a host's snippets), not a second one.
        showTerminal.collect {
            nav.navigate(TERMINAL_ROUTE) {
                popUpTo(TERMINAL_ROUTE)
                launchSingleTop = true
            }
        }
    }
    Scaffold(
        bottomBar = {
            if (topLevel) BottomBar(nav, current)
        },
    ) { padding ->
        CompositionLocalProvider(LocalNavController provides nav) {
            // Consumed so screens that pad for insets themselves (the terminal) don't pad twice.
            NavHost(
                nav,
                startDestination = TopLevel.HOSTS.route,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            ) {
                TopLevel.entries.forEach { dest ->
                    composable(dest.route) {
                        val screen = screens[dest]
                        if (screen != null) {
                            screen()
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(dest.label))
                            }
                        }
                    }
                }
                composable(TERMINAL_ROUTE) { terminal { nav.popBackStack() } }
                routes()
            }
        }
    }
}

/**
 * Shows [tab], saving the tab being left and restoring [tab]'s own back stack. Every switch to a
 * top-level tab must go through here: a tab pushed without `popUpTo(start) { saveState = true }`
 * is saved under the start tab's state when the next switch pops it, so going back to the start
 * tab would restore that tab instead.
 */
fun NavHostController.openTab(tab: TopLevel) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BottomBar(
    nav: NavHostController,
    current: String?,
) {
    val colors = MaterialTheme.colorScheme
    // Accent on the selected icon over a soft accent pill; labels stay neutral.
    val itemColors =
        NavigationBarItemDefaults.colors(
            selectedIconColor = colors.primary,
            selectedTextColor = colors.onSurface,
            indicatorColor = colors.secondaryContainer,
            unselectedIconColor = colors.onSurfaceVariant,
            unselectedTextColor = colors.onSurfaceVariant,
        )
    NavigationBar(containerColor = colors.surfaceContainerLow, tonalElevation = 0.dp) {
        TopLevel.entries.forEach { dest ->
            NavigationBarItem(
                selected = current == dest.route,
                onClick = { nav.openTab(dest) },
                colors = itemColors,
                icon = { Icon(dest.icon, contentDescription = null) },
                label = { Text(stringResource(dest.label)) },
            )
        }
    }
}
