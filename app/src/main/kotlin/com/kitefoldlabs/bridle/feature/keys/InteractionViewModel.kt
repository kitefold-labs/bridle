package com.kitefoldlabs.bridle.feature.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.connect.Interaction
import com.kitefoldlabs.bridle.connect.InteractionBroker
import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.data.KnownHostRepository
import com.kitefoldlabs.bridle.core.session.HostConnectionState
import com.kitefoldlabs.bridle.core.ssh.HostKeyChangedException
import com.kitefoldlabs.bridle.service.SessionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the app-wide connection dialogs can ask their view model to do. */
interface InteractionActions {
    /** Answers [question]; ignored unless it is still the one pending. */
    fun trust(
        question: Interaction.TrustHost,
        trusted: Boolean,
    )

    /**
     * Answers [question] with [password] (null cancels). The array is handed over and zeroed by the
     * SSH layer, or zeroed here if [question] is no longer pending.
     */
    fun password(
        question: Interaction.Password,
        password: CharArray?,
    )

    /** Forgets the old key of the host in the current warning; the next connection asks to trust the new one. */
    fun forgetOldKey()

    fun dismissKeyChanged()
}

/**
 * Drives the dialogs the connection layer needs, wherever in the app a connection is made:
 * trusting a new host key, typing a password, and the full-screen "host key changed" warning.
 * Connection failures with a changed host key are picked up from [SessionCoordinator]'s shared
 * connections as well as from [HostKeyAlerts.report].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InteractionViewModel
    @Inject
    constructor(
        private val broker: InteractionBroker,
        private val alerts: HostKeyAlerts,
        private val knownHosts: KnownHostRepository,
        hosts: HostRepository,
        coordinator: SessionCoordinator,
    ) : ViewModel(),
        InteractionActions {
        /** The question to show now, if any. */
        val pending: StateFlow<Interaction?> = broker.pending

        /** The host-key warning to show now, if any. */
        val keyChanged: StateFlow<HostKeyChangedException?> = alerts.current

        init {
            combine(hosts.observeAll(), coordinator.tabs) { list, _ -> list.map { it.id } }
                .flatMapLatest { ids ->
                    val failures: List<Flow<HostKeyChangedException>> =
                        ids.map { id ->
                            coordinator.connections.state(id).mapNotNull {
                                (it as? HostConnectionState.Failed)?.error as? HostKeyChangedException
                            }
                        }
                    failures.merge()
                }.onEach(alerts::report)
                .launchIn(viewModelScope)
        }

        override fun trust(
            question: Interaction.TrustHost,
            trusted: Boolean,
        ) {
            // The broker drops answers to questions that are no longer pending, so a dialog left
            // up after its question timed out can never answer the next one.
            broker.answer(question, trusted)
        }

        override fun password(
            question: Interaction.Password,
            password: CharArray?,
        ) {
            // A stale answer is dropped and zeroed by the broker.
            broker.answer(question, password)
        }

        override fun forgetOldKey() {
            val error = alerts.current.value ?: return
            alerts.dismiss()
            viewModelScope.launch { knownHosts.forget(error.host, error.port) }
        }

        override fun dismissKeyChanged() {
            alerts.dismiss()
        }
    }
