package com.kitefoldlabs.bridle.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.kitefoldlabs.bridle.R

/** Top-level destinations shown in the bottom bar. */
enum class TopLevel(
    val route: String,
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    HOSTS("hosts", R.string.nav_hosts, Icons.Filled.Computer),
    SESSIONS("sessions", R.string.nav_sessions, Icons.Filled.Terminal),
    KEYS("keys", R.string.nav_keys, Icons.Filled.Key),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
}
