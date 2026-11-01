package com.kitefoldlabs.bridle.core.backup

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

/** Source file names from the binary look like `Foo.kt`; anything else is not printed. */
private val SAFE_FILE_NAME = Regex("""[\w$.-]{1,100}""")

/**
 * A scrubbed, size-capped crash report the user can read in full and file as a GitHub issue.
 *
 * Only these leave the device: app version, OS version, device model, the time, exception class
 * names, **scrubbed** exception messages (see [Scrubber]) and stack frames (class, method, source
 * file name and line, which come from the app binary and are safe). Terminal content, hostnames,
 * usernames and paths are never added on purpose, and the scrubber removes them from messages.
 *
 * Serializable so the uncaught-exception handler can save it and the next launch can offer it.
 */

@Serializable
public class CrashReport internal constructor(
    /** Fully qualified class of the top-level throwable. */
    public val exceptionType: String,
    /** Version / device / time lines. */
    public val header: String,
    /** The scrubbed exception chain with stack frames. */
    public val trace: String,
) {
    /** The complete report, exactly as it will be submitted. */
    public val text: String get() = "$header\n\n$trace"

    /** A short issue title, e.g. `Crash: IllegalStateException`. */
    public val title: String get() = "Crash: " + exceptionType.substringAfterLast('.')

    override fun toString(): String = text

    public companion object {
        /** Cap on [trace]; [GitHubIssueUrl] trims further to fit a URL. */
        public const val MAX_TRACE_CHARS: Int = 12_000
        public const val MAX_FRAMES_PER_THROWABLE: Int = 60
        public const val MAX_NESTING: Int = 8
        public const val MAX_MESSAGE_CHARS: Int = 500
        public const val MAX_FIELD_CHARS: Int = 100
        public const val TRUNCATED: String = "[truncated]"

        /** Frames from these packages are the app's own. */
        public const val APP_PACKAGE: String = "com.kitefoldlabs."

        /**
         * Runs of more than this many consecutive framework frames are shortened to their first
         * [KEEP_RUN_HEAD] and last [KEEP_RUN_TAIL] frames.
         */
        public const val MAX_FRAMEWORK_RUN: Int = 6
        private const val KEEP_RUN_HEAD = 3
        private const val KEEP_RUN_TAIL = 1

        /** Messages are pre-cut to this length before scrubbing, so huge messages stay cheap. */
        private const val MESSAGE_SCAN_CHARS = MAX_MESSAGE_CHARS * 4

        /**
         * Builds a report for [throwable].
         * @param sensitive known private values (hostnames, usernames, labels, session names, working
         *   directories from the user's configuration) to remove from messages, see [Scrubber.scrub].
         */
        public fun from(
            throwable: Throwable,
            appVersion: String,
            osVersion: String,
            deviceModel: String,
            timestampEpochMs: Long,
            sensitive: Collection<String> = emptyList(),
        ): CrashReport {
            val header =
                listOf(
                    "Bridle crash report",
                    "App version: ${field(appVersion)}",
                    "OS: ${field(osVersion)}",
                    "Device: ${field(deviceModel)}",
                    "Time: ${Instant.ofEpochMilli(timestampEpochMs)}",
                ).joinToString("\n")
            val trace = TraceWriter(sensitive).apply { write(throwable, emptyArray(), "", 0) }.toString()
            return CrashReport(throwable.javaClass.name, header, cap(trace, MAX_TRACE_CHARS))
        }

        /** Cuts [text] to at most [max] characters at a line break, ending with [TRUNCATED]. */
        internal fun cap(
            text: String,
            max: Int,
        ): String {
            if (text.length <= max) return text
            val room = text.take(max - TRUNCATED.length - 1)
            val cut = room.substringBeforeLast('\n', room)
            return "$cut\n$TRUNCATED"
        }

        /** Header fields are single-line: every control character becomes a space. */
        private fun field(value: String): String =
            value
                .map { if (it.isISOControl()) ' ' else it }
                .joinToString("")
                .trim()
                .take(MAX_FIELD_CHARS)

        /** Replaces control characters other than newline and tab. */
        internal fun clean(value: String): String =
            value.map { if (it.isISOControl() && it != '\n' && it != '\t') ' ' else it }.joinToString("")
    }

    private class TraceWriter(
        private val sensitive: Collection<String>,
    ) {
        private val out = StringBuilder()
        private val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())

        fun write(
            throwable: Throwable,
            enclosing: Array<StackTraceElement>,
            prefix: String,
            depth: Int,
        ) {
            if (!seen.add(throwable)) {
                out
                    .append(prefix)
                    .append("[circular reference: ")
                    .append(throwable.javaClass.name)
                    .append("]\n")
                return
            }
            out.append(prefix).append(throwable.javaClass.name)
            throwable.message?.let { out.append(": ").append(message(it)) }
            out.append('\n')

            val frames = throwable.stackTrace
            val common = commonTail(frames, enclosing)
            frameLines(frames.copyOfRange(0, frames.size - common)).forEach { out.append(it).append('\n') }
            if (common > 0) out.append("\t... ").append(common).append(" more\n")

            val nested =
                throwable.suppressed.map { "Suppressed: " to it } +
                    listOfNotNull(throwable.cause?.let { "Caused by: " to it })
            if (nested.isNotEmpty() && depth >= MAX_NESTING) {
                out.append("\t... ").append(nested.size).append(" nested exceptions omitted\n")
                return
            }
            nested.forEach { (label, child) -> write(child, frames, label, depth + 1) }
        }

        override fun toString(): String = out.toString().trimEnd('\n')

        private fun message(raw: String): String {
            val window = if (raw.length <= MESSAGE_SCAN_CHARS) raw else raw.take(MESSAGE_SCAN_CHARS).dropLastToken()
            val scrubbed = clean(Scrubber.scrub(window, sensitive))
            if (scrubbed.length <= MAX_MESSAGE_CHARS) return scrubbed
            return scrubbed.take(MAX_MESSAGE_CHARS) + " $TRUNCATED"
        }

        /** Drops a possibly cut-off last token, so half a hostname or address cannot slip through. */
        private fun String.dropLastToken(): String {
            val end = indexOfLast(Char::isWhitespace)
            return if (end < 0) Scrubber.REDACTED else substring(0, end)
        }

        private fun commonTail(
            frames: Array<StackTraceElement>,
            enclosing: Array<StackTraceElement>,
        ): Int {
            var n = 0
            val max = minOf(frames.size, enclosing.size)
            while (n < max && frames[frames.size - 1 - n] == enclosing[enclosing.size - 1 - n]) n++
            return n
        }

        private fun frameLines(frames: Array<StackTraceElement>): List<String> {
            val lines = mutableListOf<String>()
            var i = 0
            while (i < frames.size) {
                if (isApp(frames[i])) {
                    lines += frame(frames[i++])
                    continue
                }
                var end = i
                while (end < frames.size && !isApp(frames[end])) end++
                val run = frames.copyOfRange(i, end)
                if (run.size > MAX_FRAMEWORK_RUN) {
                    run.take(KEEP_RUN_HEAD).mapTo(lines, ::frame)
                    lines += "\t... ${run.size - KEEP_RUN_HEAD - KEEP_RUN_TAIL} framework frames"
                    run.takeLast(KEEP_RUN_TAIL).mapTo(lines, ::frame)
                } else {
                    run.mapTo(lines, ::frame)
                }
                i = end
            }
            if (lines.size <= MAX_FRAMES_PER_THROWABLE) return lines
            return lines.take(MAX_FRAMES_PER_THROWABLE) + "\t... ${lines.size - MAX_FRAMES_PER_THROWABLE} more frames"
        }

        private fun isApp(frame: StackTraceElement) = frame.className.startsWith(APP_PACKAGE)

        private fun frame(frame: StackTraceElement): String {
            val file = frame.fileName?.takeIf { SAFE_FILE_NAME.matches(it) }
            val location =
                when {
                    frame.isNativeMethod -> "Native Method"
                    file == null -> "Unknown Source"
                    frame.lineNumber >= 0 -> "$file:${frame.lineNumber}"
                    else -> file
                }
            return "\tat ${frame.className}.${frame.methodName}($location)"
        }
    }
}
