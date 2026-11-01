package com.kitefoldlabs.bridle.core.ssh

/**
 * POSIX `sh` quoting. This is the **only** sanctioned way to put a value into a remote command.
 *
 * Values made only of characters that are never special to `sh` are returned as-is (readable
 * commands); everything else is wrapped in single quotes, with embedded `'` written as `'\''`.
 * NUL bytes cannot be represented in a shell word and are rejected.
 */
public object ShellQuote {
    private val safe = Regex("^[A-Za-z0-9_@%+=:,./-]+$")

    public fun quote(value: String): String {
        require('\u0000' !in value) { "NUL bytes cannot be passed to a shell" }
        return when {
            value.isEmpty() -> "''"
            safe.matches(value) -> value
            else -> "'" + value.replace("'", "'\\''") + "'"
        }
    }

    /** Quotes each argument and joins them with spaces. */
    public fun join(args: List<String>): String = args.joinToString(" ") { quote(it) }

    /** Quotes each argument and joins them with spaces. */
    public fun join(vararg args: String): String = join(args.asList())

    /**
     * Quotes a path but keeps a leading `~` or `~/` unquoted so the remote shell expands it to the
     * user's home directory (`~/src/my app` → `~/'src/my app'`).
     */
    public fun quotePath(path: String): String =
        when {
            path == "~" -> "~"
            path.startsWith("~/") -> if (path.length == 2) "~/" else "~/" + quote(path.substring(2))
            else -> quote(path)
        }
}
