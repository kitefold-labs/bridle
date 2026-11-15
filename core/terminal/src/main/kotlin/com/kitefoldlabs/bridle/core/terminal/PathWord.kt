package com.kitefoldlabs.bridle.core.terminal

/**
 * The path at the end of a shell command line, as bash and zsh would read it, for completing
 * file names. [parse] it from the typed line, [resolveDirectory] says which directory to list,
 * and [insertion] gives the text to type for a chosen entry.
 *
 * Words that would be expanded (`$`, backticks, globs, braces, `!` history, `~user`) or that sit
 * in a comment are refused, so a suggestion never means something other than the file it names.
 */
public data class PathWord(
    /** The path as the shell sees it, unquoted, with a leading `~/` left in place when [tilde]. */
    val path: String,
    /** The quote the line ends inside, which escaping must continue. */
    val quote: Quote,
    /** True when the path starts with an unquoted `~/`, which the shell expands to the home directory. */
    val tilde: Boolean = false,
    /**
     * True when the word names the command to run (the first word, or one after `; | & ( )`):
     * the shell looks it up in PATH unless it contains a `/`.
     */
    val isCommand: Boolean = false,
) {
    public enum class Quote { NONE, SINGLE, DOUBLE }

    /** The part after the last `/`: what entries must start with. */
    val prefix: String get() = path.substringAfterLast('/')

    /** The part up to and including the last `/`, or empty for the working directory. */
    val directory: String get() = path.substring(0, path.length - prefix.length)

    /**
     * The absolute directory to list, ending in `/`, given the shell's working directory [cwd] and
     * [home] (needed for `~/`); null when either is needed and missing or not absolute. `.`
     * segments and repeated slashes are removed; `..` is kept, since it depends on symlinks.
     */
    public fun resolveDirectory(
        cwd: String?,
        home: String?,
    ): String? {
        val dir = directory
        val absolute =
            when {
                tilde -> home?.takeIf { it.startsWith("/") }?.let { join(it, dir.substring(2)) }
                dir.startsWith("/") -> dir
                else -> cwd?.takeIf { it.startsWith("/") }?.let { join(it, dir) }
            }
        return absolute?.let(::normalize)
    }

    /**
     * What to type to complete [name] (an entry of [resolveDirectory]), escaped for [quote], with
     * a `/` after a directory. Null if [name] doesn't start with [prefix] or can't be typed safely
     * here (control characters, or `!` inside double quotes, where history expansion applies).
     */
    public fun insertion(
        name: String,
        isDirectory: Boolean,
    ): String? {
        if (!name.startsWith(prefix) || !isTypeable(name)) return null
        val rest = name.substring(prefix.length)
        val escaped =
            when (quote) {
                Quote.NONE -> escapeUnquoted(rest)
                Quote.SINGLE -> rest.replace("'", "'\\''")
                Quote.DOUBLE -> if ('!' in rest) return null else rest.replace(DOUBLE_SPECIAL) { "\\${it.value}" }
            }
        return if (isDirectory) "$escaped/" else escaped
    }

    public companion object {
        private val DOUBLE_SPECIAL = Regex("[\"\\\\$`]")
        private const val PLAIN_PUNCTUATION = "_./+,:@%-"
        private const val OPERATORS = ";|&()"
        private const val REDIRECTIONS = "<>"
        private const val EXPANSIONS = "$`*?[{!"

        /**
         * Control characters can't be typed, U+FFFD means the host's name wasn't valid UTF-8 (the
         * bytes typed back would differ), and an unpaired surrogate can't be encoded at all.
         */
        private fun isTypeable(name: String): Boolean =
            name.codePoints().noneMatch { cp ->
                Character.isISOControl(cp) || cp == REPLACEMENT || cp in MIN_SURROGATE..MAX_SURROGATE
            }

        /**
         * Backslash-escapes [text] for an unquoted word. Escaping goes by code point, so a
         * character outside the BMP (an emoji, say) gets one backslash before its whole
         * encoding, never one before each of its UTF-16 halves.
         */
        private fun escapeUnquoted(text: String): String =
            buildString(text.length) {
                text.codePoints().forEach { cp ->
                    if (!isPlain(cp)) append('\\')
                    appendCodePoint(cp)
                }
            }

        /** Code points typed unquoted as themselves; everything else gets a backslash. */
        private fun isPlain(cp: Int): Boolean =
            cp in LOWER ||
                cp in UPPER ||
                cp in DIGITS ||
                (cp <= ASCII_MAX && cp.toChar() in PLAIN_PUNCTUATION) ||
                (cp > ASCII_MAX && Character.isLetterOrDigit(cp))

        private const val ASCII_MAX = 0x7F
        private const val REPLACEMENT = 0xFFFD
        private const val MIN_SURROGATE = 0xD800
        private const val MAX_SURROGATE = 0xDFFF
        private val LOWER = 'a'.code..'z'.code
        private val UPPER = 'A'.code..'Z'.code
        private val DIGITS = '0'.code..'9'.code

        private fun join(
            base: String,
            relative: String,
        ): String = "$base/$relative"

        private fun normalize(dir: String): String {
            val segments = dir.split('/').filter { it.isNotEmpty() && it != "." }
            return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/", postfix = "/")
        }

        /** The path the [line] ends with, or null if there is none to complete. */
        public fun parse(line: String): PathWord? = Parser(line).run()
    }

    /** One pass over the line, tracking quotes and where the current word's path starts. */
    private class Parser(
        private val line: String,
    ) {
        private var quote = Quote.NONE
        private val value = StringBuilder()
        private var wordStarted = false
        private var tilde = false
        private var refused = false
        private var commandWord = true

        fun run(): PathWord? {
            var i = 0
            while (i < line.length) {
                val c = line[i]
                i =
                    when (quote) {
                        Quote.SINGLE -> single(c, i + 1)
                        Quote.DOUBLE -> double(c, i + 1) ?: return null
                        Quote.NONE -> none(c, i + 1) ?: return null
                    }
            }
            // "~" alone has no directory yet, and "~user" isn't supported.
            val tildeOk = !tilde || (value.length > 1 && value[1] == '/')
            return if (refused || !tildeOk) null else PathWord(value.toString(), quote, tilde, commandWord)
        }

        /** Handles [c] inside single quotes, where nothing is special but the closing quote. */
        private fun single(
            c: Char,
            next: Int,
        ): Int {
            if (c == '\'') quote = Quote.NONE else value.append(c)
            return next
        }

        /** Handles unquoted [c]; null when nothing after it can be completed (a comment, a dangling `\`). */
        private fun none(
            c: Char,
            next: Int,
        ): Int? {
            if (c == '#' && !wordStarted) return null
            if (c != '\\') {
                unquoted(c)
                return next
            }
            if (next >= line.length) return null
            take(line[next])
            return next + 1
        }

        /**
         * Handles [c] inside double quotes, where a backslash escapes only `$`, a backtick, `"`
         * and itself. Returns the next index, or null for a dangling `\`: the escaping of an
         * inserted name would merge with it (`"a\` + `\"` reads as `"a\\"`, closing the quote).
         */
        private fun double(
            c: Char,
            next: Int,
        ): Int? {
            when {
                c == '"' -> quote = Quote.NONE
                c == '\\' && next >= line.length -> return null
                c == '\\' && line[next] in "$`\"\\" -> {
                    take(line[next])
                    return next + 1
                }
                c == '$' || c == '`' || c == '!' -> refused = true
                else -> take(c)
            }
            return next
        }

        private fun unquoted(c: Char) {
            when {
                c == ' ' || c == '\t' -> {
                    if (wordStarted) commandWord = false
                    newWord()
                }
                c in REDIRECTIONS -> {
                    newWord()
                    commandWord = false // the target of > or < is a file
                }
                c in OPERATORS -> {
                    newWord()
                    commandWord = true
                }
                c == '=' -> {
                    newWord(keepStarted = true) // --opt=path: complete after the '='
                    commandWord = false
                }
                c == '\'' -> quoteStart(Quote.SINGLE)
                c == '"' -> quoteStart(Quote.DOUBLE)
                c in EXPANSIONS -> refused = true
                c == '~' && !wordStarted -> {
                    tilde = true
                    take(c)
                }
                else -> take(c)
            }
        }

        private fun quoteStart(next: Quote) {
            wordStarted = true
            quote = next
        }

        private fun take(c: Char) {
            wordStarted = true
            value.append(c)
        }

        private fun newWord(keepStarted: Boolean = false) {
            value.setLength(0)
            tilde = false
            refused = false
            wordStarted = keepStarted
        }
    }
}
