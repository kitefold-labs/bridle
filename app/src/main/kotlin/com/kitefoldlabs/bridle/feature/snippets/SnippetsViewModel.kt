package com.kitefoldlabs.bridle.feature.snippets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.data.SnippetRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Snippet
import com.kitefoldlabs.bridle.core.model.SnippetRunMode
import com.kitefoldlabs.bridle.core.model.SnippetVariable
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

/** A snippet being created (id 0) or edited. */
data class SnippetDraft(
    val id: EntityId = 0,
    val name: String = "",
    val body: String = "",
    val runMode: SnippetRunMode = SnippetRunMode.PASTE,
    val packageName: String = "",
    val nameMissing: Boolean = false,
) {
    val variables: List<SnippetVariable> get() = Snippet(name = name, body = body).variables

    fun toSnippet(): Snippet =
        Snippet(
            id = id,
            name = name.trim(),
            body = body,
            runMode = runMode,
            packageName = packageName.trim().ifEmpty { null },
        )

    companion object {
        fun from(snippet: Snippet): SnippetDraft =
            SnippetDraft(snippet.id, snippet.name, snippet.body, snippet.runMode, snippet.packageName.orEmpty())
    }
}

/** The run dialog: variable values, then the exec result if run that way. */
data class SnippetRun(
    val snippet: Snippet,
    val values: Map<String, String>,
    val running: Boolean = false,
    val result: ExecResult? = null,
    /** [result]'s output, cleaned up and shortened for display. */
    val output: ExecOutput? = null,
    val error: String? = null,
) {
    /** The command as it will be typed or run. */
    val command: String get() = snippet.render(values)
}

enum class SnippetNotice { PASTED, NO_TERMINAL, SAVED, DELETED }

data class SnippetsUiState(
    val loaded: Boolean = false,
    val snippets: List<Snippet> = emptyList(),
    val draft: SnippetDraft? = null,
    val run: SnippetRun? = null,
    val notice: SnippetNotice? = null,
)

/** Saved snippets, run against one host (pasted into its terminal or run with `exec`). */
@HiltViewModel
class SnippetsViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val repository: SnippetRepository,
        private val connections: ConnectionManager,
        target: Optional<SnippetTarget>,
    ) : ViewModel() {
        val hostId: EntityId = checkNotNull(savedState.get<Long>(SnippetsDestination.ARG_HOST)) { "hostId missing" }
        private val target: SnippetTarget = target.orElse(SnippetTarget.None)
        private val mutable = MutableStateFlow(SnippetsUiState())
        val state: StateFlow<SnippetsUiState> = mutable.asStateFlow()
        private var execJob: Job? = null

        init {
            viewModelScope.launch {
                repository.observeAll().collect { list -> mutable.update { it.copy(loaded = true, snippets = list) } }
            }
        }

        fun create() = mutable.update { it.copy(draft = SnippetDraft()) }

        fun edit(snippet: Snippet) = mutable.update { it.copy(draft = SnippetDraft.from(snippet)) }

        fun updateDraft(change: (SnippetDraft) -> SnippetDraft) =
            mutable.update { state -> state.copy(draft = state.draft?.let(change)) }

        fun cancelDraft() = mutable.update { it.copy(draft = null) }

        fun saveDraft() {
            val draft = state.value.draft ?: return
            if (draft.name.isBlank()) {
                updateDraft { it.copy(nameMissing = true) }
                return
            }
            viewModelScope.launch {
                repository.upsert(draft.toSnippet())
                mutable.update { it.copy(draft = null, notice = SnippetNotice.SAVED) }
            }
        }

        fun delete(snippet: Snippet) {
            viewModelScope.launch {
                repository.delete(snippet.id)
                mutable.update { it.copy(notice = SnippetNotice.DELETED) }
            }
        }

        /** Opens the run dialog with each variable set to its default. */
        fun startRun(snippet: Snippet) {
            stopExec()
            val values = snippet.variables.associate { it.name to it.default.orEmpty() }
            mutable.update { it.copy(run = SnippetRun(snippet, values)) }
        }

        fun setValue(
            name: String,
            value: String,
        ) = updateRun { it.copy(values = it.values + (name to value)) }

        /** Closes the run dialog, stopping a command that is still running. */
        fun closeRun() {
            stopExec()
            mutable.update { it.copy(run = null) }
        }

        /** Types the command into this host's terminal (the user presses Enter there). */
        fun paste() {
            val run = state.value.run ?: return
            viewModelScope.launch {
                val pasted = target.paste(hostId, run.command)
                mutable.update {
                    it.copy(
                        run = if (pasted) null else it.run,
                        notice = if (pasted) SnippetNotice.PASTED else SnippetNotice.NO_TERMINAL,
                    )
                }
            }
        }

        /**
         * Runs the command with `exec` on the host's shared connection and shows its output. The
         * command is the user's own snippet with the values they typed, so it is run exactly as
         * written, just as if they had typed it into a terminal.
         */
        fun exec() {
            val run = state.value.run ?: return
            stopExec()
            updateRun { it.copy(running = true, result = null, output = null, error = null) }
            // Cancelled when the dialog closes or another snippet is opened: its result must not
            // land in another snippet's dialog, nor keep the command (and the lease) going.
            execJob =
                viewModelScope.launch {
                    try {
                        val result =
                            connections.acquire(hostId).use { lease ->
                                lease.awaitConnection().exec(run.command)
                            }
                        updateRun { it.copy(running = false, result = result, output = ExecOutput.of(result)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        updateRun { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
                    }
                }
        }

        fun consumeNotice() = mutable.update { it.copy(notice = null) }

        private fun stopExec() {
            execJob?.cancel()
            execJob = null
        }

        private fun updateRun(change: (SnippetRun) -> SnippetRun) =
            mutable.update { state -> state.copy(run = state.run?.let(change)) }
    }
