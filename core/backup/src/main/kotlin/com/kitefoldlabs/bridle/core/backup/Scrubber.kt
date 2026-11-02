package com.kitefoldlabs.bridle.core.backup

/**
 * Removes anything that could identify the user, their machines or their credentials from free
 * text (exception messages, mostly). It errs on the side of over-scrubbing: a crash report with
 * `<host>` in place of a file name is still useful; one that leaks a hostname is not.
 *
 * Replaced, in this order:
 * 1. caller-supplied [sensitive] values (configured hostnames, usernames, labels…) → `<redacted>`,
 *    ignoring case, in one pass (so a value can't match inside the placeholder that replaced
 *    another); two-character values (a username such as `pi`) only as whole words
 * 2. URLs (any scheme) → `<url>`
 * 3. email addresses (also with `@` URL-encoded as `%40`) → `<email>`, then `user@host` → `<user>@<host>`
 * 4. `SHA256:` and MD5 colon-hex fingerprints → `<fingerprint>`
 * 5. absolute, home-relative and relative paths (`/home/…`, `/data/user/…`, `/storage/…`, `~/…`,
 *    `./…`, `../…`, `C:\…`, `\\server\…`) → `<path>`
 * 6. IPv6 (including zone ids and MAC-like colon hex) and IPv4 addresses → `<ip>`, even when glued
 *    to other text (`addr_10.0.0.1`); longer dotted-number runs are replaced whole
 * 7. dotted DNS names (any letters, `_` allowed) → `<host>`, except fully qualified JVM class names
 *    (`java.io.File`)
 * 8. hex strings of 32+ digits and base64-like blobs (20+ characters with a digit, or 40+) → `<redacted>`,
 *    including values of `key=value` pairs
 *
 * Single-label hostnames (`devbox`) and bare usernames cannot be recognised by shape; pass them in
 * [sensitive] to remove them too.
 */
public object Scrubber {
    public const val REDACTED: String = "<redacted>"
    public const val HOST: String = "<host>"
    public const val IP: String = "<ip>"
    public const val PATH: String = "<path>"
    public const val URL: String = "<url>"
    public const val EMAIL: String = "<email>"
    public const val USER_AT_HOST: String = "<user>@<host>"
    public const val FINGERPRINT: String = "<fingerprint>"

    /** Sensitive values this long are replaced wherever they appear, even inside a longer word. */
    public const val MIN_SENSITIVE_LENGTH: Int = 3

    /**
     * Shorter sensitive values, down to this length, are replaced only as whole words (not next to
     * a letter or digit). Single characters are ignored: replacing every "a" would destroy the text.
     */
    public const val MIN_SENSITIVE_WORD_LENGTH: Int = 2

    private const val MIN_IPV6_COLONS = 5
    private const val NOT_IN_WORD = """(?<![\p{L}\p{N}])"""
    private const val NOT_BEFORE_WORD = """(?![\p{L}\p{N}])"""

    /** Blobs without a digit are only redacted from this length on, to spare long identifiers. */
    private const val MIN_BLOB_WITHOUT_DIGIT = 40

