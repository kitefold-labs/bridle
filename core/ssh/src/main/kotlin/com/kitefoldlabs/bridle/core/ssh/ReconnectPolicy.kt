package com.kitefoldlabs.bridle.core.ssh

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential backoff with full jitter for reconnecting dropped sessions.
 *
 * Attempt 0 happens immediately (a network change usually means the new network is up). Later
 * attempts wait a random time in `[0, min(max, initial * 2^(n-1))]`, which avoids thundering
 * herds when many tabs reconnect at once. After [maxAttempts], [nextDelay] returns null and the
 * UI offers a manual "Reconnect" button instead of draining the battery.
 */
public class ReconnectPolicy(
    private val initial: Duration = 1.seconds,
    private val max: Duration = 30.seconds,
    private val maxAttempts: Int = 12,
    private val random: Random = Random.Default,
) {
    init {
        require(initial.isPositive() && max >= initial) { "Invalid backoff bounds" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    /** Delay before attempt number [attempt] (0-based), or null to give up. */
    public fun nextDelay(attempt: Int): Duration? {
        require(attempt >= 0) { "attempt must be >= 0" }
        return when {
            attempt >= maxAttempts -> null
            attempt == 0 -> Duration.ZERO
            else -> {
                val shift = (attempt - 1).coerceAtMost(MAX_SHIFT)
                val ceiling = minOf(max.inWholeMilliseconds, initial.inWholeMilliseconds shl shift)
                random.nextLong(0, ceiling + 1).milliseconds
            }
        }
    }

    private companion object {
        const val MAX_SHIFT = 30
    }
}
