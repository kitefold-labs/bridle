package com.kitefoldlabs.bridle.ui.nav

import androidx.annotation.StringRes
import androidx.navigation.NavController
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.feature.forwards.ForwardsDestination
import com.kitefoldlabs.bridle.feature.sftp.SftpDestination
import com.kitefoldlabs.bridle.feature.snippets.SnippetsDestination

/** A screen of another feature that works on one host, opened from that host's ⋮ menus. */
enum class HostTool(
    @param:StringRes val label: Int,
) {
    FILES(R.string.sftp_title),
    SNIPPETS(R.string.snippets_title),
    FORWARDS(R.string.forwards_title),
    ;

    /** The app route that opens this tool on [hostId]. */
    fun route(hostId: EntityId): String =
        when (this) {
            FILES -> SftpDestination.route(hostId)
            SNIPPETS -> SnippetsDestination.route(hostId)
            FORWARDS -> ForwardsDestination.route(hostId)
        }
}

/**
 * Opens [HostTool]s on the app's navigation. Feature tabs with a back stack of their own (Hosts)
 * and the terminal use it, with [LocalNavController] as [nav], since the tools are app-level
 * destinations. Without a nav controller (a screen shown on its own) it does nothing.
 */
class HostTools(
    private val nav: NavController?,
) {
    fun open(
        hostId: EntityId,
        tool: HostTool,
    ) {
        nav?.navigate(tool.route(hostId))
    }
}
