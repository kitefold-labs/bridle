package com.kitefoldlabs.bridle.feature.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.data.KnownHostRepository
import com.kitefoldlabs.bridle.core.model.KnownHost
import com.kitefoldlabs.bridle.feature.hosts.HostsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KnownHostsUiState(
    val loading: Boolean = true,
    val hosts: List<KnownHost> = emptyList(),
    val pendingForget: KnownHost? = null,
)

/** What the known-hosts screen can ask its view model to do. */
interface KnownHostsActions {
    fun requestForget(host: KnownHost)

    fun confirmForget()

    fun cancelForget()
}

/** Trusted host keys; forgetting one makes the next connection ask again. */
@HiltViewModel
class KnownHostsViewModel
    @Inject
    constructor(
        private val knownHosts: KnownHostRepository,
    ) : ViewModel(),
        KnownHostsActions {
        private val pendingForget = MutableStateFlow<KnownHost?>(null)

        val state: StateFlow<KnownHostsUiState> =
            combine(knownHosts.observeAll(), pendingForget) { list, forget ->
                KnownHostsUiState(loading = false, hosts = list, pendingForget = forget)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(HostsViewModel.STOP_TIMEOUT_MS),
                KnownHostsUiState(),
            )

        override fun requestForget(host: KnownHost) {
            pendingForget.value = host
        }

        override fun confirmForget() {
            val host = pendingForget.value ?: return
            pendingForget.value = null
            viewModelScope.launch { knownHosts.delete(host.host, host.port, host.keyType) }
        }

        override fun cancelForget() {
            pendingForget.value = null
        }
    }
