package com.kitefoldlabs.bridle.feature.hosts

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.ui.nav.HostTool
import com.kitefoldlabs.bridle.ui.theme.CreateFab
import com.kitefoldlabs.bridle.ui.theme.LocalStatusColors
import com.kitefoldlabs.bridle.ui.theme.StatusColors

@Composable
internal fun HostsRoute(
    viewModel: HostsViewModel,
    navigation: HostsNavigation,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HostsScreen(state, viewModel, navigation)
}

/*
 * Callbacks built here are @DontMemoize: they only forward to the view model or navigation, so
 * re-creating them on recomposition is cheap, and remembering them would add compiler-generated
 * branches (one per captured parameter) that no test can tell apart.
 */

/** The saved hosts, searchable, with each host's connection state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    state: HostsUiState,
    actions: HostsActions,
    navigation: HostsNavigation,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hosts_title)) },
                actions = {
                    IconButton(onClick = navigation.onImport) {
                        Icon(Icons.Filled.FileUpload, contentDescription = stringResource(R.string.hosts_import))
                    }
                },
            )
        },
        floatingActionButton = {
            CreateFab(onClick = navigation.onAdd) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.hosts_add))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.loading && state.noHosts) {
                EmptyHosts()
            } else {
                SearchField(state.query, @DontMemoize { query -> actions.setQuery(query) })
                HostList(state.sections, actions, navigation)
            }
        }
    }
    state.pendingDelete?.let { host ->
        ConfirmDialog(
            title = stringResource(R.string.hosts_delete_title, host.label),
            text = stringResource(R.string.hosts_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = @DontMemoize { actions.confirmDelete() },
            onDismiss = @DontMemoize { actions.cancelDelete() },
        )
    }
}

@Composable
private fun EmptyHosts() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.hosts_empty),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        label = { Text(stringResource(R.string.hosts_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun HostList(
    sections: List<HostSection>,
    actions: HostsActions,
    navigation: HostsNavigation,
) {
    if (sections.all { it.rows.isEmpty() }) {
        Text(
            stringResource(R.string.hosts_no_results),
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            textAlign = TextAlign.Center,
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        content =
            @DontMemoize {
                sections.forEach { section -> section(section, actions, navigation) }
            },
    )
}

private fun LazyListScope.section(
    section: HostSection,
    actions: HostsActions,
    navigation: HostsNavigation,
) {
    item(key = "header-${section.kind}-${section.groupId}", contentType = CONTENT_HEADER) {
        Text(
            section.groupName ?: stringResource(section.kind.title()),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        )
    }
    items(section.rows, key = { "${section.kind}-${it.host.id}" }, contentType = { CONTENT_HOST }) { row ->
        HostRowItem(row, actions, navigation)
    }
}

private const val CONTENT_HEADER = "header"
private const val CONTENT_HOST = "host"

/** The heading of a section; group sections show the group's name instead. */
@StringRes
internal fun SectionKind.title(): Int =
    when (this) {
        SectionKind.FAVORITES -> R.string.hosts_section_favorites
        SectionKind.RECENT -> R.string.hosts_section_recent
        SectionKind.GROUP, SectionKind.OTHER -> R.string.hosts_section_other
        SectionKind.RESULTS -> R.string.hosts_section_results
    }

@Composable
private fun HostRowItem(
    row: HostRow,
    actions: HostsActions,
    navigation: HostsNavigation,
) {
    val host = row.host
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { StatusDot(row.dot) },
        headlineContent = { Text(host.label) },
        supportingContent = { Text(host.displayTarget) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.hosts_more_actions, host.label),
                    )
                }
                HostMenu(host, menu, onDismiss = { menu = false }, actions, navigation)
            }
        },
        modifier =
            Modifier.combinedClickable(
                onClickLabel = stringResource(R.string.hosts_open_terminal),
                onLongClick = { menu = true },
                onLongClickLabel = stringResource(R.string.hosts_more_actions, host.label),
                onClick = @DontMemoize { navigation.onOpenTerminal(host.id) },
            ),
    )
}

@Composable
private fun HostMenu(
    host: Host,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: HostsActions,
    navigation: HostsNavigation,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        HostTool.entries.forEach { tool ->
            DropdownMenuItem(
                text = { Text(stringResource(tool.label)) },
                onClick = {
                    onDismiss()
                    navigation.onOpenTool(host.id, tool)
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_edit)) },
            onClick = {
                onDismiss()
                navigation.onEdit(host.id)
            },
        )
        DropdownMenuItem(
            text = {
                Text(stringResource(if (host.favorite) R.string.hosts_unfavorite else R.string.hosts_favorite))
            },
            leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
            onClick = {
                onDismiss()
                actions.toggleFavorite(host)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            onClick = {
                onDismiss()
                actions.requestDelete(host)
            },
        )
    }
}

/** Connection-state dot: grey idle, amber connecting/reconnecting, green connected, red failed. */
@Composable
internal fun StatusDot(dot: ConnectionDot) {
    val color = dot.color(MaterialTheme.colorScheme, LocalStatusColors.current)
    val label = stringResource(dot.description())
    Box(
        Modifier
            .size(12.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = label },
    )
}

/** The dot's colour in [scheme] and [status]. */
internal fun ConnectionDot.color(
    scheme: ColorScheme,
    status: StatusColors,
): Color =
    when (this) {
        ConnectionDot.IDLE -> status.idle
        ConnectionDot.CONNECTING, ConnectionDot.RECONNECTING -> status.warning
        ConnectionDot.CONNECTED -> status.success
        ConnectionDot.FAILED -> scheme.error
    }

/** What TalkBack says for the dot. */
@StringRes
internal fun ConnectionDot.description(): Int =
    when (this) {
        ConnectionDot.IDLE -> R.string.connection_idle
        ConnectionDot.CONNECTING -> R.string.connection_connecting
        ConnectionDot.RECONNECTING -> R.string.connection_reconnecting
        ConnectionDot.CONNECTED -> R.string.connection_connected
        ConnectionDot.FAILED -> R.string.connection_failed
    }
