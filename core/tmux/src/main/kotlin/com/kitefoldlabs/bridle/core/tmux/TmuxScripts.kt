package com.kitefoldlabs.bridle.core.tmux

import com.kitefoldlabs.bridle.core.model.LaunchMode
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.RestartMode
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.model.Validators
import com.kitefoldlabs.bridle.core.ssh.ShellQuote

/**
 * Builders for the POSIX `sh` scripts Bridle runs on a host (through `sh -s`, see
 * `SshConnection.runScript`). Every interpolated value is validated and passed through
 * [ShellQuote]; session names are restricted by [Validators.sessionName] and targeted with tmux's
 * exact-match `=name` syntax, so a session called `main` can never match `main-old`.
 */
public object TmuxScripts {
    /** tmux rewrites control characters (even tabs) in format output, and session names can't contain ':'. */
    internal const val FIELD_SEPARATOR: String = ":|:"
    internal const val PANES_MARKER: String = "@@BRIDLE_PANES@@"
    internal const val NO_TMUX_EXIT: Int = 127
    public const val DEFAULT_PREVIEW_LINES: Int = 40
    public const val MAX_PREVIEW_LINES: Int = 2_000

    /** Common install locations missing from non-interactive SSH sessions. */
    internal const val PATH_PRELUDE: String =
        "PATH=\"\$PATH:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/snap/bin:\$HOME/.local/bin:\$HOME/bin\"; " +
            "export PATH"

    private const val REQUIRE_TMUX: String =
        "command -v tmux >/dev/null 2>&1 || { echo 'tmux is not installed' >&2; exit $NO_TMUX_EXIT; }"

    private val keyName = Regex("^(?:[CMS]-)*[A-Za-z0-9]+$|^[!-~]$")

    private val sessionFormat =
        listOf("session_name", "session_created", "session_attached", "session_activity", "session_windows")
            .joinToString(FIELD_SEPARATOR) { "#{$it}" }

    // pane_current_path goes last: it is the only field that may contain unusual characters.
    private val paneFormat =
        listOf(
            "session_name",
            "pane_id",
            "pane_active",
            "window_active",
            "pane_dead",
            "pane_current_command",
            "pane_current_path",
        ).joinToString(FIELD_SEPARATOR) { "#{$it}" }

    public fun probe(helperPath: String): String =
        script(
            "printf 'tmux=%s\\n' \"\$(tmux -V 2>/dev/null)\"",
            "printf 'claude=%s\\n' \"\$(command -v claude 2>/dev/null)\"",
            "printf 'helper=%s\\n' \"\$(${ShellQuote.quotePath(helperPath)} version 2>/dev/null)\"",
        )

    /** Lists sessions, then [PANES_MARKER], then all panes. Prints nothing if no server runs. */
    public fun listSessions(): String =
        script(
            REQUIRE_TMUX,
            "tmux list-sessions -F ${ShellQuote.quote(sessionFormat)} 2>/dev/null || exit 0",
            "echo $PANES_MARKER",
            "tmux list-panes -a -F ${ShellQuote.quote(paneFormat)} 2>/dev/null || true",
        )

    /** The last [lines] lines of the session's active pane, wrapped lines joined. */
    public fun capturePane(
        session: String,
        lines: Int = DEFAULT_PREVIEW_LINES,
    ): String {
        requireValid(Validators.sessionName(session))
        require(lines in 1..MAX_PREVIEW_LINES) { "lines must be in 1..$MAX_PREVIEW_LINES" }
        return script(REQUIRE_TMUX, tmux("capture-pane", "-p", "-J", "-t", pane(session), "-S", "-$lines"))
    }

    /**
     * Creates the session if it does not exist and starts the profile's command in it.
     * Prints `created` or `exists`.
     */
    public fun launch(
        profile: LaunchProfile,
        columns: Int? = null,
        rows: Int? = null,
    ): String {
        requireValid(profile.validate())
        require((columns == null) == (rows == null)) { "Give both columns and rows, or neither" }
        val exists = tmux("has-session", "-t", "=${profile.sessionName}")
        return script(
            listOf(REQUIRE_TMUX, "if $exists 2>/dev/null; then echo exists; exit 0; fi") +
                launchLines(profile, columns, rows) +
                "echo created",
        )
    }

    /**
     * Restarts the profile's command in place (keeping the session name and window layout), or
     * launches it if the session is gone. Prints `restarted` or `created`.
     */
    public fun restart(profile: LaunchProfile): String {
        requireValid(profile.validate())
        val name = profile.sessionName
        val command = envPrefix(profile) + profile.effectiveRestartCommand
        val body =
            when {
                profile.restartMode == RestartMode.SEND_KEYS ->
                    listOf(
                        tmux("send-keys", "-t", pane(name), "C-c"),
                        "sleep 0.3",
                        tmux("send-keys", "-t", pane(name), "C-c"),
                        "sleep 0.5",
                    ) + typeCommand(name, command)
                profile.mode == LaunchMode.DIRECT ->
                    listOf(tmux("respawn-pane", "-k", "-t", pane(name), "-c", PathArg(profile.workingDir), command))
                else ->
                    listOf(tmux("respawn-pane", "-k", "-t", pane(name), "-c", PathArg(profile.workingDir))) +
                        typeCommand(name, command)
            }
        return script(
            listOf(REQUIRE_TMUX, "if ! ${tmux("has-session", "-t", "=$name")} 2>/dev/null; then") +
                launchLines(profile, null, null).map { "  $it" } +
                listOf("  echo created", "  exit 0", "fi") +
                body +
                "echo restarted",
        )
    }

