package com.kitefoldlabs.bridle.core.tmux

/** Parsed `tmux -V` output. Development builds (`tmux master`, `next-3.6`) are treated as new. */
public data class TmuxVersion(
    val major: Int,
    val minor: Int,
) : Comparable<TmuxVersion> {
    override fun compareTo(other: TmuxVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor })

    override fun toString(): String = "$major.$minor"

    public companion object {
        /** Oldest tmux Bridle supports (respawn-pane -c, window-size, `=name` targets). */
        public val MINIMUM: TmuxVersion = TmuxVersion(3, 0)

        /** First tmux whose `new-session` takes `-e NAME=value`. */
        public val SESSION_ENV_FLAG: TmuxVersion = TmuxVersion(3, 2)
        private val DEVELOPMENT = TmuxVersion(99, 0)
        private val pattern = Regex("""(\d+)\.(\d+)""")

        /**
         * OpenBSD's base tmux reports the OS release (`tmux openbsd-7.4`), not its own version:
         * the oldest OpenBSD release that shipped each tmux version Bridle checks for. Older
         * releases count as too old.
         */
        private val openBsd =
            listOf(
                TmuxVersion(6, 9) to SESSION_ENV_FLAG,
                TmuxVersion(6, 7) to TmuxVersion(3, 1),
                TmuxVersion(6, 6) to MINIMUM,
            )
        private val TOO_OLD = TmuxVersion(2, 9)
        private val openBsdPattern = Regex("""^tmux openbsd-(\d+)\.(\d+)""")

        public fun parse(output: String): TmuxVersion? {
            val text = output.trim()
            val openBsdRelease = openBsdPattern.find(text)
            return when {
                !text.startsWith("tmux ") -> null
                text == "tmux master" -> DEVELOPMENT
                openBsdRelease != null ->
                    of(openBsdRelease)?.let { os ->
                        openBsd.firstOrNull { (since, _) -> os >= since }?.second ?: TOO_OLD
                    }
                else -> pattern.find(text)?.let(::of)
            }
        }

        /** A version from a match's two groups; `null` if a number doesn't fit an Int. */
        private fun of(match: MatchResult): TmuxVersion? {
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            return TmuxVersion(major, minor)
        }
    }
}

/** What a host offers, discovered once per connection. */
public data class HostProbe(
    val tmuxVersion: TmuxVersion?,
    val claudePath: String?,
    val helperVersion: Int?,
    /** Shells whose rc file sources Bridle's shell integration. */
    val shellIntegration: Set<IntegratedShell> = emptySet(),
) {
    val tmuxSupported: Boolean get() = tmuxVersion != null && tmuxVersion >= TmuxVersion.MINIMUM
}

public data class TmuxSession(
    val name: String,
    val createdEpochSeconds: Long,
    val attachedClients: Int,
    val lastActivityEpochSeconds: Long,
    val windows: Int,
)

public data class TmuxPane(
    val sessionName: String,
    val paneId: String,
    val active: Boolean,
    val windowActive: Boolean,
    val dead: Boolean,
    val currentCommand: String,
    val currentPath: String,
)

/** Claude Code activity as reported by the helper's hooks or guessed from the screen. */
public enum class ClaudeState {
    WORKING,
    WAITING_PERMISSION,
    WAITING_INPUT,
    IDLE,
    EXITED,
    UNKNOWN,
}

public enum class StatusSource {
    /** From Claude Code hooks via bridle-helper: authoritative. */
    HOOKS,

    /** Inferred from the visible pane text: a heuristic, labelled as such in the UI. */
    SCREEN,
}

public data class ClaudeStatus(
    val state: ClaudeState,
    val source: StatusSource,
    val message: String? = null,
    val updatedEpochSeconds: Long? = null,
)

/** One row in the session browser. */
public data class SessionSummary(
    val session: TmuxSession,
    /** The active pane of the active window, if any. */
    val activePane: TmuxPane?,
    val claudeRunning: Boolean,
    val claude: ClaudeStatus?,
)
