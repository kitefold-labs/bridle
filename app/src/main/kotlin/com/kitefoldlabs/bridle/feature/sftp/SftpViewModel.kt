package com.kitefoldlabs.bridle.feature.sftp

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.session.ConnectionLease
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.session.HostConnectionState
import com.kitefoldlabs.bridle.core.ssh.RemoteFile
import com.kitefoldlabs.bridle.core.ssh.RemoteFileChangedException
import com.kitefoldlabs.bridle.core.ssh.RemoteFileTooLargeException
import com.kitefoldlabs.bridle.core.ssh.Sftp
import com.kitefoldlabs.bridle.core.ssh.SftpSession
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Something to tell the user once (a snackbar). */
sealed interface SftpNotice {
    data class Failed(
        val detail: String,
    ) : SftpNotice

    data class TooLarge(
        val size: Long,
        val limit: Long,
    ) : SftpNotice

    data object InvalidName : SftpNotice

    data object InvalidMode : SftpNotice

    data object Saved : SftpNotice
}

/** The small-file text editor. [opened] is the file as read, for conflict detection on save. */
data class EditorState(
    val path: String,
    val opened: RemoteFile,
    val original: String,
    val text: String,
    /** The content is not valid UTF-8 or contains NUL bytes; editing is off until the user insists. */
    val binary: Boolean,
    val editable: Boolean,
    val saving: Boolean = false,
    /** The file changed on the host since it was opened; the user must choose what to do. */
    val conflict: Boolean = false,
) {
    val name: String get() = opened.name
    val dirty: Boolean get() = text != original
}

data class SftpUiState(
    val connecting: Boolean = true,
    /** The connection or SFTP subsystem could not be opened; [errorDetail] says why, if known. */
    val failed: Boolean = false,
    val errorDetail: String? = null,
    val path: String? = null,
    val entries: List<RemoteFile> = emptyList(),
    val sort: SftpSort = SftpSort.NAME,
    val showHidden: Boolean = false,
    val loading: Boolean = false,
    val notice: SftpNotice? = null,
    val editor: EditorState? = null,
    /** A file waiting for the user to pick where to save it. */
    val pendingDownload: RemoteFile? = null,
) {
    val visible: List<RemoteFile> get() = SftpListing.arrange(entries, sort, showHidden)
}

/**
 * The SFTP browser for one host. Uses the host's shared connection through a [ConnectionLease]
 * and reopens the SFTP subsystem whenever the connection is re-established.
 *
 * Old SFTP sessions are closed in [background] on [io], never on the main thread: closing an
 * sshj channel writes to the socket, then waits (up to the connection timeout) for the reply.
 */
