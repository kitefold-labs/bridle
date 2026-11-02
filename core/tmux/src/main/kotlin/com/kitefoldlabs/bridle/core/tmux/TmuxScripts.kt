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

    /** What a session runs until its pane is respawned with the environment in place. */
    private const val PLACEHOLDER: String = "cat"

    /**
     * The user's login shell, as tmux would start it. tmux sets `SHELL` for every pane, and the
     * line means the same in sh, bash, zsh and fish (tmux runs it with its `default-shell`).
     */
    private const val LOGIN_SHELL: String = "exec \"\$SHELL\" -l"

    /** Session option holding the names of the variables Bridle last set, so restart can drop removed ones. */
    private const val ENV_OPTION: String = "@bridle-env"

    private val keyName = Regex("^(?:[CMS]-)*[A-Za-z0-9]+$|^[!-~]$")
    private val captureMarker = Regex("^@@[A-Za-z0-9_]+@@$")

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
     * The last [lines] lines of the active pane of each of [sessions], in one round trip (status
     * polling would otherwise open a channel per session). Each capture follows a line of
     * [marker], a space and the session name; a session that vanished has an empty capture.
     * [marker] should be unpredictable (see [TmuxController.previews]), so pane text cannot fake
     * another session's capture.
     */
    public fun capturePanes(
        sessions: List<String>,
        lines: Int,
        marker: String,
    ): String {
        sessions.forEach { requireValid(Validators.sessionName(it)) }
        require(lines in 1..MAX_PREVIEW_LINES) { "lines must be in 1..$MAX_PREVIEW_LINES" }
        require(captureMarker.matches(marker)) { "Invalid marker: $marker" }
        val captures =
            sessions.flatMap { session ->
                listOf(
                    "echo ${ShellQuote.quote("$marker $session")}",
                    "${tmux("capture-pane", "-p", "-J", "-t", pane(session), "-S", "-$lines")} 2>/dev/null || true",
                )
            }
        return script(listOf(REQUIRE_TMUX) + captures)
    }

    /**
     * Creates the session if it does not exist and starts the profile's command in it.
     * Prints `created` or `exists`.
     *
     * The profile's environment variables are put in the tmux *session environment*, never typed
     * into the pane, so their values (API keys, say) stay off the screen, out of scrollback and out
     * of the shell's history: with `new-session -e` on [tmuxVersion] 3.2 and newer, otherwise by
     * starting the session on a placeholder, setting them with `set-environment` and respawning the
     * pane, whose new process then inherits them. Pass `null` when the version isn't known to get
     * the second way, which works everywhere.
     */
    public fun launch(
        profile: LaunchProfile,
        columns: Int? = null,
        rows: Int? = null,
        tmuxVersion: TmuxVersion? = null,
    ): String {
        requireValid(profile.validate())
        require((columns == null) == (rows == null)) { "Give both columns and rows, or neither" }
        val exists = tmux("has-session", "-t", "=${profile.sessionName}")
        return script(
            listOf(REQUIRE_TMUX, "if $exists 2>/dev/null; then echo exists; exit 0; fi") +
                launchLines(profile, columns, rows, tmuxVersion) +
                "echo created",
        )
    }

    /**
     * Restarts the profile's command in place (keeping the session name and window layout), or
     * launches it if the session is gone. Prints `restarted` or `created`.
     *
     * The environment variables are (re)set in the session environment first, and those the
     * profile no longer has are unset, so a respawned pane gets the profile's current values.
     * [RestartMode.SEND_KEYS] types into the shell that is already running, which keeps the
     * environment it started with.
     */
    public fun restart(
        profile: LaunchProfile,
        tmuxVersion: TmuxVersion? = null,
    ): String {
        requireValid(profile.validate())
        val name = profile.sessionName
        val command = profile.effectiveRestartCommand
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
                launchLines(profile, null, null, tmuxVersion).map { "  $it" } +
                listOf("  echo created", "  exit 0", "fi") +
                forgetEnvironment(name) +
                setEnvironment(profile) +
                recordEnvironment(profile) +
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
     * fish: the fixed program contains no `'` or `\`, and [Validators.workingDirectory] keeps `\`
     * out of the arguments.
     */
    public fun attachCommand(
        session: String,
        workingDir: String = "~",
    ): String {
        requireValid(Validators.sessionName(session))
        requireValid(Validators.workingDirectory(workingDir))
        val program =
            "$PATH_PRELUDE; " +
                "case \"\$2\" in \"~\" | \"~/\"*) d=\"\$HOME\${2#?}\" ;; *) d=\"\$2\" ;; esac; " +
                "exec tmux new-session -A -s \"\$1\" -c \"\$d\""
        return "sh -c '$program' bridle ${ShellQuote.quote(session)} ${ShellQuote.quote(workingDir)}"
    }

    /** Lines that create the session and start the command (assumes it does not exist). */
    private fun launchLines(
        profile: LaunchProfile,
        columns: Int?,
        rows: Int?,
        tmuxVersion: TmuxVersion?,
    ): List<String> {
        val name = profile.sessionName
        val size: List<Any> =
            if (columns != null && rows != null) listOf("-x", "$columns", "-y", "$rows") else emptyList()
        val create = listOf("new-session", "-d", "-s", name, "-c", PathArg(profile.workingDir)) + size
        // DIRECT runs the command as the pane's program; SHELL_HOSTED starts the shell and types it.
        val program = if (profile.mode == LaunchMode.DIRECT) listOf(profile.command) else emptyList()
        // A failed new-session (a session of that name started meanwhile, an option this tmux
        // lacks) must stop here, or the command would be typed into someone else's pane.
        val start =
            when {
                profile.env.isEmpty() -> listOf("${tmux(create + program)} || exit 1")
                tmuxVersion != null && tmuxVersion >= TmuxVersion.SESSION_ENV_FLAG -> {
                    val flags = profile.env.flatMap { (key, value) -> listOf("-e", "$key=$value") }
                    listOf("${tmux(create + flags + program)} || exit 1")
                }
                else -> {
                    // The placeholder waits until the respawn replaces it with a process that
                    // inherits the session environment set in between. Respawning with no command
                    // would rerun the placeholder, so a shell-hosted pane names the login shell.
                    // If that fails, the session goes rather than being left on the placeholder.
                    val respawn =
                        tmux(
                            listOf("respawn-pane", "-k", "-t", pane(name), "-c", PathArg(profile.workingDir)) +
                                program.ifEmpty { listOf(LOGIN_SHELL) },
                        )
                    listOf(
                        "${tmux(create + PLACEHOLDER)} || exit 1",
                        "if ! { ${(setEnvironment(profile) + respawn).joinToString(" && ")}; }; then",
                        "  ${tmux("kill-session", "-t", "=$name")} 2>/dev/null",
                        "  exit 1",
                        "fi",
                    )
                }
            }
        val run =
            when (profile.mode) {
                LaunchMode.SHELL_HOSTED -> typeCommand(name, profile.command)
                LaunchMode.DIRECT -> listOf(tmux("set-option", "-w", "-t", "=$name:", "remain-on-exit", "on"))
            }
        val record = if (profile.env.isEmpty()) emptyList() else listOf(recordEnvironment(profile))
        return start + record + run +
            "${tmux("set-option", "-w", "-t", "=$name:", "window-size", "latest")} 2>/dev/null || true"
    }

    private fun typeCommand(
        session: String,
        command: String,
    ): List<String> =
        listOf(
            tmux("send-keys", "-t", pane(session), "-l", "--", command),
            tmux("send-keys", "-t", pane(session), "Enter"),
        )

    /**
     * Sets the profile's variables in the session environment (`set-environment`, any tmux).
     * Values only ever travel as quoted arguments of the script Bridle runs through `sh -s`, never
     * through the pane, so nothing echoes them.
     */
    private fun setEnvironment(profile: LaunchProfile): List<String> =
        profile.env.map { (key, value) -> tmux("set-environment", "-t", "=${profile.sessionName}", key, value) }

    /** Unsets the variables recorded by [recordEnvironment] (names only, checked by [Validators.envName]). */
    private fun forgetEnvironment(session: String): String =
        "for n in \$(${tmux("show-options", "-qv", "-t", pane(session), ENV_OPTION)}); do " +
            "${tmux("set-environment", "-u", "-t", "=$session")} \"\$n\"; done"

    private fun recordEnvironment(profile: LaunchProfile): String =
        tmux("set-option", "-t", pane(profile.sessionName), ENV_OPTION, profile.env.keys.joinToString(" "))

    private fun pane(session: String) = "=$session:"

    /** A path argument: quoted, but a leading `~` still expands to the remote home directory. */
    private class PathArg(
        val path: String,
    )

    private fun tmux(vararg args: Any): String = tmux(args.asList())

    private fun tmux(args: List<Any>): String =
        "tmux " +
            args.joinToString(" ") {
                if (it is PathArg) ShellQuote.quotePath(tmuxArg(it.path)) else ShellQuote.quote(tmuxArg(it.toString()))
            }

    /**
     * tmux (3.0+) treats a command-line argument that *ends* in `;` as a command separator: the
     * `;` is dropped and the arguments after it run as a new tmux command (a trailing `\;` stands
     * for a literal `;`). Writing a trailing `;` as `\;` keeps every argument literal, so text typed
     * with `send-keys -l`, a path or a command can never end the tmux command early.
     */
    internal fun tmuxArg(value: String): String = if (value.endsWith(';')) value.dropLast(1) + "\\;" else value

    private fun script(vararg lines: String): String = script(lines.asList())

    private fun script(lines: List<String>): String =
        (listOf(PATH_PRELUDE) + lines.filter { it.isNotEmpty() }).joinToString("\n", postfix = "\n")

    private fun requireValid(validation: Validation) {
        require(validation is Validation.Valid) { "Invalid input: $validation" }
    }
}
