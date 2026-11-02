package com.kitefoldlabs.bridle.feature.forwards

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.data.PortForwardRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.model.Validators
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Fields of the forward editor that can be invalid. */
enum class ForwardField { BIND_PORT, DEST_HOST, DEST_PORT }

/** A forward being created (id 0) or edited; ports are kept as typed. */
data class ForwardDraft(
    val id: EntityId = 0,
    val type: PortForwardType = PortForwardType.LOCAL,
    val bindPort: String = "",
    val destHost: String = "localhost",
    val destPort: String = "",
    val autoStart: Boolean = false,
    val errors: Set<ForwardField> = emptySet(),
) {
    /** Fields that are invalid; empty when the draft can be saved. */
    fun validate(): Set<ForwardField> {
        val errors = mutableSetOf<ForwardField>()
        if (bindPort.toIntOrNull() !in MIN_LOCAL_PORT..Validators.MAX_PORT) errors += ForwardField.BIND_PORT
        if (type == PortForwardType.LOCAL) {
            if (Validators.hostname(destHost.trim()) != Validation.Valid) errors += ForwardField.DEST_HOST
            if (destPort.toIntOrNull() !in Validators.MIN_PORT..Validators.MAX_PORT) errors += ForwardField.DEST_PORT
        }
        return errors
    }

    /** The forward to save; call only when [validate] is empty. */
    fun toForward(hostId: EntityId): PortForward {
        val local = type == PortForwardType.LOCAL
        return PortForward(
            id = id,
            hostId = hostId,
            type = type,
            bindPort = bindPort.toInt(),
            destHost = if (local) destHost.trim() else null,
            destPort = if (local) destPort.toInt() else null,
            autoStart = autoStart,
        )
    }

    companion object {
        /** Android apps can't listen on privileged ports (below 1024). */
        const val MIN_LOCAL_PORT: Int = 1024

        fun from(forward: PortForward): ForwardDraft =
            ForwardDraft(
                id = forward.id,
                type = forward.type,
                bindPort = forward.bindPort.toString(),
                destHost = forward.destHost ?: "localhost",
                destPort = forward.destPort?.toString().orEmpty(),
                autoStart = forward.autoStart,
            )
    }
}

data class ForwardsUiState(
    val loaded: Boolean = false,
    val forwards: List<PortForward> = emptyList(),
    val statuses: Map<EntityId, ForwardStatus> = emptyMap(),
    val draft: ForwardDraft? = null,
)

/** The port forwards of one host: edit them, and start or stop them. */
@HiltViewModel
class ForwardsViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val repository: PortForwardRepository,
        private val manager: ForwardManager,
    ) : ViewModel() {
        val hostId: EntityId = checkNotNull(savedState.get<Long>(ForwardsDestination.ARG_HOST)) { "hostId missing" }
        private val mutable = MutableStateFlow(ForwardsUiState())
        val state: StateFlow<ForwardsUiState> = mutable.asStateFlow()

        init {
            viewModelScope.launch {
                repository.observeForHost(hostId).collect { list ->
                    mutable.update { it.copy(loaded = true, forwards = list) }
                }
            }
            viewModelScope.launch {
                manager.statuses.collect { statuses -> mutable.update { it.copy(statuses = statuses) } }
            }
        }

        fun create() = mutable.update { it.copy(draft = ForwardDraft()) }

        fun edit(forward: PortForward) = mutable.update { it.copy(draft = ForwardDraft.from(forward)) }

        fun updateDraft(change: (ForwardDraft) -> ForwardDraft) =
            mutable.update { state -> state.copy(draft = state.draft?.let(change)) }

        fun cancelDraft() = mutable.update { it.copy(draft = null) }

        /** Saves the draft; a running forward is restarted with the new settings. */
        fun saveDraft() {
            val draft = state.value.draft ?: return
            val errors = draft.validate()
            if (errors.isNotEmpty()) {
                updateDraft { it.copy(errors = errors) }
                return
            }
            val forward = draft.toForward(hostId)
            mutable.update { it.copy(draft = null) }
            viewModelScope.launch {
                val id = repository.upsert(forward)
                if (state.value.statuses.containsKey(id)) {
                    manager.stop(id)
                    manager.start(forward.copy(id = id))
                }
            }
        }

        fun delete(forward: PortForward) {
            manager.stop(forward.id)
            viewModelScope.launch { repository.delete(forward.id) }
        }

        fun setRunning(
            forward: PortForward,
            running: Boolean,
        ) = if (running) manager.start(forward) else manager.stop(forward.id)
    }
