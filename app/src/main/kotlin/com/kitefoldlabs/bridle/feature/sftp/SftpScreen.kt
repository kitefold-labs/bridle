package com.kitefoldlabs.bridle.feature.sftp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.ssh.RemoteFile
import com.kitefoldlabs.bridle.core.ssh.Sftp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(
    viewModel: SftpViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SftpDialog?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val noticeText = state.notice?.let { noticeText(it) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            viewModel.consumeNotice()
            // Not in this effect: consuming the notice restarts it, which would dismiss the snackbar.
            scope.launch { snackbar.showSnackbar(noticeText) }
        }
    }
    val upload =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.upload(uris)
        }
    val download =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_BINARY)) { uri ->
            viewModel.downloadTo(uri)
        }
    LaunchedEffect(state.pendingDownload) {
        state.pendingDownload?.let { download.launch(it.name) }
    }
    val editor = state.editor
    if (editor != null) {
        SftpEditor(editor, viewModel, snackbar)
        return
    }
    Scaffold(
        topBar = {
            SftpTopBar(
                state = state,
                transfers = transfers,
                viewModel = viewModel,
                onBack = onBack,
                onUpload = { upload.launch(arrayOf(MIME_ANY)) },
                onDialog = { dialog = it },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.failed -> ConnectionFailed(state.errorDetail, viewModel::retry)
                state.connecting || state.path == null -> Centered { CircularProgressIndicator() }
                else -> {
                    Breadcrumbs(state.path.orEmpty(), viewModel::open)
                    HorizontalDivider()
                    FileList(state, viewModel, onDialog = { dialog = it })
                }
            }
        }
    }
    dialog?.let { current ->
        SftpDialogs(current, viewModel, transfers, onDismiss = { dialog = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SftpTopBar(
    state: SftpUiState,
    transfers: List<Transfer>,
    viewModel: SftpViewModel,
    onBack: () -> Unit,
    onUpload: () -> Unit,
    onDialog: (SftpDialog) -> Unit,
) {
    val ready = state.path != null && !state.failed
    val activeTransfers = transfers.count { it.isActive }
    TopAppBar(
        title = { Text(stringResource(R.string.sftp_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            if (transfers.isNotEmpty()) {
                IconButton(onClick = { onDialog(SftpDialog.Transfers) }) {
                    BadgedBox(badge = { if (activeTransfers > 0) Badge { Text("$activeTransfers") } }) {
                        Icon(Icons.Filled.SwapVert, contentDescription = stringResource(R.string.sftp_transfers))
                    }
                }
            }
            IconButton(onClick = onUpload, enabled = ready) {
                Icon(Icons.Filled.Upload, contentDescription = stringResource(R.string.sftp_upload))
            }
            IconButton(onClick = { onDialog(SftpDialog.NewFolder) }, enabled = ready) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.sftp_new_folder))
            }
            ViewMenu(state, viewModel::setSort, viewModel::setShowHidden)
        },
    )
}

@Composable
private fun ViewMenu(
    state: SftpUiState,
    onSort: (SftpSort) -> Unit,
    onShowHidden: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sftp_view_options))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        SftpSort.entries.forEach { sort ->
            DropdownMenuItem(
                text = { Text(stringResource(sortLabel(sort))) },
                leadingIcon = { RadioButton(selected = state.sort == sort, onClick = null) },
                onClick = {
                    onSort(sort)
                    open = false
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sftp_show_hidden)) },
            leadingIcon = { Checkbox(checked = state.showHidden, onCheckedChange = null) },
            onClick = {
                onShowHidden(!state.showHidden)
                open = false
            },
        )
    }
}

private fun sortLabel(sort: SftpSort): Int =
    when (sort) {
        SftpSort.NAME -> R.string.sftp_sort_name
        SftpSort.SIZE -> R.string.sftp_sort_size
        SftpSort.DATE -> R.string.sftp_sort_date
    }

