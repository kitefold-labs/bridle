package com.kitefoldlabs.bridle.core.tmux

/**
 * Best-effort guess at what Claude Code is doing from the visible pane text (no helper needed).
 * Claude's TUI wording can change between versions, so results are always marked
 * [StatusSource.SCREEN] and shown as a hint, never used for anything destructive.
 */
public object ClaudeScreen {
    private val permission =
        listOf(
            Regex("""Do you want to (proceed|make this edit|create|allow)""", RegexOption.IGNORE_CASE),
            Regex("""❯\s*1\.\s*Yes""", RegexOption.IGNORE_CASE),
            Regex("""\(y/n\)""", RegexOption.IGNORE_CASE),
        )
    private val working = Regex("""esc to interrupt""", RegexOption.IGNORE_CASE)
    private val prompt = Regex("""^\s*[│|]?\s*>\s*.*$""", RegexOption.MULTILINE)

    /** Classifies the last screenful of text (bottom lines matter most). */
    public fun classify(screen: String): ClaudeStatus {
        val tail =
            screen
                .trimEnd()
                .lines()
                .takeLast(TAIL_LINES)
                .joinToString("\n")
        val state =
            when {
                permission.any { it.containsMatchIn(tail) } -> ClaudeState.WAITING_PERMISSION
                working.containsMatchIn(tail) -> ClaudeState.WORKING
                prompt.containsMatchIn(tail) -> ClaudeState.WAITING_INPUT
                else -> ClaudeState.UNKNOWN
            }
        return ClaudeStatus(state, StatusSource.SCREEN)
    }

    private const val TAIL_LINES = 25
}
