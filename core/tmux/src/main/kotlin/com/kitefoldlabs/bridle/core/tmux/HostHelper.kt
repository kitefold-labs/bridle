package com.kitefoldlabs.bridle.core.tmux

import com.kitefoldlabs.bridle.core.ssh.ShellQuote
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** The optional `bridle-helper` script: bundled copy, install script and state protocol. */
public object HostHelper {
    public const val PROTOCOL_VERSION: Int = 1

    /** Where the helper is installed (`~` is the remote home directory). */
    public const val INSTALL_PATH: String = "~/.local/share/bridle/bridle-helper"

    private const val RESOURCE = "bridle-helper"
    private const val HEREDOC = "BRIDLE_HELPER_EOF_7f3a"
    internal const val INSTALLED_MARKER = "BRIDLE_HELPER_INSTALLED"

    private val json = Json { ignoreUnknownKeys = true }

    /** The helper script bundled with this build. */
    public val script: String by lazy {
        val stream =
            checkNotNull(HostHelper::class.java.getResourceAsStream(RESOURCE)) { "bridle-helper resource missing" }
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** SHA-256 of [script], shown to the user before installing and verified after upload. */
    public val sha256: String by lazy { sha256Hex(script.toByteArray(Charsets.UTF_8)) }

    /**
     * Writes [content] to [INSTALL_PATH] atomically, verifies its checksum with whichever of
     * `sha256sum`/`shasum` exists, and optionally installs the Claude Code hooks.
     */
    public fun installScript(
        content: String = script,
        installHooks: Boolean,
    ): String {
        require(content.lines().none { it == HEREDOC }) { "Helper content collides with the heredoc delimiter" }
        val expected = sha256Hex(content.toByteArray(Charsets.UTF_8))
        val path = ShellQuote.quotePath(INSTALL_PATH)
        val hooks = if (installHooks) "$path install-hooks || exit 3" else ""
        val text = if (content.endsWith("\n")) content else "$content\n"
        return buildString {
            appendLine("set -e")
            appendLine("umask 077")
            appendLine("mkdir -p ~/.local/share/bridle")
            appendLine("tmp=\$(mktemp ~/.local/share/bridle/.helper.XXXXXX)")
            appendLine("cat > \"\$tmp\" <<'$HEREDOC'")
            append(text)
            appendLine(HEREDOC)
            appendLine("if command -v sha256sum >/dev/null 2>&1; then sum=\$(sha256sum \"\$tmp\" | cut -d' ' -f1);")
            appendLine("else sum=\$(shasum -a 256 \"\$tmp\" | cut -d' ' -f1); fi")
            appendLine(
                "if [ \"\$sum\" != ${ShellQuote.quote(expected)} ]; then rm -f \"\$tmp\"; " +
                    "echo 'checksum mismatch' >&2; exit 4; fi",
            )
            appendLine("chmod 700 \"\$tmp\"")
            appendLine("mv -f \"\$tmp\" $path")
            if (hooks.isNotEmpty()) appendLine(hooks)
            appendLine("echo $INSTALLED_MARKER")
        }
    }

    /** Removes the hooks and the helper (state files are left for the user to delete). */
    public fun uninstallScript(): String {
        val path = ShellQuote.quotePath(INSTALL_PATH)
        return "if [ -x $path ]; then $path uninstall-hooks || true; rm -f $path; fi\necho removed\n"
    }

    /** Command that prints all session states, one JSON object per line. */
    public fun listCommand(): String = "${ShellQuote.quotePath(INSTALL_PATH)} list 2>/dev/null || true\n"

    @Serializable
    internal data class StateLine(
        val v: Int = 0,
        val state: String,
        @SerialName("tmux_session") val tmuxSession: String = "",
        @SerialName("session_id") val sessionId: String = "",
        val message: String = "",
        val ts: Long? = null,
    )

    /** Parses `bridle-helper list` output into statuses keyed by tmux session name. */
    public fun parseStates(output: String): Map<String, ClaudeStatus> =
        output
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { line ->
                try {
                    json.decodeFromString(StateLine.serializer(), line)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }.filter { it.tmuxSession.isNotEmpty() }
            .groupBy { it.tmuxSession }
            .mapValues { (_, lines) ->
                val latest = lines.maxBy { it.ts ?: 0 }
                ClaudeStatus(
                    state = stateOf(latest.state),
                    source = StatusSource.HOOKS,
                    message = latest.message.takeIf { it.isNotEmpty() },
                    updatedEpochSeconds = latest.ts,
                )
            }

    internal fun stateOf(value: String): ClaudeState =
        when (value) {
            "working" -> ClaudeState.WORKING
            "waiting_permission" -> ClaudeState.WAITING_PERMISSION
            "waiting_input" -> ClaudeState.WAITING_INPUT
            "idle" -> ClaudeState.IDLE
            "exited" -> ClaudeState.EXITED
            else -> ClaudeState.UNKNOWN
        }

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
