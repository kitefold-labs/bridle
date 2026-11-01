package com.kitefoldlabs.bridle.feature.hosts

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kitefoldlabs.bridle.core.model.EntityId

private const val ROUTE_LIST = "hosts/list"
private const val ROUTE_EDIT = "hosts/edit/{${HostEditorViewModel.ARG_HOST_ID}}"
private const val ROUTE_IMPORT = "hosts/import"

private fun editRoute(id: EntityId) = "hosts/edit/$id"

/**
 * The Hosts tab: the host list, the host editor and the `~/.ssh/config` import, on their own
 * back stack. Tapping a host calls [onOpenTerminal], which the terminal feature provides.
 */
@Composable
fun HostsFeature(onOpenTerminal: (EntityId) -> Unit) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack(ROUTE_LIST, inclusive = false) }
    NavHost(nav, startDestination = ROUTE_LIST) {
        composable(ROUTE_LIST) {
            HostsRoute(
                hiltViewModel(),
                HostsNavigation(
                    onOpenTerminal = onOpenTerminal,
                    onAdd = { nav.navigate(editRoute(0)) },
                    onEdit = { nav.navigate(editRoute(it)) },
                    onImport = { nav.navigate(ROUTE_IMPORT) },
                ),
            )
        }
        composable(ROUTE_EDIT, listOf(navArgument(HostEditorViewModel.ARG_HOST_ID) { type = NavType.LongType })) {
            HostEditorRoute(hiltViewModel(), onDone = back)
        }
        composable(ROUTE_IMPORT) { SshConfigImportRoute(hiltViewModel(), onDone = back) }
    }
}
