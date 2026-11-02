package com.kitefoldlabs.bridle.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter

/** A screen inside a top-level tab's own navigation that the rest of the app can ask for. */
enum class TabRequest { GENERATE_KEY, ADD_HOST }

/**
 * One-shot requests for a screen inside a top-level tab, such as first-run onboarding's "Generate
 * a key" and "Add a host". The Keys and Hosts tabs keep their own back stacks, so the app's
 * navigation can only bring the tab up: the tab opens the screen itself ([OnTabRequest]). A
 * request waits until the tab is composed to see it, and is handled once.
 */
class TabRequests {
    internal val pending = MutableStateFlow<TabRequest?>(null)

    fun request(request: TabRequest) {
        pending.value = request
    }
}

/** The app's [TabRequests]; [com.kitefoldlabs.bridle.ui.BridleApp] provides it. */
val LocalTabRequests = staticCompositionLocalOf { TabRequests() }

/** Calls [open] for each [request] made through [LocalTabRequests], including one already waiting. */
@Composable
fun OnTabRequest(
    request: TabRequest,
    open: () -> Unit,
) {
    val requests = LocalTabRequests.current
    val currentOpen by rememberUpdatedState(open)
    LaunchedEffect(requests, request) {
        requests.pending.filter { it == request }.collect {
            requests.pending.compareAndSet(request, null)
            currentOpen()
        }
    }
}