@HiltViewModel
class SftpViewModel(
    private val savedState: SavedStateHandle,
    private val connections: ConnectionManager,
    private val queue: TransferQueue,
    private val background: CoroutineScope,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        savedState: SavedStateHandle,
        connections: ConnectionManager,
        queue: TransferQueue,
        @ApplicationScope scope: CoroutineScope,
    ) : this(savedState, connections, queue, scope, Dispatchers.IO)

    val hostId: EntityId = checkNotNull(savedState.get<Long>(SftpDestination.ARG_HOST)) { "hostId missing" }

    private val mutable = MutableStateFlow(SftpUiState())
    val state: StateFlow<SftpUiState> = mutable.asStateFlow()

    /** This host's transfers. */
    val transfers: StateFlow<List<Transfer>> =
        queue.transfers
            .map { list -> list.filter { it.hostId == hostId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var lease: ConnectionLease? = null
    private var sftp: SftpSession? = null
    private var connectJob: Job? = null
    private var listJob: Job? = null
    private val refreshedUploads = mutableSetOf<Long>()

    init {
        connect()
        viewModelScope.launch {
            transfers.collect { list -> refreshAfterUploads(list) }
        }
    }

    /** Drops the current connection attempt and starts again. */
    fun retry() = connect()

    /** Lists [path]. A newer request replaces one still in flight, so a slow listing can't win. */
    fun open(path: String) {
        val session = sftp ?: return
        mutable.update { it.copy(loading = true) }
        listJob?.cancel()
        listJob =
            viewModelScope.launch {
                try {
                    val entries = session.list(path)
                    mutable.update { it.copy(path = path, entries = entries, loading = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    mutable.update { it.copy(loading = false, notice = failure(e)) }
                }
            }
    }

    fun refresh() {
        state.value.path?.let(::open)
    }

    /** Opens a directory, follows a symlink to one, or opens a file in the editor. */
    fun activate(file: RemoteFile) {
        when (file.type) {
            RemoteFile.Type.DIRECTORY -> open(file.path)
            RemoteFile.Type.SYMLINK ->
                operation { session ->
                    val target = session.stat(file.path)
                    if (target?.isDirectory == true) open(file.path) else openEditor(file)
                }
            else -> openEditor(file)
        }
    }

    fun setSort(sort: SftpSort) = mutable.update { it.copy(sort = sort) }

    fun setShowHidden(show: Boolean) = mutable.update { it.copy(showHidden = show) }

    fun consumeNotice() = mutable.update { it.copy(notice = null) }

    fun mkdir(name: String) = withName(name) { session, dir -> session.mkdir(Sftp.join(dir, name)) }

    fun rename(
        file: RemoteFile,
        name: String,
    ) = withName(name) { session, _ -> session.rename(file.path, Sftp.join(Sftp.parent(file.path), name)) }

    /** Deletes [file]; a directory is deleted with everything in it (the UI confirms first). */
    fun delete(file: RemoteFile) =
        operation(refresh = true) { session -> session.delete(file.path, recursive = file.isDirectory) }

    /** Sets permissions from octal text such as `644` or `0755`. */
    fun chmod(
        file: RemoteFile,
        octal: String,
    ) {
        val mode = SftpListing.parseOctal(octal)
        if (mode == null) {
            mutable.update { it.copy(notice = SftpNotice.InvalidMode) }
            return
        }
        operation(refresh = true) { session -> session.chmod(file.path, mode) }
    }

    fun upload(uris: List<Uri>) {
        val dir = state.value.path ?: return
        uris.forEach { uri ->
            try {
                queue.upload(hostId, uri, dir)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                mutable.update { it.copy(notice = failure(e)) }
            }
        }
    }

    /**
     * Asks the UI to open the "save as" picker for [file]. Its path is kept in the saved state,
     * so the download still starts if Android recreates the app while the picker is open.
     */
    fun requestDownload(file: RemoteFile) {
        savedState[KEY_DOWNLOAD] = file.path
        mutable.update { it.copy(pendingDownload = file) }
    }

    /** The UI has opened the picker; it must not open it again (say, after a rotation). */
    fun downloadPickerShown() = mutable.update { it.copy(pendingDownload = null) }

    /** The user picked [uri] for the pending download, or cancelled the picker (null). */
    fun downloadTo(uri: Uri?) {
        val path = savedState.remove<String>(KEY_DOWNLOAD) ?: return
        mutable.update { it.copy(pendingDownload = null) }
        if (uri != null) queue.download(hostId, path, uri)
    }

    fun cancelTransfer(id: Long) = queue.cancel(id)

    fun clearTransfers() = queue.clearFinished()

    fun openEditor(file: RemoteFile) =
        operation { session ->
            try {
                val (text, info) = session.readText(file.path)
                val binary = SftpListing.looksBinary(text)
                val editor = EditorState(file.path, info, text, text, binary = binary, editable = !binary)
                mutable.update { it.copy(editor = editor) }
            } catch (e: RemoteFileTooLargeException) {
                mutable.update { it.copy(notice = SftpNotice.TooLarge(e.size, e.limit)) }
            }
        }

    fun editText(text: String) = updateEditor { it.copy(text = text) }

    /** The user chose to edit a file that does not look like UTF-8 text anyway. */
    fun editAnyway() = updateEditor { it.copy(editable = true) }

    fun closeEditor() = mutable.update { it.copy(editor = null) }

    /**
     * Saves, refusing (with a conflict) if the file changed on the host since it was opened.
     * With [overwrite], resolves such a conflict by replacing the host's version with the editor's.
     */
    fun save(overwrite: Boolean = false) = write(overwrite)

    /** Resolves a conflict by discarding the edits and loading the host's version. */
    fun reload() {
        val editor = state.value.editor ?: return
        closeEditor()
        openEditor(editor.opened)
    }

    fun dismissConflict() = updateEditor { it.copy(conflict = false) }

    override fun onCleared() {
        disconnect()
    }

    private fun write(overwrite: Boolean) {
        val editor = state.value.editor ?: return
        val session = sftp ?: return
        updateEditor { it.copy(saving = true, conflict = false) }
        viewModelScope.launch {
            try {
                val opened = if (overwrite) null else editor.opened
                val saved = session.writeText(editor.path, editor.text, opened)
                updateEditor { it.copy(opened = saved, original = editor.text, saving = false) }
                mutable.update { it.copy(notice = SftpNotice.Saved) }
                refresh()
            } catch (ignored: RemoteFileChangedException) {
                updateEditor { it.copy(saving = false, conflict = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                updateEditor { it.copy(saving = false) }
                mutable.update { it.copy(notice = failure(e)) }
            }
        }
    }

    private fun updateEditor(change: (EditorState) -> EditorState) =
        mutable.update { state -> state.copy(editor = state.editor?.let(change)) }

    private fun withName(
        name: String,
        block: suspend (SftpSession, String) -> Unit,
    ) {
        val dir = state.value.path ?: return
        if (!SftpListing.isValidName(name)) {
            mutable.update { it.copy(notice = SftpNotice.InvalidName) }
            return
        }
        operation(refresh = true) { session -> block(session, dir) }
    }

    /** Runs [block] on the open SFTP session, reporting failures as a notice. */
    private fun operation(
        refresh: Boolean = false,
        block: suspend (SftpSession) -> Unit,
    ) {
        val session = sftp ?: return
        viewModelScope.launch {
            try {
                block(session)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                mutable.update { it.copy(notice = failure(e)) }
            }
            if (refresh) refresh()
        }
    }

    private fun connect() {
        disconnect()
        mutable.update { it.copy(connecting = true, failed = false, errorDetail = null) }
        connectJob =
            viewModelScope.launch {
                val lease = connections.acquire(hostId).also { lease = it }
                lease.state.collect { state ->
                    when (state) {
                        is HostConnectionState.Connected -> attach(state.connection)
                        is HostConnectionState.Failed -> fail(state.error)
                        HostConnectionState.Idle -> fail(null)
                        else -> mutable.update { it.copy(connecting = true) }
                    }
                }
            }
    }

    /** Opens SFTP on a (re-established) connection and shows the current directory, or the home directory. */
    private suspend fun attach(connection: SshConnection) {
        closeInBackground(sftp)
        sftp = null
        try {
            val session = connection.openSftp()
            sftp = session
            val path = state.value.path ?: session.canonicalize("")
            mutable.update { it.copy(connecting = false, failed = false, errorDetail = null) }
            open(path)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            fail(e)
        }
    }

    private fun fail(error: Throwable?) {
        mutable.update { it.copy(connecting = false, failed = true, errorDetail = error?.let(::describe)) }
    }

    private fun disconnect() {
        connectJob?.cancel()
        closeInBackground(sftp)
        lease?.close()
        sftp = null
        lease = null
    }

    private fun closeInBackground(session: SftpSession?) {
        if (session != null) background.launch(io) { session.close() }
    }

    private fun refreshAfterUploads(list: List<Transfer>) {
        val dir = state.value.path ?: return
        val landed =
            list.filter {
                it.direction == TransferDirection.UPLOAD &&
                    it.status == TransferStatus.DONE &&
                    Sftp.parent(it.remotePath) == dir &&
                    refreshedUploads.add(it.id)
            }
        if (landed.isNotEmpty()) refresh()
    }

    private fun failure(e: Exception): SftpNotice = SftpNotice.Failed(describe(e))

    private fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName

    private companion object {
        const val KEY_DOWNLOAD = "pendingDownload"
    }
}
