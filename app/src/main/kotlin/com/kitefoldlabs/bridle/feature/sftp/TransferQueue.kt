package com.kitefoldlabs.bridle.feature.sftp

import android.net.Uri
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.ssh.Sftp
import com.kitefoldlabs.bridle.core.ssh.SftpSession
import com.kitefoldlabs.bridle.core.ssh.TransferProgress
import com.kitefoldlabs.bridle.di.ApplicationScope
import com.kitefoldlabs.bridle.feature.sftp.TransferStatus.CANCELLED
import com.kitefoldlabs.bridle.feature.terminal.SessionServiceStarter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** One upload or download. [remotePath] is the file on the host. */
data class Transfer(
    val id: Long,
    val hostId: EntityId,
    val direction: TransferDirection,
    val name: String,
    val remotePath: String,
    val transferred: Long = 0,
    val total: Long? = null,
    val status: TransferStatus = TransferStatus.QUEUED,
    val error: String? = null,
) {
    val isActive: Boolean get() = status == TransferStatus.QUEUED || status == TransferStatus.RUNNING

    /** Progress in 0..1, or null when the total size is unknown. */
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (transferred.toFloat() / it).coerceIn(0f, 1f) }
}

/**
 * Uploads and downloads between the host and documents picked with the Storage Access Framework.
 *
 * Transfers run one at a time, in order, in the application scope, so they continue when the user
 * leaves the file browser. Each holds a [ConnectionManager] lease while it runs, which keeps the
 * host's shared connection open (and reconnects it) for as long as the transfer needs it, and
 * queueing one starts the foreground service, which stays up while [hasActiveWork].
 */
@Singleton
class TransferQueue
    @Inject
    constructor(
        private val connections: ConnectionManager,
        private val documents: DocumentAccess,
        @param:ApplicationScope private val scope: CoroutineScope,
        private val service: SessionServiceStarter,
    ) {
        private val state = MutableStateFlow<List<Transfer>>(emptyList())
        private val jobs = ConcurrentHashMap<Long, Job>()
        private val ids = AtomicLong()
        private val oneAtATime = Mutex()

        /** Every transfer since the list was last cleared, oldest first. */
        val transfers: StateFlow<List<Transfer>> = state.asStateFlow()

        /** True while a transfer is queued or running. */
        val hasActiveWork: Boolean get() = state.value.any { it.isActive }

        /** Uploads the document at [uri] into [remoteDir], keeping its name. Never overwrites. */
        fun upload(
            hostId: EntityId,
            uri: Uri,
            remoteDir: String,
        ): Transfer {
            val info = documents.describe(uri)
            // The name comes from the document provider (another app): keep it a plain file name.
            val name = SftpListing.safeName(info.name, fallback = "upload")
            val transfer =
                Transfer(
                    id = ids.incrementAndGet(),
                    hostId = hostId,
                    direction = TransferDirection.UPLOAD,
                    name = name,
                    remotePath = Sftp.join(remoteDir, name),
                    total = info.size,
                )
            run(transfer) { sftp, progress ->
                documents.openInput(uri).use { input ->
                    sftp.upload(input, transfer.remotePath, info.size, overwrite = false, progress = progress)
                }
            }
            return transfer
        }

        /** Downloads [remotePath] into the document at [uri] (created by the user with SAF). */
        fun download(
            hostId: EntityId,
            remotePath: String,
            uri: Uri,
        ): Transfer {
            val transfer =
                Transfer(
                    id = ids.incrementAndGet(),
                    hostId = hostId,
                    direction = TransferDirection.DOWNLOAD,
                    name = remotePath.trimEnd('/').substringAfterLast('/'),
                    remotePath = remotePath,
                )
            run(transfer, onFailure = { documents.delete(uri) }) { sftp, progress ->
                documents.openOutput(uri).use { output -> sftp.download(remotePath, output, progress) }
            }
            return transfer
        }

        /** Stops a queued or running transfer. A cancelled upload leaves nothing behind on the host. */
        fun cancel(id: Long) {
            jobs[id]?.cancel()
        }

        /** Removes finished, failed and cancelled transfers from [transfers]. */
        fun clearFinished() {
            state.update { list -> list.filter { it.isActive } }
        }

        private fun run(
            transfer: Transfer,
            onFailure: () -> Unit = {},
            block: suspend (SftpSession, TransferProgress) -> Unit,
        ) {
            state.update { it + transfer }
            service.ensureRunning()
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        oneAtATime.withLock { transfer(transfer, block) }
                        update(transfer.id) { it.copy(status = TransferStatus.DONE) }
                    } catch (e: CancellationException) {
                        update(transfer.id) { it.copy(status = TransferStatus.CANCELLED) }
                        onFailure()
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        update(transfer.id) {
                            it.copy(status = TransferStatus.FAILED, error = e.message ?: e.javaClass.simpleName)
                        }
                        onFailure()
                    }
                }
            jobs[transfer.id] = job
            job.invokeOnCompletion { cause ->
                jobs.remove(transfer.id)
                // Cancelled before its body ever ran, so nothing above recorded it; left QUEUED, it
                // would keep the foreground service up for good.
                if (cause != null) update(transfer.id) { if (it.isActive) it.copy(status = CANCELLED) else it }
            }
            job.start()
        }

        private suspend fun transfer(
            transfer: Transfer,
            block: suspend (SftpSession, TransferProgress) -> Unit,
        ) {
            update(transfer.id) { it.copy(status = TransferStatus.RUNNING) }
            connections.acquire(transfer.hostId).use { lease ->
                lease.awaitConnection().openSftp().use { sftp ->
                    block(sftp) { done, total ->
                        update(transfer.id) { it.copy(transferred = done, total = total ?: it.total) }
                    }
                }
            }
        }

        private fun update(
            id: Long,
            change: (Transfer) -> Transfer,
        ) {
            state.update { list -> list.map { if (it.id == id) change(it) else it } }
        }
    }
