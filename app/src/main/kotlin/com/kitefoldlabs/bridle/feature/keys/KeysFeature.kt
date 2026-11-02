package com.kitefoldlabs.bridle.feature.keys

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kitefoldlabs.bridle.core.model.EntityId

private const val ROUTE_LIST = "keys/list"
private const val ROUTE_GENERATE = "keys/generate"
private const val ROUTE_IMPORT = "keys/import"
private const val ROUTE_DETAIL = "keys/detail/{${KeyDetailViewModel.ARG_IDENTITY_ID}}"
private const val ROUTE_KNOWN_HOSTS = "keys/known-hosts"

private fun detailRoute(id: EntityId) = "keys/detail/$id"

/** Shows a new key's details in place of the screen that created it. */
private fun NavHostController.showCreated(id: EntityId) {
    navigate(detailRoute(id)) { popUpTo(ROUTE_LIST) }
}

/** The Keys tab: identities, generate, import, key details, and trusted host keys. */
@Composable
fun KeysFeature() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack(ROUTE_LIST, inclusive = false) }
    NavHost(nav, startDestination = ROUTE_LIST) {
        composable(ROUTE_LIST) {
            KeysRoute(
                hiltViewModel(),
                KeysNavigation(
                    onOpen = { nav.navigate(detailRoute(it)) },
                    onGenerate = { nav.navigate(ROUTE_GENERATE) },
                    onImport = { nav.navigate(ROUTE_IMPORT) },
                    onKnownHosts = { nav.navigate(ROUTE_KNOWN_HOSTS) },
                ),
            )
        }
        composable(ROUTE_GENERATE) { KeyGenerateRoute(hiltViewModel(), onBack = back, onCreated = nav::showCreated) }
        composable(ROUTE_IMPORT) { KeyImportRoute(hiltViewModel(), onBack = back, onCreated = nav::showCreated) }
        composable(ROUTE_DETAIL, listOf(navArgument(KeyDetailViewModel.ARG_IDENTITY_ID) { type = NavType.LongType })) {
            KeyDetailRoute(hiltViewModel(), onBack = back)
        }
        composable(ROUTE_KNOWN_HOSTS) { KnownHostsRoute(onBack = back) }
    }
}
