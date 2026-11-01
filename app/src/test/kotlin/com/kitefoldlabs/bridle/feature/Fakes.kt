package com.kitefoldlabs.bridle.feature

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.ssh.ConnectionState
import com.kitefoldlabs.bridle.core.ssh.DuplexStream
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.ssh.RemoteFile
import com.kitefoldlabs.bridle.core.ssh.RemoteFileChangedException
import com.kitefoldlabs.bridle.core.ssh.RemoteFileTooLargeException
import com.kitefoldlabs.bridle.core.ssh.SftpSession
import com.kitefoldlabs.bridle.core.ssh.ShellChannel
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.core.ssh.TransferProgress
import com.kitefoldlabs.bridle.feature.sftp.DocumentAccess
import com.kitefoldlabs.bridle.feature.sftp.DocumentInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import kotlin.time.Duration

/** An in-memory remote file system. Paths are absolute; the home directory is [HOME]. */
class FakeSftpSession : SftpSession {
    private class Node(
        var file: RemoteFile,
        var content: ByteArray = ByteArray(0),
        var linkTarget: String? = null,
    )

    private val nodes = linkedMapOf<String, Node>()
    private var clock = 1_000L

    /** Thrown (once) by the next operation. */
    var failNext: Exception? = null

    /** When set, uploads and downloads wait for it before finishing. */
    var transferGate: CompletableDeferred<Unit>? = null
    var closed = false
        private set

    init {
        dir("/")
        dir("/home")
        dir(HOME)
    }

    fun dir(path: String) {
        nodes[path] = Node(remote(path, RemoteFile.Type.DIRECTORY, 0, PERM_DIR))
    }

    fun file(
        path: String,
        text: String = "",
        bytes: ByteArray = text.toByteArray(),
        permissions: Int = PERM_FILE,
    ) {
        nodes[path] = Node(remote(path, RemoteFile.Type.FILE, bytes.size.toLong(), permissions), bytes)
    }

    fun link(
        path: String,
        target: String,
    ) {
        nodes[path] = Node(remote(path, RemoteFile.Type.SYMLINK, 0, PERM_FILE), linkTarget = target)
    }

    /** Simulates someone else editing [path] on the host. */
    fun touch(
        path: String,
        text: String,
    ) {
        val node = nodes.getValue(path)
        node.content = text.toByteArray()
        node.file = node.file.copy(size = node.content.size.toLong(), modifiedEpochSeconds = ++clock)
    }

    fun exists(path: String): Boolean = path in nodes

    fun text(path: String): String = String(nodes.getValue(path).content)

    fun permissions(path: String): Int = nodes.getValue(path).file.permissions

    override suspend fun canonicalize(path: String): String = fail { if (path.isEmpty()) HOME else path }

    override suspend fun list(path: String): List<RemoteFile> =
        fail {
            val dir = nodes[path]?.linkTarget ?: path
            val node = nodes[dir] ?: throw IOException("No such file: $path")
            if (!node.file.isDirectory) throw IOException("Not a directory: $path")
            nodes.values.map { it.file }.filter { it.path != dir && parentOf(it.path) == dir }
        }

    override suspend fun stat(path: String): RemoteFile? =
        fail {
            val node = nodes[path]
            val target = node?.linkTarget
            if (target != null) nodes[target]?.file else node?.file
        }

    override suspend fun mkdir(path: String) =
        fail {
            if (path in nodes) throw IOException("$path exists")
            dir(path)
        }

    override suspend fun rename(
        from: String,
        to: String,
    ) = fail {
        val moved = nodes.keys.filter { it == from || it.startsWith("$from/") }
        if (moved.isEmpty()) throw IOException("No such file: $from")
        moved.forEach { old ->
            val node = nodes.remove(old)!!
            val new = to + old.removePrefix(from)
            node.file = node.file.copy(name = new.substringAfterLast('/'), path = new)
            nodes[new] = node
        }
    }

    override suspend fun delete(
        path: String,
        recursive: Boolean,
    ) = fail {
        val children = nodes.keys.filter { it.startsWith("$path/") }
        if (children.isNotEmpty() && !recursive) throw IOException("Directory not empty")
        children.forEach(nodes::remove)
        nodes.remove(path) ?: throw IOException("No such file: $path")
        Unit
    }

    override suspend fun chmod(
        path: String,
        permissions: Int,
    ) = fail {
        val node = nodes[path] ?: throw IOException("No such file: $path")
        node.file = node.file.copy(permissions = permissions)
    }

    override suspend fun download(
        path: String,
        sink: OutputStream,
        progress: TransferProgress,
    ): Long {
        val node = fail { nodes[path] ?: throw IOException("No such file: $path") }
        progress(0, node.content.size.toLong())
        transferGate?.await()
        sink.write(node.content)
        progress(node.content.size.toLong(), node.content.size.toLong())
        return node.content.size.toLong()
    }

    override suspend fun upload(
        source: InputStream,
        path: String,
        size: Long?,
        overwrite: Boolean,
        progress: TransferProgress,
    ): Long {
        fail { if (!overwrite && path in nodes) throw IOException("$path already exists") }
        val bytes = source.readBytes()
        progress(bytes.size / 2L, size)
        transferGate?.await()
        file(path, bytes = bytes)
        nodes.getValue(path).file = nodes.getValue(path).file.copy(modifiedEpochSeconds = ++clock)
        progress(bytes.size.toLong(), size)
        return bytes.size.toLong()
    }