    private val url = Regex("""\b[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>`]+""")
    private val email = Regex("""[A-Za-z0-9._%+-]+(?:@|%40)[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val userAtHost = Regex("""[A-Za-z0-9._-]+@(?:\[[0-9A-Za-z:.%_-]+]|[A-Za-z0-9._:%-]*[A-Za-z0-9])""")
    private val sha256 = Regex("""SHA256:[A-Za-z0-9+/]{43}=?""")
    private val md5 = Regex("""\b(?:[0-9A-Fa-f]{2}:){15}[0-9A-Fa-f]{2}\b""")
    private val windowsPath = Regex("""\b[A-Za-z]:\\[^\s"'<>`]*|\\\\[^\s"'<>`]+""")
    private val homePath = Regex("""(?<![\w.~-])(?:~|\.\.?)/[^\s"'<>`()\[\],;:]*""")
    private val absolutePath = Regex("""(?<![\w.~>/\\-])/[^\s"'<>`()\[\],;:]+""")
    private val ipv6 =
        Regex(
            """(?<![\w:.])(?:[0-9A-Fa-f]{0,4}:){2,8}""" +
                """(?:(?:\d{1,3}\.){3}\d{1,3}|[0-9A-Fa-f]{0,4})(?:%[\w.-]+)?(?![\w:]|\.\d)""",
        )
    private val ipv4 = Regex("""(?<!\d)(?:\d{1,3}\.){3,}\d{1,3}(?!\d)""")
    private val hostname =
        Regex(
            """(?<![\p{L}\p{N}_])(?:[\p{L}\p{N}_](?:[\p{L}\p{N}_-]{0,61}[\p{L}\p{N}_])?\.)+""" +
                """\p{L}[\p{L}\p{N}-]*[\p{L}\p{N}](?![\p{L}\p{N}_-]|\.[\p{L}\p{N}_])""",
        )
    private val className =
        Regex(
            """^(?:java|javax|kotlin|kotlinx|android|androidx|dalvik|libcore|com|org|net|io|sun|jdk)""" +
                """(?:\.[a-z_][a-z0-9_]*)*\.[A-Z][A-Za-z0-9_]*(?:\.[A-Z][A-Za-z0-9_]*)*$""",
        )
    private val hex = Regex("""(?<![\w+/-])[0-9A-Fa-f]{32,}(?![\w+/=-])""")
    private val blob = Regex("""(?<![\w+/-])[A-Za-z0-9+/_-]{20,}={0,2}(?![\w+/=-])""")

    /** Returns [text] with everything sensitive replaced by placeholders. */
    public fun scrub(
        text: String,
        sensitive: Collection<String> = emptyList(),
    ): String {
        var out = sensitivePattern(sensitive)?.replace(text, REDACTED) ?: text
        out = url.replace(out, URL)
        out = email.replace(out, EMAIL)
        out = userAtHost.replace(out, USER_AT_HOST)
        out = sha256.replace(out, FINGERPRINT)
        out = md5.replace(out, FINGERPRINT)
        out = windowsPath.replace(out, PATH)
        out = homePath.replace(out, PATH)
        out = absolutePath.replace(out, PATH)
        out = ipv6.replace(out) { if (isIpv6(it.value)) IP else it.value }
        out = ipv4.replace(out, IP)
        out = hostname.replace(out) { if (className.matches(it.value)) it.value else HOST }
        out = hex.replace(out, REDACTED)
        out = blob.replace(out) { if (isBlob(it.value)) REDACTED else it.value }
        return out
    }

    /** One alternation of [sensitive], longest first so a value wins over its own prefix. */
    private fun sensitivePattern(sensitive: Collection<String>): Regex? {
        val alternatives =
            sensitive
                .map(String::trim)
                .filter { it.length >= MIN_SENSITIVE_WORD_LENGTH }
                .distinctBy { it.lowercase() }
                .sortedByDescending(String::length)
                .map { value ->
                    val literal = Regex.escape(value)
                    if (value.length >= MIN_SENSITIVE_LENGTH) literal else "$NOT_IN_WORD$literal$NOT_BEFORE_WORD"
                }
        if (alternatives.isEmpty()) return null
        return Regex(alternatives.joinToString("|"), RegexOption.IGNORE_CASE) // Unicode-aware on the JVM
    }

    /** Colon-separated hex is an address (rather than a time like `12:34:56`) if compressed or long. */
    private fun isIpv6(candidate: String): Boolean =
        candidate.contains("::") || candidate.count { it == ':' } >= MIN_IPV6_COLONS

    private fun isBlob(candidate: String): Boolean =
        candidate.length >= MIN_BLOB_WITHOUT_DIGIT || candidate.any(Char::isDigit)
}