@Composable
private fun Breadcrumbs(
    path: String,
    onOpen: (String) -> Unit,
) {
    val crumbs = SftpListing.breadcrumbs(path)
    LazyRow(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs) { index, crumb ->
            if (index > 1) Text("/", color = MaterialTheme.colorScheme.outline)
            val current = index == crumbs.lastIndex
            TextButton(onClick = { onOpen(crumb.path) }, enabled = !current) {
                Text(crumb.label)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileList(
    state: SftpUiState,
    viewModel: SftpViewModel,
    onDialog: (SftpDialog) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        val files = state.visible
        LazyColumn(Modifier.fillMaxSize()) {
            if (state.path != "/") {
                item {
                    ListItem(
                        headlineContent = { Text("..") },
                        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        modifier =
                            Modifier.clickable(
                                onClickLabel = stringResource(R.string.sftp_parent_folder),
                                role = Role.Button,
                                onClick = { viewModel.open(Sftp.parent(state.path.orEmpty())) },
                            ),
                    )
                }
            }
            if (files.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sftp_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(files, key = { it.path }) { file ->
                FileRow(file, viewModel, onDialog)
            }
        }
    }
}

@Composable
private fun FileRow(
    file: RemoteFile,
    viewModel: SftpViewModel,
    onDialog: (SftpDialog) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val icon =
        when (file.type) {
            RemoteFile.Type.DIRECTORY -> Icons.Filled.Folder
            RemoteFile.Type.SYMLINK -> Icons.Filled.Link
            else -> Icons.AutoMirrored.Filled.InsertDriveFile
        }
    ListItem(
        headlineContent = { Text(file.name) },
        supportingContent = { Text(details(file)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.sftp_file_actions, file.name),
                    )
                }
                FileMenu(file, menu, onDismiss = { menu = false }, viewModel = viewModel, onDialog = onDialog)
            }
        },
        modifier = Modifier.clickable(role = Role.Button) { viewModel.activate(file) },
    )
}

@Composable
private fun FileMenu(
    file: RemoteFile,
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewModel: SftpViewModel,
    onDialog: (SftpDialog) -> Unit,
) {
    @Composable
    fun entry(
        label: Int,
        action: () -> Unit,
    ) = DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            onDismiss()
            action()
        },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!file.isDirectory) {
            entry(R.string.sftp_edit) { viewModel.openEditor(file) }
            entry(R.string.sftp_download) { viewModel.requestDownload(file) }
        }
        entry(R.string.sftp_rename) { onDialog(SftpDialog.Rename(file)) }
        entry(R.string.sftp_permissions) { onDialog(SftpDialog.Permissions(file)) }
        entry(R.string.sftp_delete) { onDialog(SftpDialog.Delete(file)) }
    }
}

@Composable
private fun details(file: RemoteFile): String {
    val date =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(
            Date(
                file.modifiedEpochSeconds * MILLIS,
            ),
        )
    val parts =
        listOfNotNull(
            if (file.isDirectory) null else SftpListing.formatSize(file.size),
            date,
            SftpListing.symbolic(file.permissions),
        )
    return parts.joinToString(" · ")
}

@Composable
private fun ConnectionFailed(
    detail: String?,
    onRetry: () -> Unit,
) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sftp_connect_failed), style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun noticeText(notice: SftpNotice): String =
    when (notice) {
        is SftpNotice.Failed -> stringResource(R.string.sftp_failed, notice.detail)
        is SftpNotice.TooLarge ->
            stringResource(
                R.string.sftp_too_large,
                SftpListing.formatSize(notice.size),
                SftpListing.formatSize(notice.limit),
            )
        SftpNotice.InvalidName -> stringResource(R.string.sftp_invalid_name)
        SftpNotice.InvalidMode -> stringResource(R.string.sftp_invalid_mode)
        SftpNotice.Saved -> stringResource(R.string.sftp_saved)
    }

private const val MIME_ANY = "*/*"
private const val MIME_BINARY = "application/octet-stream"
private const val MILLIS = 1000L
