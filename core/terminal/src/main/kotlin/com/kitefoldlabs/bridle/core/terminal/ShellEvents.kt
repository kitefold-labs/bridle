package com.kitefoldlabs.bridle.core.terminal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * What a shell reports through its integration codes: OSC 133 prompt marks and the OSC 7 working
 * directory (see `docs/command-line-suggestions.md`). Any program's output can contain these
 * codes, so they are hints, never trusted input.
 */
public sealed interface ShellEvent {
    /** OSC 133 codes: [pane] is the tmux pane from the `aid=` parameter, or null outside tmux. */
    public sealed interface Mark : ShellEvent {
        public val pane: String?
    }

    /** `OSC 133;A`: a prompt is about to be drawn. */
    public data class PromptStart(
        override val pane: String? = null,
    ) : Mark

    /** `OSC 133;B`: the prompt is drawn and input starts. Sent again on every prompt redraw. */
    public data class InputStart(
        override val pane: String? = null,
    ) : Mark

    /** `OSC 133;C`: a command starts. */
    public data class CommandStart(
        override val pane: String? = null,
    ) : Mark

    /** `OSC 133;D[;status]`: a command finished, or the line was abandoned (Ctrl-C, no `C`). */
    public data class CommandEnd(
        val status: Int? = null,
        override val pane: String? = null,
    ) : Mark

    /** `OSC 7;file://host/path`: the shell's working directory, percent-decoded. */
    public data class WorkingDirectory(
        val host: String,
        val path: String,
    ) : ShellEvent
}

/** Parses the content of OSC strings into [ShellEvent]s. */
public object ShellEvents {
    private const val MARK_PREFIX = "133;"
    private const val DIRECTORY_PREFIX = "7;file://"
    private const val PANE_KEY = "aid="
    private val paneId = Regex("[A-Za-z0-9%_.:-]{1,32}")
    private const val HEX_RADIX = 16
    private const val PERCENT_ESCAPE_LENGTH = 3
    private val PRINTABLE_ASCII = 0x21..0x7E

    /**
     * The event for an OSC string's [content] (the bytes between `ESC ]` and the terminator,
     * e.g. `133;D;1;aid=%3`), or null if it is not a code Bridle uses or is malformed.
     */
    public fun parseOsc(content: String): ShellEvent? =
        when {
            content.startsWith(MARK_PREFIX) -> parseMark(content.substring(MARK_PREFIX.length))
            content.startsWith(DIRECTORY_PREFIX) -> parseDirectory(content.substring(DIRECTORY_PREFIX.length))
            else -> null
        }

    private fun parseMark(body: String): ShellEvent.Mark? {
        val parts = body.split(';')
        // Other keys (kitty's k=, cl=, …) are ignored; an aid that isn't a plain id is dropped.
        val pane =
            parts
                .drop(1)
                .lastOrNull { it.startsWith(PANE_KEY) }
                ?.substring(PANE_KEY.length)
                ?.takeIf(paneId::matches)
        return when (parts[0]) {
            "A" -> ShellEvent.PromptStart(pane)
            "B" -> ShellEvent.InputStart(pane)
            "C" -> ShellEvent.CommandStart(pane)
            "D" -> ShellEvent.CommandEnd(parts.getOrNull(1)?.toIntOrNull(), pane)
            else -> null
        }
    }

    /** `host/path` after `file://`: a URL is printable ASCII, and the path must be absolute. */
    private fun parseDirectory(rest: String): ShellEvent.WorkingDirectory? {
        val slash = rest.indexOf('/')
        if (slash < 0 || rest.any { it.code !in PRINTABLE_ASCII }) return null
        val path = percentDecode(rest.substring(slash))?.takeIf { decoded -> decoded.none { it.isISOControl() } }
        return path?.let { ShellEvent.WorkingDirectory(host = rest.substring(0, slash), path = it) }
    }

    /** Decodes `%XX` escapes as UTF-8 bytes; null on a bad escape or invalid UTF-8. */
    private fun percentDecode(text: String): String? {
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + PERCENT_ESCAPE_LENGTH > text.length) return null
                val hi = Character.digit(text[i + 1], HEX_RADIX)
                val lo = Character.digit(text[i + 2], HEX_RADIX)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * HEX_RADIX + lo)
                i += PERCENT_ESCAPE_LENGTH
            } else {
                bytes.write(c.code)
                i++
            }
        }
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

/**
 * What the shell integration has reported so far, folded from [ShellEvent]s with [after].
 *
 * Inside tmux, codes from every visible pane arrive interleaved, each tagged with its pane;
 * deciding which pane the user is typing in is left to the caller.
 */
public data class ShellPrompt(
    /** True once any OSC 133 code has arrived: the shell has Bridle's (or compatible) integration. */
    val active: Boolean = false,
    /** True between a prompt (`A` or `B`) and the next command start (`C`) or end (`D`). */
    val atPrompt: Boolean = false,
    /** The pane of the last OSC 133 code; null outside tmux. */
    val pane: String? = null,
    /** Each pane's working directory (key null outside tmux), from the OSC 7 sent before its `A`. */
    val directories: Map<String?, String> = emptyMap(),
    /** An OSC 7 directory waiting for the next `A`, which says whose it is. */
    val pendingDirectory: String? = null,
) {
    /** [pane]'s working directory, if its shell has reported one. */
    public fun directory(pane: String?): String? = directories[pane]

    public fun after(event: ShellEvent): ShellPrompt =
        when (event) {
            is ShellEvent.WorkingDirectory -> copy(pendingDirectory = event.path)
            is ShellEvent.PromptStart ->
                copy(
                    active = true,
                    atPrompt = true,
                    pane = event.pane,
                    directories = pendingDirectory?.let { withDirectory(event.pane, it) } ?: directories,
                    pendingDirectory = null,
                )
            is ShellEvent.InputStart -> copy(active = true, atPrompt = true, pane = event.pane)
            is ShellEvent.CommandStart -> copy(active = true, atPrompt = false, pane = event.pane)
            is ShellEvent.CommandEnd -> copy(active = true, atPrompt = false, pane = event.pane)
        }

    /** Adds [path] for [pane] as the newest entry, keeping at most [MAX_PANES] (forged ids can't grow it). */
    private fun withDirectory(
        pane: String?,
        path: String,
    ): Map<String?, String> {
        val updated = LinkedHashMap(directories)
        updated.remove(pane)
        updated[pane] = path
        while (updated.size > MAX_PANES) updated.remove(updated.keys.first())
        return updated
    }

    public companion object {
        /** Most panes whose directory is remembered. */
        public const val MAX_PANES: Int = 64
    }
}
