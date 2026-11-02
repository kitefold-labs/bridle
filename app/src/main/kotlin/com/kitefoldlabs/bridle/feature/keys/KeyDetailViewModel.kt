package com.kitefoldlabs.bridle.feature.keys

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.data.IdentityRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.feature.hosts.HostsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Progress of "Install on host". */
sealed interface InstallState {
    data object Idle : InstallState

    data class Running(
        val hostLabel: String,
    ) : InstallState

    data class Done(
        val hostLabel: String,
        val outcome: InstallOutcome,
    ) : InstallState
}

data class KeyDetailUiState(
    val loading: Boolean = true,
    val identity: Identity? = null,
    val fingerprint: String? = null,
    /** Hosts the key can be installed on. */
    val hosts: List<Host> = emptyList(),
    val install: InstallState = InstallState.Idle,
    val confirmDelete: Boolean = false,
    /** The identity is gone (deleted here or elsewhere): the screen should close. */
    val gone: Boolean = false,
)

/** What the key details screen can ask its view model to do. */
interface KeyDetailActions {
    fun install(hostId: EntityId)

    fun dismissInstall()

    fun requestDelete()

    fun confirmDelete()

    fun cancelDelete()
}

/** One identity: its public key (copy, share), "Install on host", and delete. */
@HiltViewModel
class KeyDetailViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        identities: IdentityRepository,
        hosts: HostRepository,
        private val library: KeyLibrary,
        private val installer: KeyInstaller,
    ) : ViewModel(),
        KeyDetailActions {
        private val identityId: EntityId = savedState.get<Long>(ARG_IDENTITY_ID) ?: 0L
        private val install = MutableStateFlow<InstallState>(InstallState.Idle)
        private val confirmDelete = MutableStateFlow(false)

        val state: StateFlow<KeyDetailUiState> =
            combine(
                identities.observeAll(),
                hosts.observeAll(),
                install,
                confirmDelete,
            ) { all, hostList, progress, delete ->
                val identity = all.firstOrNull { it.id == identityId }
                KeyDetailUiState(
                    loading = false,
                    identity = identity,
                    fingerprint = identity?.let(::fingerprintOf),
                    hosts = hostList,
                    install = progress,
                    confirmDelete = delete,
                    gone = identity == null,
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(HostsViewModel.STOP_TIMEOUT_MS),
                KeyDetailUiState(),
            )

        override fun install(hostId: EntityId) {
            val current = state.value
            val identity = current.identity ?: return
            val host = current.hosts.firstOrNull { it.id == hostId } ?: return
            if (install.value is InstallState.Running) return
            install.value = InstallState.Running(host.label)
            viewModelScope.launch {
                val outcome = installer.install(identity, hostId)
                install.value = InstallState.Done(host.label, outcome)
            }
        }

        override fun dismissInstall() {
            install.update { if (it is InstallState.Done) InstallState.Idle else it }
        }

        override fun requestDelete() {
            confirmDelete.value = true
        }

        override fun confirmDelete() {
            confirmDelete.value = false
            val identity = state.value.identity ?: return
            viewModelScope.launch { library.delete(identity) }
        }

        override fun cancelDelete() {
            confirmDelete.value = false
        }

        companion object {
            /** Navigation argument: the identity to show. */
            const val ARG_IDENTITY_ID: String = "identityId"
        }
    }
