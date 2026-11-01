package com.kitefoldlabs.bridle.core.ssh

/**
 * Shell quoting. This is the **only** sanctioned way to put a value into a remote command.
 *
 * `exec` commands are parsed by the user's *login* shell, which may be sh, bash, zsh or fish, so
 * the result must mean the same thing to all of them. Values made only of characters that are
 * never special are returned as-is (readable commands); everything else is wrapped in single
 * quotes. `'` and `\` are written *outside* the quotes (`'\''` and `'\\'`): fish treats `\'` and
 * `\\` as escapes even inside single quotes, so a value ending in `\` would otherwise end the
 * quoted word early there and let the text after it run as code. A leading `=` is quoted because
 * zsh expands `=cmd` to a path. NUL bytes cannot be represented in a shell word and are rejected.
 */
public object ShellQuote {
    private val safe = Regex("^[A-Za-z0-9_@%+:,./-][A-Za-z0-9_@%+=:,./-]*$")

    public fun quote(value: String): String {
        require('\u0000' !in value) { "NUL bytes cannot be passed to a shell" }
        return when {
            value.isEmpty() -> "''"
            safe.matches(value) -> value
            else -> singleQuote(value)
        }
    }

    private fun singleQuote(value: String): String =
        buildString(value.length + 2) {
            append('\'')
            for (c in value) {
                when (c) {
                    '\'' -> append("'\\''")
                    '\\' -> append("'\\\\'")
                    else -> append(c)
                }
            }
            append('\'')
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
