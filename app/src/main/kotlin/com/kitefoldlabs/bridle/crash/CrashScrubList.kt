package com.kitefoldlabs.bridle.crash

import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.data.LaunchProfileRepository
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps [CrashReporter.sensitiveValues] up to date with what the user saved: host labels,
 * hostnames and IP addresses, usernames, tmux session names, Launch Profile names and working
 * directories. Many of these (a single-word hostname such as `devbox`, a username) can't be
 * recognised by shape, so the scrubber has to be told.
 *
 * The uncaught-exception handler only reads the latest snapshot (one volatile read): it never
 * touches the database, which may be what failed.
 */
@Singleton
class CrashScrubList(
    private val reporter: CrashReporter,
    private val values: Flow<Set<String>>,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        reporter: CrashReporter,
        hosts: HostRepository,
        profiles: LaunchProfileRepository,
        @ApplicationScope scope: CoroutineScope,
    ) : this(reporter, combine(hosts.observeAll(), profiles.observeAll(), ::valuesOf), scope)

    /** Follows the saved values for as long as the scope lives. */
    fun start(): Job =
        scope.launch {
            values
                // A database error must not take the app down: the last snapshot stays.
                .catch { }
                .collect { reporter.sensitiveValues = it }
        }

    companion object {
        internal fun valuesOf(
            hosts: List<Host>,
            profiles: List<LaunchProfile>,
        ): Set<String> =
            buildSet {
                for (host in hosts) {
                    add(host.label)
                    add(host.hostname)
                    // An IPv6 literal may be saved bracketed but appear bare in messages.
                    add(host.hostname.removeSurrounding("[", "]"))
                    add(host.username)
                    host.defaultTmuxSession?.let(::add)
                }
                for (profile in profiles) {
                    add(profile.name)
                    add(profile.sessionName)
                    add(profile.workingDir)
                }
            }
    }
}
