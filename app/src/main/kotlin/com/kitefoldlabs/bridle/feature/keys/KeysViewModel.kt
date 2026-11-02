package com.kitefoldlabs.bridle.feature.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.data.IdentityRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.security.KeyFingerprints
import com.kitefoldlabs.bridle.feature.hosts.HostsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One identity in the keys list. */
data class KeyRow(
    val identity: Identity,
    /** `SHA256:…`, or null if the stored public key can't be parsed. */
    val fingerprint: String?,
) {
    val isHardware: Boolean get() = identity.source == KeySource.KEYSTORE
}

data class KeysUiState(
    val loading: Boolean = true,
    val keys: List<KeyRow> = emptyList(),
)

/** Callbacks the keys list needs from its navigation. */
data class KeysNavigation(
    val onOpen: (EntityId) -> Unit,
    val onGenerate: () -> Unit,
    val onImport: () -> Unit,
    val onKnownHosts: () -> Unit,
)

/** The identities list. */
@HiltViewModel
class KeysViewModel
    @Inject
    constructor(
        identities: IdentityRepository,
    ) : ViewModel() {
        val state: StateFlow<KeysUiState> =
            identities
                .observeAll()
                .map { list -> KeysUiState(loading = false, keys = list.map { KeyRow(it, fingerprintOf(it)) }) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(HostsViewModel.STOP_TIMEOUT_MS), KeysUiState())
    }

/** [identity]'s fingerprint, or null if its stored public key is damaged. */
internal fun fingerprintOf(identity: Identity): String? =
    try {
        KeyFingerprints.sha256(identity)
    } catch (
        // sshj reports malformed key blobs with assorted runtime and I/O exceptions.
        @Suppress("TooGenericExceptionCaught") ignored: Exception,
    ) {
        null
    }
