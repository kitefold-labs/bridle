package com.kitefoldlabs.bridle.feature.forwards

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kitefoldlabs.bridle.core.model.EntityId

/** A host's port forwards, opened from the host's overflow menu. */
object ForwardsDestination {
    const val ARG_HOST: String = "hostId"
    const val ROUTE: String = "hosts/{$ARG_HOST}/forwards"

    /** The route that opens the port forwards of [hostId]. */
    fun route(hostId: EntityId): String = "hosts/$hostId/forwards"
}

/** Registers the port-forwards screen; [onBack] leaves it. */
fun NavGraphBuilder.forwardsDestination(onBack: () -> Unit) {
    composable(
        ForwardsDestination.ROUTE,
        arguments = listOf(navArgument(ForwardsDestination.ARG_HOST) { type = NavType.LongType }),
    ) {
        ForwardsRoute(onBack)
    }
}

@Composable
internal fun ForwardsRoute(onBack: () -> Unit) {
    ForwardsScreen(hiltViewModel<ForwardsViewModel>(), onBack)
}