    public fun kill(session: String): String {
        requireValid(Validators.sessionName(session))
        return script(REQUIRE_TMUX, "${tmux("kill-session", "-t", "=$session")} 2>/dev/null || true")
    }

    /** Types [text] literally into the pane, optionally followed by Enter. */
    public fun sendText(
        session: String,
        text: String,
        enter: Boolean,
    ): String {
        requireValid(Validators.sessionName(session))
        require('\u0000' !in text) { "Text may not contain NUL" }
        val literal = if (text.isEmpty()) "" else tmux("send-keys", "-t", pane(session), "-l", "--", text)
        val press = if (enter) tmux("send-keys", "-t", pane(session), "Enter") else ""
        return script(REQUIRE_TMUX, literal, press)
    }

    /** Sends named tmux keys (`Escape`, `C-c`, `BTab`, `Up`, `y`…). */
    public fun sendKeys(
        session: String,
        keys: List<String>,
    ): String {
        requireValid(Validators.sessionName(session))
        require(keys.isNotEmpty() && keys.all { keyName.matches(it) }) { "Invalid key names: $keys" }
        return script(REQUIRE_TMUX, tmux(listOf("send-keys", "-t", pane(session), "--") + keys))
    }

    /**
     * The command for an interactive PTY that attaches to [session], creating it in [workingDir]
     * if needed. It is run as the SSH exec command, so it must parse the same in bash, zsh and
     * fish: the fixed program contains no `'` or `\`, and arguments may not contain `\`.
     */
    public fun attachCommand(
        session: String,
        workingDir: String = "~",
    ): String {
        requireValid(Validators.sessionName(session))
        require('\\' !in workingDir) { "Working directory may not contain backslashes" }
        require(workingDir.none { it.isISOControl() }) { "Working directory may not contain control characters" }
        val program =
            "$PATH_PRELUDE; " +
                "case \"\$2\" in \"~\"*) d=\"\$HOME\${2#?}\" ;; *) d=\"\$2\" ;; esac; " +
                "exec tmux new-session -A -s \"\$1\" -c \"\$d\""
        return "sh -c '$program' bridle ${ShellQuote.quote(session)} ${ShellQuote.quote(workingDir)}"
    }

    /** Lines that create the session and start the command (assumes it does not exist). */
    private fun launchLines(
        profile: LaunchProfile,
        columns: Int?,
        rows: Int?,
    ): List<String> {
        val name = profile.sessionName
        val size: List<Any> =
            if (columns != null && rows != null) listOf("-x", "$columns", "-y", "$rows") else emptyList()
        val create = listOf("new-session", "-d", "-s", name, "-c", PathArg(profile.workingDir)) + size
        val window = "=$name:"
        val start =
            when (profile.mode) {
                LaunchMode.SHELL_HOSTED ->
                    listOf(tmux(create)) + typeCommand(name, envPrefix(profile) + profile.command)
                LaunchMode.DIRECT ->
                    listOf(
                        tmux(create + (envPrefix(profile) + profile.command)),
                        tmux("set-option", "-w", "-t", window, "remain-on-exit", "on"),
                    )
            }
        return start + "${tmux("set-option", "-t", "=$name", "window-size", "latest")} 2>/dev/null || true"
    }

    private fun typeCommand(
        session: String,
        command: String,
    ): List<String> =
        listOf(
            tmux("send-keys", "-t", pane(session), "-l", "--", command),
            tmux("send-keys", "-t", pane(session), "Enter"),
        )

    private fun envPrefix(profile: LaunchProfile): String =
        profile.env.entries.joinToString("") { (key, value) -> "export $key=${ShellQuote.quote(value)}; " }

    private fun pane(session: String) = "=$session:"

    /** A path argument: quoted, but a leading `~` still expands to the remote home directory. */
    private class PathArg(
        val path: String,
    )

    private fun tmux(vararg args: Any): String = tmux(args.asList())

    private fun tmux(args: List<Any>): String =
        "tmux " +
            args.joinToString(" ") {
                if (it is PathArg) ShellQuote.quotePath(it.path) else ShellQuote.quote(it.toString())
            }

    private fun script(vararg lines: String): String = script(lines.asList())

    private fun script(lines: List<String>): String =
        (listOf(PATH_PRELUDE) + lines.filter { it.isNotEmpty() }).joinToString("\n", postfix = "\n")

    private fun requireValid(validation: Validation) {
        require(validation is Validation.Valid) { "Invalid input: $validation" }
    }
}
