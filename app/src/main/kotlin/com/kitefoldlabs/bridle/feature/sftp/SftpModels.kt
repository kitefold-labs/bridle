package com.kitefoldlabs.bridle.feature.sftp

import com.kitefoldlabs.bridle.core.ssh.RemoteFile
import com.kitefoldlabs.bridle.core.ssh.Sftp

/** How the file list is ordered. Directories always come first. */
enum class SftpSort { NAME, SIZE, DATE }

/** One segment of the breadcrumb path bar: its label and the absolute path it opens. */
data class Crumb(
    val label: String,
    val path: String,
)

/** Pure helpers behind the SFTP browser, kept out of the ViewModel so they are easy to test. */
object SftpListing {
    private const val PERMISSION_DIGITS = 3
    private const val SPECIAL_DIGITS = 4
    private const val OCTAL = 8
    private const val MAX_MODE = 0b111_111_111_111
    private const val BYTES_PER_UNIT = 1024.0
    private val units = listOf("B", "KB", "MB", "GB", "TB")

    /** Hidden files filtered out unless [showHidden]; directories first, then by [sort]. */
    fun arrange(
        entries: List<RemoteFile>,
        sort: SftpSort,
        showHidden: Boolean,
    ): List<RemoteFile> {
        val byName = compareBy<RemoteFile, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val order =
            when (sort) {
                SftpSort.NAME -> byName
                SftpSort.SIZE -> compareByDescending<RemoteFile> { it.size }.then(byName)
                SftpSort.DATE -> compareByDescending<RemoteFile> { it.modifiedEpochSeconds }.then(byName)
            }
        return entries
            .filter { showHidden || !it.isHidden }
            .sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.then(order))
    }

    /** `/home/me/src` → `/`, `home`, `me`, `src`, each opening its own path. */
    fun breadcrumbs(path: String): List<Crumb> {
        val crumbs = mutableListOf(Crumb("/", "/"))
        var current = "/"
        path.split('/').filter { it.isNotEmpty() }.forEach { name ->
            current = Sftp.join(current, name)
            crumbs += Crumb(name, current)
        }
        return crumbs
    }

    /** `0o755` → `rwxr-xr-x` (special bits shown as `s`/`t` like `ls -l`). */
    fun symbolic(permissions: Int): String {
        val chars = CharArray(PERMISSION_DIGITS * PERMISSION_DIGITS)
        val letters = "rwx"
        for (bit in 0 until PERMISSION_DIGITS * PERMISSION_DIGITS) {
            val set = permissions and (1 shl (PERMISSION_DIGITS * PERMISSION_DIGITS - 1 - bit)) != 0
            chars[bit] = if (set) letters[bit % PERMISSION_DIGITS] else '-'
        }
        special(chars, permissions and SETUID != 0, OWNER_EXECUTE, 's')
        special(chars, permissions and SETGID != 0, GROUP_EXECUTE, 's')
        special(chars, permissions and STICKY != 0, OTHER_EXECUTE, 't')
        return String(chars)
    }

    private fun special(
        chars: CharArray,
        set: Boolean,
        index: Int,
        letter: Char,
    ) {
        if (set) chars[index] = if (chars[index] == 'x') letter else letter.uppercaseChar()
    }

    /** `0o644` → `"644"`; special bits add a leading digit (`"4755"`). */
    fun octal(permissions: Int): String {
        val text = Integer.toOctalString(permissions and MAX_MODE)
        val digits = if (permissions and SPECIAL_MASK != 0) SPECIAL_DIGITS else PERMISSION_DIGITS
        return text.padStart(digits, '0')
    }

    /** Parses three or four octal digits (`"644"`, `"0755"`, `"4755"`), or null if invalid. */
    fun parseOctal(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.length !in PERMISSION_DIGITS..SPECIAL_DIGITS || trimmed.any { it !in '0'..'7' }) return null
        return trimmed.toInt(OCTAL)
    }

    /** A file name the user typed is usable: not blank, no `/`, not `.` or `..`. */
    fun isValidName(name: String): Boolean = name.isNotBlank() && '/' !in name && name != "." && name != ".."

    /**
     * A file name from somewhere untrusted (a server's directory listing, a document provider's
     * display name) made safe to use as a single path segment: path separators, control
     * characters (including terminal escapes) and bidirectional overrides, which can disguise an
     * extension, become `_`. With [forDocument], so do the characters Android's storage rejects.
     * Falls back to [fallback] if nothing usable is left (`""`, `.`, `..`).
     */
    fun safeName(
        name: String,
        fallback: String,
        forDocument: Boolean = false,
    ): String {
        val cleaned =
            buildString {
                name.forEach { c ->
                    val unsafe = c == '/' || c.isISOControl() || c in BIDI_CONTROLS || (forDocument && c in RESERVED)
                    append(if (unsafe) '_' else c)
                }
            }.trim().take(MAX_NAME_CHARS)
        return if (isValidName(cleaned)) cleaned else fallback
    }

    /** Human-readable size: `1.5 MB`. */
    fun formatSize(bytes: Long): String {
        var value = bytes.toDouble()
        var unit = 0
        while (value >= BYTES_PER_UNIT && unit < units.lastIndex) {
            value /= BYTES_PER_UNIT
            unit++
        }
        return if (unit == 0) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
    }

    /**
     * Whether text decoded from a file looks like it was not UTF-8 text: it contains NUL bytes
     * (binary) or replacement characters (bytes that were not valid UTF-8). Saving such a file from
     * the editor would corrupt it.
     */
    fun looksBinary(text: String): Boolean = '\u0000' in text || '�' in text

    private const val MAX_NAME_CHARS = 255
    private const val RESERVED = "\\:*?\"<>|"
    private const val BIDI_CONTROLS = "\u061c\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069"
    private const val OWNER_EXECUTE = 2
    private const val GROUP_EXECUTE = 5
    private const val OTHER_EXECUTE = 8
    private const val SETUID = 0b100_000_000_000
    private const val SETGID = 0b010_000_000_000
    private const val STICKY = 0b001_000_000_000
    private const val SPECIAL_MASK = SETUID or SETGID or STICKY
}
