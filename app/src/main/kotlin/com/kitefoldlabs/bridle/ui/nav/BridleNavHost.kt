package com.kitefoldlabs.bridle.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/** The app's [NavHostController], for screens that open other destinations (e.g. a host's files). */
val LocalNavController = staticCompositionLocalOf<NavHostController?> { null }

/**
 * App navigation. [screens] supplies each top-level destination's content, so feature screens are
 * plugged in from one place and the scaffold can be tested on its own. [routes] registers the
 * other destinations (full-screen, without the bottom bar), such as a host's files or snippets.
 */
@Composable
fun BridleNavHost(
    screens: Map<TopLevel, @Composable () -> Unit> = emptyMap(),
    nav: NavHostController = rememberNavController(),
    routes: NavGraphBuilder.() -> Unit = {},
) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val topLevel = current == null || TopLevel.entries.any { it.route == current }
    Scaffold(
        bottomBar = {
            if (topLevel) BottomBar(nav, current)
        },
    ) { padding ->
        CompositionLocalProvider(LocalNavController provides nav) {
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
                routes()
            }
        }
    }
}

@Composable
private fun BottomBar(
    nav: NavHostController,
    current: String?,
) {
    NavigationBar {
        TopLevel.entries.forEach { dest ->
            NavigationBarItem(
                selected = current == dest.route,
                onClick = {
                    nav.navigate(dest.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(dest.icon, contentDescription = null) },
                label = { Text(stringResource(dest.label)) },
            )
        }
    }
}
