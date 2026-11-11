package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.session.TerminalSession
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.terminal.PromptLine
import com.kitefoldlabs.bridle.core.terminal.ShellEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Suggestions for the command line [line], which they were computed from. */
public data class LineSuggestions(
    val line: String? = null,
    val items: List<PathSuggestion> = emptyList(),
)

/**
 * File-name suggestions for one terminal tab. It follows the shell's prompts through [engine]'s
 * shell integration events, and what is typed through the session it [follow]s, in a
 * [PromptLine]. It publishes [suggestions] for the path being typed, from [completer].
 *
 * Takes over [TerminalEngine.onShellEvent] and [TerminalSession.onInput]. Lives as long as the
 * tab; [close] it with the tab.
 */
public class CommandLineSuggestions(
    private val engine: TerminalEngine,
    private val completer: PathCompleter,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val debounce: Duration = DEFAULT_DEBOUNCE,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val line = PromptLine()
    private val snapshots = MutableStateFlow(line.snapshot)
    private val mutableSuggestions = MutableStateFlow(LineSuggestions())

    /** Suggestions for the line as last seen; empty while it is unknown or suggestions are off. */
    public val suggestions: StateFlow<LineSuggestions> = mutableSuggestions.asStateFlow()

    /**
     * True once the shell has sent an OSC 133 code: from then on the strip keeps its row, so the
     * terminal doesn't resize as suggestions come and go.
     */
    public val available: StateFlow<Boolean> =
        engine.shellPrompt
            .map { it.active }
            .stateIn(scope, SharingStarted.Eagerly, engine.shellPrompt.value.active)

    /** Whether to suggest at all (the user's setting). */
    public val enabled: MutableStateFlow<Boolean> = MutableStateFlow(true)

    init {
        line.onChange = { snapshots.value = it }
        engine.onShellEvent = { event ->
            // The last command may have changed files.
            if (event is ShellEvent.PromptStart) completer.invalidate()
            line.onShellEvent(event)
        }
        scope.launch {
            combine(snapshots, enabled) { snapshot, on -> snapshot.takeIf { on } }.collectLatest { snapshot ->
                val text = snapshot?.text
                if (text == null) {
                    mutableSuggestions.value = LineSuggestions()
                    return@collectLatest
                }
                delay(debounce) // typing fast: only the line it settles on is looked up
                val cwd = engine.shellPrompt.value.directory(snapshot.pane)
                mutableSuggestions.value = LineSuggestions(text, completer.suggest(text, cwd))
            }
        }
    }

    /**
     * Watches [session]: everything sent to it goes to the line model, and the line is forgotten
     * whenever the session isn't open (a drop or reattach leaves the shell's state unknown).
     */
    public fun follow(session: TerminalSession) {
        session.onInput = ::onInput
        followState(session.state)
    }

    /** Records input sent to the session (see [TerminalSession.onInput]). */
    internal fun onInput(bytes: ByteArray) = line.onInput(bytes)

    /** Forgets the line whenever [state] isn't [TerminalState.Open]. */
    internal fun followState(state: StateFlow<TerminalState>) {
        scope.launch {
            state.collect { if (it != TerminalState.Open) line.reset() }
        }
    }

    /**
     * The bytes to send to accept [suggestion], or null if the line has changed since it was
     * suggested (its insertion would then complete a different word).
     */
    public fun accept(suggestion: PathSuggestion): ByteArray? {
        val current = suggestions.value
        val text = line.snapshot.text
        if (text == null || text != current.line || suggestion !in current.items) return null
        return suggestion.insertion.toByteArray(Charsets.UTF_8)
    }

    override fun close() {
        scope.cancel()
        engine.onShellEvent = null
        completer.close()
    }

    public companion object {
        /** How long the line must stay unchanged before it is looked up. */
        public val DEFAULT_DEBOUNCE: Duration = 60.milliseconds
    }
}
