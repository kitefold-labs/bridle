package com.kitefoldlabs.bridle.feature.keys

import com.kitefoldlabs.bridle.core.ssh.HostKeyChangedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "host key changed" warning currently on screen. Any feature whose connection fails with
 * [HostKeyChangedException] reports it here; the app root shows the full-screen warning. Each
 * exception is shown once, so a failure that stays in a connection state isn't re-shown after
 * the user dismissed it.
 */
@Singleton
class HostKeyAlerts
    @Inject
    constructor() {
        private val mutableCurrent = MutableStateFlow<HostKeyChangedException?>(null)
        private val seen: MutableSet<HostKeyChangedException> =
            Collections.synchronizedSet(
                Collections.newSetFromMap(WeakHashMap()),
            )

        val current: StateFlow<HostKeyChangedException?> = mutableCurrent.asStateFlow()

        /** Shows the warning for [error] unless it was already shown. */
        fun report(error: HostKeyChangedException) {
            if (seen.add(error)) mutableCurrent.value = error
        }

        fun dismiss() {
            mutableCurrent.value = null
        }
    }
