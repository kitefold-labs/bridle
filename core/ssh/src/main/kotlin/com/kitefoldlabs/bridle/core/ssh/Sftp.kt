package com.kitefoldlabs.bridle.core.ssh

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.UUID

/** A file or directory on the remote host. */
public data class RemoteFile(
    val name: String,
    val path: String,
    val type: Type,
    val size: Long,
    val modifiedEpochSeconds: Long,
    /** Unix permission bits, e.g. `0o644`. */
    val permissions: Int,
) {
    public enum class Type { FILE, DIRECTORY, SYMLINK, OTHER }

    val isDirectory: Boolean get() = type == Type.DIRECTORY
    val isHidden: Boolean get() = name.startsWith(".")
}

/** Progress callback: bytes so far and total if known. Called at most every [Sftp.PROGRESS_STEP] bytes. */
public typealias TransferProgress = (transferred: Long, total: Long?) -> Unit

/** The file was modified on the host after it was opened in the editor. */
public class RemoteFileChangedException(
    public val path: String,
) : IOException("$path changed on the host since it was opened")

/** The file is larger than the editor allows. */
public class RemoteFileTooLargeException(
    public val path: String,
    public val size: Long,
    public val limit: Long,
) : IOException("$path is $size bytes; the editor limit is $limit")

/** SFTP operations over one SSH connection. */
public interface SftpSession : AutoCloseable {
    /** Resolves `.`, `..`, relative paths and `""` (the home directory) to an absolute path. */
    public suspend fun canonicalize(path: String): String

    /** Directory entries without `.` and `..`, in server order. */
    public suspend fun list(path: String): List<RemoteFile>

    /** Attributes of [path], or null if it does not exist. */
    public suspend fun stat(path: String): RemoteFile?

    public suspend fun mkdir(path: String)

    public suspend fun rename(
        from: String,
        to: String,
    )

    /**
     * Deletes a file, or an empty directory (or a directory tree with [recursive]). Symbolic links
     * are removed, never followed, so deleting a tree can't reach anything outside it.
     */
    public suspend fun delete(
        path: String,
        recursive: Boolean = false,
    )

    public suspend fun chmod(
        path: String,
        permissions: Int,
    )

    /** Streams [path] into [sink]. Cancellable between chunks. */
    public suspend fun download(
        path: String,
        sink: OutputStream,
        progress: TransferProgress = { _, _ -> },
    ): Long

    /**
     * Uploads [source] to [path] atomically: data goes to a temporary sibling that is renamed
     * over the target only after the transfer completes, so an interrupted upload never leaves a
     * truncated file behind.
     */
    public suspend fun upload(
        source: InputStream,
        path: String,
        size: Long? = null,
        overwrite: Boolean = false,
        progress: TransferProgress = { _, _ -> },
    ): Long

    /** Reads a small text file for the editor. */
    public suspend fun readText(
        path: String,
        limit: Long = Sftp.EDITOR_LIMIT,
    ): Pair<String, RemoteFile>

    /**
     * Saves editor text, failing with [RemoteFileChangedException] if the file's size or mtime
     * differs from [opened] (someone else changed it).
     */
    public suspend fun writeText(
        path: String,
        text: String,
        opened: RemoteFile?,
    ): RemoteFile
}

public object Sftp {
    public const val EDITOR_LIMIT: Long = 1024L * 1024L
    internal const val PROGRESS_STEP: Long = 256L * 1024L
    internal const val BUFFER_SIZE: Int = 32 * 1024
    internal const val READ_AHEAD: Int = 16
    internal const val UNCONFIRMED_WRITES: Int = 16
    internal const val PERMISSION_BITS: Int = 0b111_111_111
    internal const val SPECIAL_BITS: Int = 0b111_000_000_000
    internal const val TEMP_SUFFIX: String = ".bridle-part"
    internal const val TEMP_MODE: Int = 0b110_000_000

    /** Joins a directory and a name with exactly one `/`. */
    public fun join(
        dir: String,
        name: String,
    ): String {
        require(name.isNotEmpty() && '/' !in name && name != "." && name != "..") { "Invalid file name: $name" }
        return if (dir.endsWith("/")) dir + name else "$dir/$name"
    }

