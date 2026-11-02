package com.kitefoldlabs.bridle.feature.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.data.GroupRepository
import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.session.HostConnectionState
import com.kitefoldlabs.bridle.service.SessionCoordinator
import com.kitefoldlabs.bridle.ui.nav.HostTool
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** The coloured dot next to a host. */
enum class ConnectionDot { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }

/** Maps a connection state to its dot. */
fun HostConnectionState.dot(): ConnectionDot =
    when (this) {
        HostConnectionState.Idle -> ConnectionDot.IDLE
        HostConnectionState.Connecting -> ConnectionDot.CONNECTING
        is HostConnectionState.Connected -> ConnectionDot.CONNECTED
        is HostConnectionState.Reconnecting -> ConnectionDot.RECONNECTING
        is HostConnectionState.Failed -> ConnectionDot.FAILED
    }

/** One host in the list. */
data class HostRow(
    val host: Host,
    val dot: ConnectionDot,
)

/** What a section of the list holds. */
enum class SectionKind { FAVORITES, RECENT, GROUP, OTHER, RESULTS }

/**
 * A titled run of hosts. [groupName] and [groupId] are set for [SectionKind.GROUP]; group names
 * needn't be unique, so the list tells group sections apart by [groupId].
 */
data class HostSection(
    val kind: SectionKind,
    val rows: List<HostRow>,
    val groupName: String? = null,
    val groupId: EntityId? = null,
)

data class HostsUiState(
    val loading: Boolean = true,
    val query: String = "",
    /** True when there are no hosts at all (as opposed to no search results). */
    val noHosts: Boolean = false,
    val sections: List<HostSection> = emptyList(),
    val pendingDelete: Host? = null,
)

/** Callbacks the hosts list needs from its navigation. [onOpenTool] opens a host's files, snippets or forwards. */
data class HostsNavigation(
    val onOpenTerminal: (EntityId) -> Unit,
    val onAdd: () -> Unit,
    val onEdit: (EntityId) -> Unit,
    val onImport: () -> Unit,
    val onOpenTool: (EntityId, HostTool) -> Unit,
)

/** What the hosts list can ask its view model to do. */
interface HostsActions {
    fun setQuery(query: String)

    fun toggleFavorite(host: Host)

    fun requestDelete(host: Host)

    fun confirmDelete()

    fun cancelDelete()
}

/**
 * The hosts list: favourites, recently used hosts, then hosts by group; a search box flattens it
 * into one result list. Each host shows its shared connection's state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HostsViewModel
    @Inject
    constructor(
        private val hosts: HostRepository,
        groups: GroupRepository,
        coordinator: SessionCoordinator,
    ) : ViewModel(),
        HostsActions {
        private val query = MutableStateFlow("")
        private val pendingDelete = MutableStateFlow<Host?>(null)
        private val allHosts = hosts.observeAll()

        private val states: Flow<Map<EntityId, ConnectionDot>> =
            // ConnectionManager only creates a host's state when something connects to it, which
            // (for the UI) means opening a tab, so the lookups are refreshed whenever tabs change.
            combine(allHosts, coordinator.tabs) { list, _ -> list.map { it.id } }
                .flatMapLatest { ids -> dots(coordinator.connections, ids) }

        val state: StateFlow<HostsUiState> =
            combine(allHosts, groups.observeAll(), states, query, pendingDelete) { list, groupList, dots, q, delete ->
                HostsUiState(
                    loading = false,
                    query = q,
                    noHosts = list.isEmpty(),
                    sections = sections(list, groupList, dots, q),
                    pendingDelete = delete,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HostsUiState())

        override fun setQuery(query: String) {
            this.query.value = query
        }

        override fun toggleFavorite(host: Host) {
            viewModelScope.launch { hosts.upsert(host.copy(favorite = !host.favorite)) }
        }

        override fun requestDelete(host: Host) {
            pendingDelete.value = host
        }

        override fun confirmDelete() {
            val host = pendingDelete.value ?: return
            pendingDelete.update { null }
            viewModelScope.launch { hosts.delete(host.id) }
        }

        override fun cancelDelete() {
            pendingDelete.value = null
        }

        companion object {
            const val STOP_TIMEOUT_MS: Long = 5_000
            const val MAX_RECENT: Int = 5

            private fun dots(
                connections: ConnectionManager,
                ids: List<EntityId>,
            ): Flow<Map<EntityId, ConnectionDot>> =
                if (ids.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(ids.map { id -> connections.state(id).map { id to it.dot() } }) { it.toMap() }
                }

            /** Splits [hosts] (already in display order) into the list's sections. */
            internal fun sections(
                hosts: List<Host>,
                groups: List<Group>,
                dots: Map<EntityId, ConnectionDot>,
                query: String,
            ): List<HostSection> {
                fun row(host: Host) = HostRow(host, dots[host.id] ?: ConnectionDot.IDLE)
                if (query.isNotBlank()) {
                    val matches = hosts.filter { it.matches(query.trim()) }
                    return listOf(HostSection(SectionKind.RESULTS, matches.map(::row)))
                }
                val favorites = hosts.filter { it.favorite }
                val recent =
                    hosts
                        .filter { !it.favorite && it.lastConnectedAtEpochMs != null }
                        .sortedByDescending { it.lastConnectedAtEpochMs }
                        .take(MAX_RECENT)
                val rest = hosts - (favorites + recent).toSet()
                val byGroup = rest.groupBy { it.groupId }
                val knownGroups = groups.map { it.id }.toSet()
                return buildList {
                    add(HostSection(SectionKind.FAVORITES, favorites.map(::row)))
                    add(HostSection(SectionKind.RECENT, recent.map(::row)))
                    groups.forEach { group ->
                        val rows = byGroup[group.id].orEmpty().map(::row)
                        add(HostSection(SectionKind.GROUP, rows, group.name, group.id))
                    }
                    add(HostSection(SectionKind.OTHER, rest.filter { it.groupId !in knownGroups }.map(::row)))
                }.filter { it.rows.isNotEmpty() }
            }

            private fun Host.matches(query: String): Boolean {
                val needle = query.lowercase(Locale.ROOT)
                return listOf(label, hostname, username).any { it.lowercase(Locale.ROOT).contains(needle) } ||
                    tags.any { it.lowercase(Locale.ROOT).contains(needle) }
            }
        }
    }