    override suspend fun readText(
        path: String,
        limit: Long,
    ): Pair<String, RemoteFile> =
        fail {
            val node = nodes[nodes[path]?.linkTarget ?: path] ?: throw IOException("No such file: $path")
            if (node.file.size > limit) throw RemoteFileTooLargeException(path, node.file.size, limit)
            String(node.content, Charsets.UTF_8) to node.file
        }

    override suspend fun writeText(
        path: String,
        text: String,
        opened: RemoteFile?,
    ): RemoteFile =
        fail {
            val now = nodes[path]?.file
            if (opened != null && (now == null || now.modifiedEpochSeconds != opened.modifiedEpochSeconds)) {
                throw RemoteFileChangedException(path)
            }
            file(path, text, permissions = now?.permissions ?: PERM_FILE)
            nodes.getValue(path).file = nodes.getValue(path).file.copy(modifiedEpochSeconds = ++clock)
            nodes.getValue(path).file
        }

    override fun close() {
        closed = true
    }

    private inline fun <T> fail(block: () -> T): T {
        failNext?.let {
            failNext = null
            throw it
        }
        return block()
    }

    private fun remote(
        path: String,
        type: RemoteFile.Type,
        size: Long,
        permissions: Int,
    ) = RemoteFile(path.substringAfterLast('/').ifEmpty { "/" }, path, type, size, ++clock, permissions)

    private fun parentOf(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

    companion object {
        const val HOME = "/home/me"
        const val PERM_DIR = 0b111_101_101
        const val PERM_FILE = 0b110_100_100
    }
}

/** A connection whose SFTP, exec and direct-tcpip channels are fakes. */
class FakeConnection(
    var sftp: FakeSftpSession = FakeSftpSession(),
) : SshConnection {
    override val state = MutableStateFlow(ConnectionState.CONNECTED)
    var sftpFailure: Exception? = null
    var execResult: ExecResult = ExecResult(0, "ok\n", "")
    var execFailure: Exception? = null
    val commands = mutableListOf<String>()

    override suspend fun exec(
        command: String,
        timeout: Duration,
    ): ExecResult {
        commands += command
        execFailure?.let { throw it }
        return execResult
    }

    override suspend fun runScript(
        script: String,
        timeout: Duration,
    ): ExecResult = exec(script, timeout)

    override suspend fun openShell(
        pty: PtySpec,
        command: String?,
    ): ShellChannel = throw UnsupportedOperationException()

    override suspend fun openSftp(): SftpSession {
        sftpFailure?.let { throw it }
        return sftp
    }

    /** An echo server: whatever the client sends comes straight back. */
    override suspend fun openDirectTcpip(
        host: String,
        port: Int,
    ): DuplexStream {
        val input = PipedInputStream(PIPE)
        val output = PipedOutputStream(input)
        return object : DuplexStream {
            override val input: InputStream = input
            override val output: OutputStream = output

            override fun close() {
                output.close()
                input.close()
            }
        }
    }

    override fun close() {
        state.value = ConnectionState.DISCONNECTED
    }

    private companion object {
        const val PIPE = 64 * 1024
    }
}

/** A [ConnectionManager] whose hosts all connect to [connection] (or fail with [failure]). */
fun connections(
    connection: () -> SshConnection,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
): ConnectionManager = ConnectionManager({ connection() }, scope)

/** Documents held in memory, keyed by URI string. */
class FakeDocuments : DocumentAccess {
    val inputs = mutableMapOf<String, ByteArray>()
    val outputs = mutableMapOf<String, ByteArrayOutputStream>()
    val deleted = mutableListOf<String>()
    var names = mutableMapOf<String, DocumentInfo>()

    override fun describe(uri: Uri): DocumentInfo =
        names[uri.toString()] ?: DocumentInfo(uri.lastPathSegment ?: "x", null)

    override fun openInput(uri: Uri): InputStream =
        ByteArrayInputStream(inputs[uri.toString()] ?: throw FileNotFoundException(uri.toString()))

    override fun openOutput(uri: Uri): OutputStream {
        if (uri.toString().contains("readonly")) throw FileNotFoundException(uri.toString())
        return ByteArrayOutputStream().also { outputs[uri.toString()] = it }
    }

    override fun delete(uri: Uri) {
        deleted += uri.toString()
    }
}

/** An in-memory data layer with its own settings file. */
fun testData(context: Context): DataModule {
    val dir = Files.createTempDirectory("bridle-test").toFile()
    val store =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            File(dir, "settings.preferences_pb")
        }
    return DataModule.inMemory(context, store)
}

/** Polls [condition] for up to five seconds of real time (for work on Room's own threads). */
fun eventually(
    timeoutMillis: Long = 5_000,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "Condition not met in $timeoutMillis ms" }
        org.robolectric.shadows.ShadowLooper
            .idleMainLooper()
        Thread.sleep(POLL_MS)
    }
}

private const val POLL_MS = 10L