    /** The parent of an absolute path (`/` stays `/`). */
    public fun parent(path: String): String {
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return if (index <= 0) "/" else trimmed.substring(0, index)
    }
}

internal class SshjSftpSession(
    private val client: SFTPClient,
    private val io: CoroutineDispatcher,
) : SftpSession {
    override suspend fun canonicalize(path: String): String = call { client.canonicalize(path.ifEmpty { "." }) }

    override suspend fun list(path: String): List<RemoteFile> =
        call { client.ls(path).filter { it.name != "." && it.name != ".." }.map { it.toRemote() } }

    override suspend fun stat(path: String): RemoteFile? =
        call {
            client.statExistence(path)?.toRemote(path.trimEnd('/').substringAfterLast('/'), path)
        }

    override suspend fun mkdir(path: String) = call { client.mkdir(path) }

    override suspend fun rename(
        from: String,
        to: String,
    ) = call { client.rename(from, to) }

    override suspend fun delete(
        path: String,
        recursive: Boolean,
    ) {
        // lstat, not stat: a symlink to a directory is removed itself, never recursed into.
        val target = lstat(path) ?: return
        if (!target.isDirectory) return call { client.rm(path) }
        if (recursive) {
            for (child in list(path)) delete(child.path, recursive = true)
        }
        call { client.rmdir(path) }
    }

    override suspend fun chmod(
        path: String,
        permissions: Int,
    ) {
        require(permissions and (Sftp.PERMISSION_BITS or Sftp.SPECIAL_BITS).inv() == 0) { "Invalid mode" }
        call { client.chmod(path, permissions) }
    }

    override suspend fun download(
        path: String,
        sink: OutputStream,
        progress: TransferProgress,
    ): Long =
        withContext(io) {
            client.open(path, EnumSet.of(OpenMode.READ)).use { file ->
                val total = file.length()
                file.ReadAheadRemoteFileInputStream(Sftp.READ_AHEAD).use { input ->
                    copy(input, sink, total, progress)
                }
            }
        }

    override suspend fun upload(
        source: InputStream,
        path: String,
        size: Long?,
        overwrite: Boolean,
        progress: TransferProgress,
    ): Long =
        withContext(io) {
            val existing = client.statExistence(path)
            if (!overwrite && existing != null) {
                throw IOException("$path already exists")
            }
            // A fresh, unpredictable name opened with EXCL: never another upload's file, and never
            // a planted symlink (O_EXCL does not follow one), even in a shared directory.
            val temp = "$path.${UUID.randomUUID().toString().take(TEMP_ID_LENGTH)}${Sftp.TEMP_SUFFIX}"
            val modes = EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)
            // A replacement stays private while it is written and gets the target's mode before the
            // rename, so saving a 0600 file never exposes it and a script stays executable.
            val attrs = if (existing == null) FileAttributes.EMPTY else privateFile
            var created = false
            var targetRemoved = false
            try {
                val written =
                    client.open(temp, modes, attrs).use { file ->
                        created = true
                        file.RemoteFileOutputStream(0, Sftp.UNCONFIRMED_WRITES).use { out ->
                            copy(source, out, size, progress)
                        }
                    }
                existing?.mode?.let { client.chmod(temp, it.permissionsMask and Sftp.PERMISSION_BITS) }
                replace(temp, path) { targetRemoved = true }
                written
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                // Also on cancellation: an abandoned upload must not leave its temp file behind. Once
                // the old target is gone, though, the temp file is the only copy, so it is kept.
                if (created && !targetRemoved) removeQuietly(temp)
                throw e
            }
        }

    override suspend fun readText(
        path: String,
        limit: Long,
    ): Pair<String, RemoteFile> {
        val info = stat(path) ?: throw IOException("$path does not exist")
        // Devices, FIFOs and sockets report size 0: /dev/zero would be read until memory runs
        // out, and a FIFO would block forever.
        if (info.type != RemoteFile.Type.FILE) throw IOException("$path is not a regular file")
        if (info.size > limit) throw RemoteFileTooLargeException(path, info.size, limit)
        // The size can also be wrong (/proc) or grow while it is read (a log): cap the read too.
        val out = CappedBuffer(limit) { size -> RemoteFileTooLargeException(path, size, limit) }
        download(path, out)
        return out.toString(Charsets.UTF_8) to info
    }

    override suspend fun writeText(
        path: String,
        text: String,
        opened: RemoteFile?,
    ): RemoteFile {
        if (opened != null) {
            val now = stat(path)
            if (now == null || now.size != opened.size || now.modifiedEpochSeconds != opened.modifiedEpochSeconds) {
                throw RemoteFileChangedException(path)
            }
        }
        upload(text.toByteArray(Charsets.UTF_8).inputStream(), path, overwrite = true)
        return checkNotNull(stat(path)) { "$path vanished after saving" }
    }

    override fun close() = closeQuietly(client)

    /** Renames [temp] over [target], replacing it (POSIX rename semantics where supported). */
    private fun replace(
        temp: String,
        target: String,
        onTargetRemoved: () -> Unit,
    ) {
        try {
            client.rename(temp, target)
        } catch (e: SFTPException) {
            // SFTP v3 rename fails if the target exists; fall back to remove + rename.
            if (e.statusCode != Response.StatusCode.FAILURE &&
                e.statusCode != Response.StatusCode.FILE_ALREADY_EXISTS
            ) {
                throw e
            }
            client.rm(target)
            onTargetRemoved()
            client.rename(temp, target)
        }
    }

    /** Like [stat] but does not follow a final symbolic link. */
    private suspend fun lstat(path: String): RemoteFile? =
        call {
            try {
                client.lstat(path).toRemote(path.trimEnd('/').substringAfterLast('/'), path)
            } catch (e: SFTPException) {
                if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw e
            }
        }

    private fun removeQuietly(path: String) {
        try {
            client.rm(path)
        } catch (_: IOException) {
            // Best effort cleanup of a partial upload.
        }
    }

    private suspend fun copy(
        input: InputStream,
        output: OutputStream,
        total: Long?,
        progress: TransferProgress,
    ): Long {
        val buffer = ByteArray(Sftp.BUFFER_SIZE)
        var transferred = 0L
        var reported = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            transferred += n
            if (transferred - reported >= Sftp.PROGRESS_STEP) {
                reported = transferred
                progress(transferred, total)
            }
        }
        output.flush()
        progress(transferred, total)
        return transferred
    }

    private suspend fun <T> call(block: () -> T): T = withContext(io) { block() }

    private companion object {
        const val TEMP_ID_LENGTH = 8
        val privateFile: FileAttributes = FileAttributes.Builder().withPermissions(Sftp.TEMP_MODE).build()
    }
}

