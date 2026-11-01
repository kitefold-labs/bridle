package com.kitefoldlabs.bridle.feature.sftp

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kitefoldlabs.bridle.core.model.EntityId

/** The SFTP file browser, opened from a host's overflow menu. */
object SftpDestination {
    const val ARG_HOST: String = "hostId"
    const val ROUTE: String = "hosts/{$ARG_HOST}/files"

    /** The route that opens the file browser on [hostId]. */
    fun route(hostId: EntityId): String = "hosts/$hostId/files"
}

/** Registers the file browser; [onBack] leaves it. */
fun NavGraphBuilder.sftpDestination(onBack: () -> Unit) {
    composable(
        SftpDestination.ROUTE,
        arguments =
            listOf(
                navArgument(SftpDestination.ARG_HOST) {
                    type = NavType.LongType
                },
            ),
    ) {
        SftpRoute(onBack)
    }
}

@Composable
internal fun SftpRoute(onBack: () -> Unit) {
    SftpScreen(hiltViewModel<SftpViewModel>(), onBack)
}
