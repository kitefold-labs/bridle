package com.kitefoldlabs.bridle.core.tmux

/** Parsers for the output of [TmuxScripts]. Malformed lines are skipped, never fatal. */
public object TmuxParsers {
    private const val SESSION_FIELDS = 5
    private const val PANE_FIELDS = 7

    public fun parseProbe(output: String): HostProbe {
        val values =
            output
                .lineSequence()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { (k, v) -> k to v.trim() }
        return HostProbe(
            tmuxVersion = values["tmux"]?.let(TmuxVersion::parse),
            claudePath = values["claude"]?.takeIf { it.isNotEmpty() },
            helperVersion = values["helper"]?.removePrefix("bridle-helper ")?.toIntOrNull(),
        )
    }

    /** Splits [TmuxScripts.listSessions] output into sessions and panes. */
    public fun parseList(output: String): Pair<List<TmuxSession>, List<TmuxPane>> {
        val lines = output.lines()
        val marker = lines.indexOf(TmuxScripts.PANES_MARKER)
        val sessionLines = if (marker < 0) lines else lines.subList(0, marker)
        val paneLines = if (marker < 0) emptyList() else lines.subList(marker + 1, lines.size)
        return sessionLines.mapNotNull(::parseSession) to paneLines.mapNotNull(::parsePane)
    }

    internal fun parseSession(line: String): TmuxSession? {
        val f = line.split(TmuxScripts.FIELD_SEPARATOR)
        if (f.size != SESSION_FIELDS || f[0].isEmpty()) return null
        val n = f.drop(1).mapNotNull { it.toLongOrNull() }
        if (n.size != SESSION_FIELDS - 1) return null
        val (created, attached, activity) = n
        return TmuxSession(f[0], created, attached.toInt(), activity, windows = n.last().toInt())
    }

    internal fun parsePane(line: String): TmuxPane? {
        val f = line.split(TmuxScripts.FIELD_SEPARATOR, limit = PANE_FIELDS)
        if (f.size != PANE_FIELDS || f[0].isEmpty() || !f[1].startsWith("%")) return null
        return TmuxPane(
            sessionName = f[0],
            paneId = f[1],
            active = f[2] == "1",
            windowActive = f[3] == "1",
            dead = f[4] == "1",
            currentCommand = f[5],
            currentPath = f[6],
        )
    }

    /** Process names Claude Code shows up as (native binary, or Node for npm installs). */
    private val claudeCommands = setOf("claude", "node")

    /**
     * Joins sessions with their active pane and helper status. [claudeHint] decides whether a
     * `node` pane counts as Claude (e.g. because a Launch Profile for that session runs `claude`).
     */
    public fun summarize(
        sessions: List<TmuxSession>,
        panes: List<TmuxPane>,
        helperStatus: Map<String, ClaudeStatus> = emptyMap(),
        claudeHint: (String) -> Boolean = { false },
    ): List<SessionSummary> {
        val activeBySession =
            panes
                .filter { it.active && it.windowActive }
                .associateBy { it.sessionName }
        return sessions
            .sortedByDescending { it.lastActivityEpochSeconds }
            .map { session ->
                val pane = activeBySession[session.name]
                val command = pane?.currentCommand
                val running =
                    pane != null &&
                        !pane.dead &&
                        (command == "claude" || (command in claudeCommands && claudeHint(session.name)))
                SessionSummary(session, pane, running, helperStatus[session.name])
            }
    }
}