/** A byte buffer that refuses to grow past [limit] bytes, throwing [tooLarge] (given the size it would reach). */
internal class CappedBuffer(
    private val limit: Long,
    private val tooLarge: (Long) -> IOException,
) : ByteArrayOutputStream() {
    override fun write(b: Int) {
        ensureRoom(1)
        super.write(b)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        ensureRoom(len)
        super.write(b, off, len)
    }

    private fun ensureRoom(len: Int) {
        val size = count.toLong() + len
        if (size > limit) throw tooLarge(size)
    }
}

internal fun RemoteResourceInfo.toRemote(): RemoteFile = attributes.toRemote(name, path)

internal fun FileAttributes.toRemote(
    name: String,
    path: String,
): RemoteFile =
    RemoteFile(
        name = name,
        path = path,
        type =
            when (mode?.type) {
                FileMode.Type.REGULAR -> RemoteFile.Type.FILE
                FileMode.Type.DIRECTORY -> RemoteFile.Type.DIRECTORY
                FileMode.Type.SYMLINK -> RemoteFile.Type.SYMLINK
                else -> RemoteFile.Type.OTHER
            },
        size = size,
        modifiedEpochSeconds = mtime,
        permissions = (mode?.permissionsMask ?: 0) and (Sftp.PERMISSION_BITS or Sftp.SPECIAL_BITS),
    )
