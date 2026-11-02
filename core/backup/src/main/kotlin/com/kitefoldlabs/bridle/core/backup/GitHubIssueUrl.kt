package com.kitefoldlabs.bridle.core.backup

import java.nio.charset.StandardCharsets

/**
 * Builds the pre-filled "new issue" URL for a [CrashReport], matching
 * `.github/ISSUE_TEMPLATE/crash_report.yml` (fields `context` and `report`). The app opens it in
 * the browser; nothing is sent until the user reviews the issue and presses Submit on GitHub.
 *
 * Browsers and GitHub reject very long URLs, so the result is capped at [MAX_URL_LENGTH]: the
 * stack trace is trimmed at a line break and marked [CrashReport.TRUNCATED]; only if that is not
 * enough are the user's notes trimmed too.
 */
public object GitHubIssueUrl {
    public const val DEFAULT_REPO: String = "kitefold-labs/bridle"
    public const val TEMPLATE: String = "crash_report.yml"
    public const val MAX_URL_LENGTH: Int = 7_500
    public const val MAX_NOTES_CHARS: Int = 2_000

    private val repoPattern = Regex("""[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}""")
    private const val HEX = "0123456789ABCDEF"
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0xF
    private const val BYTE_MASK = 0xFF

    /**
     * @param repo `owner/name` of the GitHub repository.
     * @param userNotes what the user was doing, sent as-is (they can still edit it on GitHub).
     * @throws IllegalArgumentException if [repo] is not `owner/name`, or [maxLength] cannot fit even
     *   an empty report.
     */
    public fun build(
        repo: String = DEFAULT_REPO,
        report: CrashReport,
        userNotes: String = "",
        maxLength: Int = MAX_URL_LENGTH,
    ): String {
        require(repoPattern.matches(repo)) { "repo must be owner/name" }
        val base = "https://github.com/$repo/issues/new?template=$TEMPLATE&title=${encode(report.title)}"

        fun url(
            notes: String,
            body: String,
        ) = "$base&context=${encode(notes)}&report=${encode(body)}"

        fun fits(
            notes: String,
            body: String,
        ) = url(notes, body).length <= maxLength

        val notes = safeTake(userNotes, MAX_NOTES_CHARS)
        if (fits(notes, report.text)) return url(notes, report.text)

        val headerOnly = report.header + "\n\n"
        val marked = { trace: String ->
            val lines = trace.substringBeforeLast('\n', "")
            headerOnly + (if (lines.isEmpty()) "" else lines + "\n") + CrashReport.TRUNCATED
        }
        if (fits(notes, marked(""))) {
            val trace = longestPrefix(report.trace) { fits(notes, marked(it)) }
            return url(notes, marked(trace))
        }
        val minimal = marked("")
        require(fits("", minimal)) { "maxLength $maxLength is too small for a crash report" }
        return url(longestPrefix(notes) { fits(it, minimal) }, minimal)
    }

    /** RFC 3986 percent-encoding of UTF-8: everything but unreserved characters is escaped. */
    internal fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
            val b = byte.toInt() and BYTE_MASK
            val c = b.toChar()
            if (c in UNRESERVED) {
                out.append(c)
            } else {
                out.append('%').append(HEX[b shr NIBBLE_BITS]).append(HEX[b and NIBBLE_MASK])
            }
        }
        return out.toString()
    }

    /** The longest prefix of [text] (never splitting a surrogate pair) that satisfies [fits]. */
    private fun longestPrefix(
        text: String,
        fits: (String) -> Boolean,
    ): String {
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (fits(safeTake(text, mid))) low = mid else high = mid - 1
        }
        return safeTake(text, low)
    }

    private fun safeTake(
        text: String,
        n: Int,
    ): String {
        if (n >= text.length) return text
        return if (n > 0 && text[n - 1].isHighSurrogate()) text.take(n - 1) else text.take(n)
    }
}
