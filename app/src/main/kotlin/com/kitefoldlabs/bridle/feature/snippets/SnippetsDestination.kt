package com.kitefoldlabs.bridle.feature.snippets

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kitefoldlabs.bridle.core.model.EntityId

/** Snippets run against one host, opened from the host's overflow menu. */
object SnippetsDestination {
    const val ARG_HOST: String = "hostId"
    const val ROUTE: String = "hosts/{$ARG_HOST}/snippets"

    /** The route that opens the snippets screen for [hostId]. */
    fun route(hostId: EntityId): String = "hosts/$hostId/snippets"
}

/** Registers the snippets screen; [onBack] leaves it. */
fun NavGraphBuilder.snippetsDestination(onBack: () -> Unit) {
    composable(
        SnippetsDestination.ROUTE,
        arguments = listOf(navArgument(SnippetsDestination.ARG_HOST) { type = NavType.LongType }),
    ) {
        SnippetsRoute(onBack)
    }
}

@Composable
internal fun SnippetsRoute(onBack: () -> Unit) {
    SnippetsScreen(hiltViewModel<SnippetsViewModel>(), onBack)
}
