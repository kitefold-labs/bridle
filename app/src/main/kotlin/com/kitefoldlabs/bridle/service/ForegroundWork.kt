package com.kitefoldlabs.bridle.service

import com.kitefoldlabs.bridle.feature.sftp.TransferQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** What keeps [SessionService] in the foreground: open terminal tabs and unfinished transfers. */
data class ActiveWork(
    val tabs: Int = 0,
    val transfers: Int = 0,
) {
    val isIdle: Boolean get() = tabs == 0 && transfers == 0
}

/**
 * The work [SessionService] follows. Kept out of the service (a Hilt entry point) so it is tested
 * and measured like the rest.
 */
@Singleton
class ForegroundWork
    @Inject
    constructor(
        private val coordinator: SessionCoordinator,
        private val transfers: TransferQueue,
    ) {
        val current: ActiveWork
            get() = ActiveWork(coordinator.tabs.value.size, transfers.transfers.value.count { it.isActive })

        /** [current] each time it changes, starting with its value now. */
        val changes: Flow<ActiveWork> =
            combine(coordinator.tabs, transfers.transfers) { tabs, queue ->
                ActiveWork(tabs.size, queue.count { it.isActive })
            }.distinctUntilChanged()
    }
