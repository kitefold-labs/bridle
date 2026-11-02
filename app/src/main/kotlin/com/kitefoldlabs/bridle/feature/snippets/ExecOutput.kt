package com.kitefoldlabs.bridle.feature.snippets

import com.kitefoldlabs.bridle.core.ssh.ExecResult

/**
 * A command's result made fit for a plain text view: its output (stdout, then stderr) cut to the
 * last [MAX_CHARS] characters, without terminal escape sequences or other control characters.
 * [omitted] is how many earlier characters were left out.
 *
 * Commands write colours, cursor movement and window titles for a terminal; shown as text they
 * are noise (or, from a crafted file, misleading). And `exec` keeps up to 4 MiB of each stream,
 * far more than a Compose `Text` lays out without freezing a low-end phone.
 */
data class ExecOutput(
    val exitStatus: Int?,
    val text: String,
    val omitted: Int,
) {
    companion object {
        const val MAX_CHARS: Int = 32 * 1024

        // CSI (ESC [ or the 8-bit 0x9B) up to its final byte; OSC (ESC ]) up to BEL or ST; then
        // any other escape: ESC, intermediate bytes (as in ESC ( B), and a final byte.
        private val ESCAPES =
            Regex(
                "(?:\\u001B\\[|\\u009B)[0-?]*[ -/]*[@-~]" +
                    "|\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)?" +
                    "|\\u001B[ -/]*[0-~]?",
            )

        fun of(result: ExecResult): ExecOutput {
            val total = result.stdout.length + result.stderr.length
            val omitted = (total - MAX_CHARS).coerceAtLeast(0)
            // Cut before cleaning up, so the work is bounded however much the command printed.
            val kept =
                if (omitted < result.stdout.length) {
                    result.stdout.substring(omitted) + result.stderr
                } else {
                    result.stderr.substring(omitted - result.stdout.length)
                }
            val text =
                ESCAPES
                    .replace(kept, "")
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            return ExecOutput(result.exitStatus, text, omitted)
        }
    }
}
