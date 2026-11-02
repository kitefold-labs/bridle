package com.kitefoldlabs.bridle.core.session

import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.tmux.ClaudeScreen
import com.kitefoldlabs.bridle.core.tmux.ClaudeState
import com.kitefoldlabs.bridle.core.tmux.ClaudeStatus
import com.kitefoldlabs.bridle.core.tmux.SessionSummary
import com.kitefoldlabs.bridle.core.tmux.StatusSource
import com.kitefoldlabs.bridle.core.tmux.TmuxController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A Claude session changed state in a way the user may want to hear about. */
public data class ClaudeStatusEvent(
    val hostId: EntityId,
    val session: String,
    val from: ClaudeState?,
    val to: ClaudeState,
    val message: String?,
)

/** Which transitions are worth a notification. */
public object NotificationPolicy {
    public fun shouldNotify(
        from: ClaudeState?,
        to: ClaudeState,
    ): Boolean =
        from != null &&
            from != to &&
            when (to) {
                ClaudeState.WAITING_PERMISSION -> true
                ClaudeState.WAITING_INPUT, ClaudeState.IDLE -> from == ClaudeState.WORKING
                ClaudeState.EXITED -> from != ClaudeState.UNKNOWN
                ClaudeState.WORKING, ClaudeState.UNKNOWN -> false
            }
}

/** How often to poll. Faster while the user is looking; slower from the background service. */
public enum class PollMode(
    public val interval: Duration,
) {
    FOREGROUND(5.seconds),
    BACKGROUND(45.seconds),
}

/**
 * Polls tmux (and bridle-helper, when installed) on one host and turns Claude state changes into
 * [events]. Without the helper, sessions that look like Claude get a screen-based guess.
 * The first poll establishes a baseline and never notifies.
 */
public class ClaudeStatusMonitor(
    private val hostId: EntityId,
    private val tmux: TmuxController,
    private val useHelper: () -> Boolean,
    private val claudeHint: (String) -> Boolean = { false },
) {
    private val mutableSessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    public val sessions: StateFlow<List<SessionSummary>> = mutableSessions.asStateFlow()

    private val mutableEvents = MutableSharedFlow<ClaudeStatusEvent>(extraBufferCapacity = EVENT_BUFFER)
    public val events: SharedFlow<ClaudeStatusEvent> = mutableEvents.asSharedFlow()

    private val mode = MutableStateFlow(PollMode.FOREGROUND)
    private var last: Map<String, ClaudeState>? = null

    public fun setMode(value: PollMode) {
        mode.value = value
    }

    /** Polls until cancelled. Errors are swallowed per round (the connection layer reports them). */
    public suspend fun run() {
        while (true) {
            try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") ignored: Exception,
            ) {
                // Transient (dropped connection, tmux restarting); try again next round.
            }
            // Wake early when the mode changes: opening the app shouldn't wait out a 45 s sleep.
            val current = mode.value
            withTimeoutOrNull(current.interval) { mode.first { it != current } }
        }
    }

    /** One refresh; exposed for pull-to-refresh and tests. */
    public suspend fun pollOnce() {
        val helper = useHelper()
        val listed = tmux.listSessions(useHelper = helper, claudeHint = claudeHint)
        val unreported = listed.filter { it.claude == null && it.claudeRunning }.map { it.session.name }
        // All the screens to guess from in one round trip, however many sessions there are.
        val screens = if (unreported.isEmpty()) emptyMap() else screens(unreported)
        val rows =
            listed.map { row ->
                val name = row.session.name
                if (name in unreported) row.copy(claude = guess(screens[name])) else row
            }
        mutableSessions.value = rows
        val current = rows.mapNotNull { row -> row.claude?.let { row.session.name to it } }.toMap()
        val previous = last
        last = current.mapValues { it.value.state }
        if (previous == null) return
        for ((name, status) in current) {
            val before = previous[name]
            if (NotificationPolicy.shouldNotify(before, status.state)) {
                mutableEvents.emit(ClaudeStatusEvent(hostId, name, before, status.state, status.message))
            }
        }
        for ((name, before) in previous) {
            if (name !in current && NotificationPolicy.shouldNotify(before, ClaudeState.EXITED)) {
                mutableEvents.emit(ClaudeStatusEvent(hostId, name, before, ClaudeState.EXITED, null))
            }
        }
    }

    private suspend fun screens(sessions: List<String>): Map<String, String> =
        try {
            tmux.previews(sessions)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: Exception,
        ) {
            emptyMap() // every guess is UNKNOWN this round
        }

    private fun guess(screen: String?): ClaudeStatus =
        screen?.let(ClaudeScreen::classify) ?: ClaudeStatus(ClaudeState.UNKNOWN, StatusSource.SCREEN)

    private companion object {
        const val EVENT_BUFFER = 64
    }
}
