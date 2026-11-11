package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.ssh.RemoteFile
import com.kitefoldlabs.bridle.core.ssh.SftpSession
import com.kitefoldlabs.bridle.core.terminal.PathWord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A directory entry that completes the path being typed, and the text to type for it. */
public data class PathSuggestion(
    val name: String,
    val isDirectory: Boolean,
    /** The rest of [name], escaped for the line's quoting, with a `/` after a directory. */
    val insertion: String,
)

/**
 * Suggests entries of the directory named by the path at the end of a shell command line
 * (see [PathWord]), listed over SFTP on the host's connection.
 *
 * Each directory is listed once and cached until [invalidate], which the caller runs at every
 * new prompt because the last command may have changed files. A directory that can't be listed
 * (missing, no permission) is cached as empty. The SFTP session is opened on first use and
 * reopened after a failure.
 *
 * A word in command position is only completed when it contains a `/` (`./run`, `bin/x`):
 * otherwise the shell looks it up in PATH. Dotfiles appear only when the prefix starts with
 * `.`. Directories (including symlinks to them) come first, then names in case-insensitive
 * order.
 */
public class PathCompleter(
    private val openSftp: suspend () -> SftpSession,
    private val maxSuggestions: Int = DEFAULT_MAX_SUGGESTIONS,
) : AutoCloseable {
    private val mutex = Mutex()
    private var sftp: SftpSession? = null
    private var home: String? = null
    private val listings = ConcurrentHashMap<String, List<Entry>>()

    @Volatile
    private var generation = 0

    /** A listed name; symlinks are resolved only when they are about to be suggested. */
    private class Entry(
        val name: String,
        var type: RemoteFile.Type,
    )

    /** Suggestions for the end of [line], given the shell's working directory [cwd]. */
    public suspend fun suggest(
        line: String,
        cwd: String?,
    ): List<PathSuggestion> {
        val word = PathWord.parse(line) ?: return emptyList()
        if (word.isCommand && '/' !in word.path) return emptyList()
        return mutex.withLock {
            val session = session() ?: return@withLock emptyList()
            val homeDir = if (word.tilde) home(session) else null
            val dir = word.resolveDirectory(cwd, homeDir) ?: return@withLock emptyList()
            val matches =
                listing(session, dir)
                    .filter { it.name.startsWith(word.prefix) }
                    .filter { word.prefix.startsWith(".") || !it.name.startsWith(".") }
            resolveSymlinks(session, dir, matches)
            matches
                .sortedWith(ORDER)
                .mapNotNull { entry ->
                    val isDirectory = entry.type == RemoteFile.Type.DIRECTORY
                    word
                        .insertion(entry.name, isDirectory)
                        ?.takeIf { it.isNotEmpty() } // the whole name is typed already
                        ?.let { PathSuggestion(entry.name, isDirectory, it) }
                }.take(maxSuggestions)
        }
    }

    /** Forgets every listing; the next [suggest] lists again. */
    public fun invalidate() {
        generation++
        listings.clear()
    }

    override fun close() {
        sftp?.close()
        sftp = null
    }

    private suspend fun session(): SftpSession? =
        sftp ?: try {
            openSftp().also { sftp = it }
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }

    private suspend fun home(session: SftpSession): String? =
        home ?: guarded(session, null) { session.canonicalize("") }?.also { home = it }

    private suspend fun listing(
        session: SftpSession,
        dir: String,
    ): List<Entry> {
        listings[dir]?.let { return it }
        val seen = generation
        val entries =
            guarded(session, emptyList()) {
                session
                    .list(dir)
                    .asSequence()
                    .take(MAX_ENTRIES)
                    .map { Entry(it.name, it.type) }
                    .toList()
            }
        if (seen == generation) listings[dir] = entries
        return entries
    }

    /** Stats the symlinks among the first [maxSuggestions] candidates, so links to directories get a `/`. */
    private suspend fun resolveSymlinks(
        session: SftpSession,
        dir: String,
        candidates: List<Entry>,
    ) {
        for (entry in candidates.filter { it.type == RemoteFile.Type.SYMLINK }.take(maxSuggestions)) {
            if (sftp !== session) return // a failure dropped the session
            val target = guarded(session, null) { session.stat(dir.trimEnd('/') + "/" + entry.name) }
            entry.type =
                if (target?.type == RemoteFile.Type.DIRECTORY) RemoteFile.Type.DIRECTORY else RemoteFile.Type.FILE
        }
    }

    /** Runs [block], returning [fallback] on an I/O error and dropping the session so the next call reopens it. */
    private suspend fun <T> guarded(
        session: SftpSession,
        fallback: T,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (_: IOException) {
            if (sftp === session) close()
            fallback
        }

    public companion object {
        public const val DEFAULT_MAX_SUGGESTIONS: Int = 20

        /** Directories first, then names case-insensitively (ties by exact name, for a stable order). */
        private val ORDER: Comparator<Entry> =
            compareBy<Entry>({ it.type != RemoteFile.Type.DIRECTORY }, { it.name.lowercase() }, { it.name })

        /** Entries read from one directory at most; huge directories are cut, not loaded whole. */
        public const val MAX_ENTRIES: Int = 10_000
    }
}
