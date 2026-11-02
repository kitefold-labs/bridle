package com.kitefoldlabs.bridle.feature.hosts

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.data.GroupRepository
import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.data.IdentityRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.model.Validators
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HostEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    /** Editing a host that no longer exists. */
    val notFound: Boolean = false,
    val form: HostForm = HostForm(),
    /** Errors to show now: for fields the user has edited, or all of them after a save attempt. */
    val errors: Map<HostField, Validation.Reason> = emptyMap(),
    val identities: List<Identity> = emptyList(),
    /** Hosts that can be the jump host: never this host or one that already jumps through it. */
    val jumpCandidates: List<Host> = emptyList(),
    val groups: List<Group> = emptyList(),
    val saving: Boolean = false,
    /** The key, jump host or group picked was deleted meanwhile. */
    val missingReference: Boolean = false,
    val confirmDelete: Boolean = false,
    /** Saved or deleted: the screen should close. */
    val finished: Boolean = false,
)

/** What the host editor can ask its view model to do. */
interface HostEditorActions {
    /** Applies [change] to the form; [field], if given, starts showing its error. */
    fun edit(
        field: HostField?,
        change: (HostForm) -> HostForm,
    )

    fun save()

    /** Creates a group named [name] and selects it. */
    fun createGroup(name: String)

    fun requestDelete()

    fun confirmDelete()

    fun cancelDelete()
}

/** Adds or edits one host. The `hostId` navigation argument is 0 for a new host. */
@HiltViewModel
class HostEditorViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val hosts: HostRepository,
        private val groups: GroupRepository,
        identities: IdentityRepository,
    ) : ViewModel(),
        HostEditorActions {
        private val hostId: EntityId = savedState.get<Long>(ARG_HOST_ID) ?: 0L

        private data class Editor(
            val loading: Boolean,
            val base: Host? = null,
            val notFound: Boolean = false,
            val form: HostForm = HostForm(),
            val touched: Set<HostField> = emptySet(),
            val showAllErrors: Boolean = false,
            val saving: Boolean = false,
            val missingReference: Boolean = false,
            val confirmDelete: Boolean = false,
            val finished: Boolean = false,
        ) {
            /** A host is loaded (or new) and not being saved. */
            val ready: Boolean get() = !loading && !notFound && !saving
        }

        private val editor = MutableStateFlow(Editor(loading = hostId != 0L))

        val state: StateFlow<HostEditorUiState> =
            combine(
                editor,
                identities.observeAll(),
                hosts.observeAll(),
                groups.observeAll(),
            ) { e, keys, all, groupList ->
                val errors = e.form.errors()
                HostEditorUiState(
                    loading = e.loading,
                    isNew = hostId == 0L,
                    notFound = e.notFound,
                    form = e.form,
                    errors = errors.filterKeys { e.showAllErrors || it in e.touched },
                    identities = keys,
                    jumpCandidates = jumpCandidates(hostId, all),
                    groups = groupList,
                    saving = e.saving,
                    missingReference = e.missingReference,
                    confirmDelete = e.confirmDelete,
                    finished = e.finished,
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(HostsViewModel.STOP_TIMEOUT_MS),
                HostEditorUiState(),
            )

        init {
            if (hostId != 0L) {
                viewModelScope.launch {
                    val host = hosts.get(hostId)
                    editor.update {
                        if (host == null) {
                            it.copy(loading = false, notFound = true)
                        } else {
                            it.copy(loading = false, base = host, form = HostForm.of(host))
                        }
                    }
                }
            }
        }

        override fun edit(
            field: HostField?,
            change: (HostForm) -> HostForm,
        ) {
            editor.update { e ->
                e.copy(form = change(e.form), touched = if (field == null) e.touched else e.touched + field)
            }
        }

        override fun save() {
            val current = editor.value
            if (!current.ready || current.finished) return
            if (current.form.errors().isNotEmpty()) {
                editor.update { it.copy(showAllErrors = true) }
                return
            }
            editor.update { it.copy(saving = true, missingReference = false) }
            viewModelScope.launch {
                val saved =
                    try {
                        hosts.upsert(current.form.toHost(current.base))
                        true
                    } catch (ignored: SQLiteConstraintException) {
                        false
                    }
                editor.update { it.copy(saving = false, missingReference = !saved, finished = saved) }
            }
        }

        override fun createGroup(name: String) {
            if (Validators.label(name.trim()) != Validation.Valid) return
            viewModelScope.launch {
                val id = groups.upsert(Group(name = name.trim()))
                edit(null) { it.copy(groupId = id) }
            }
        }

        override fun requestDelete() {
            if (hostId != 0L) editor.update { it.copy(confirmDelete = true) }
        }

        override fun confirmDelete() {
            editor.update { it.copy(confirmDelete = false) }
            viewModelScope.launch {
                hosts.delete(hostId)
                editor.update { it.copy(finished = true) }
            }
        }

        override fun cancelDelete() {
            editor.update { it.copy(confirmDelete = false) }
        }

        companion object {
            /** Navigation argument: the host to edit, 0 for a new one. */
            const val ARG_HOST_ID: String = "hostId"

            /** [all] minus [selfId] and every host whose ProxyJump chain passes through it. */
            internal fun jumpCandidates(
                selfId: EntityId,
                all: List<Host>,
            ): List<Host> {
                val byId = all.associateBy { it.id }

                fun reachesSelf(host: Host): Boolean {
                    val seen = mutableSetOf<EntityId>()
                    var current: Host? = host
                    while (current != null && seen.add(current.id)) {
                        if (current.id == selfId) return true
                        current = current.jumpHostId?.let(byId::get)
                    }
                    return false
                }
                return all.filterNot(::reachesSelf)
            }
        }
    }
