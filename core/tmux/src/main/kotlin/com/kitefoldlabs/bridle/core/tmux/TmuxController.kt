package com.kitefoldlabs.bridle.core.tmux

import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.model.Validators
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.RemoteCommandException
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import java.security.SecureRandom

public enum class LaunchResult { CREATED, ALREADY_RUNNING }

public enum class RestartResult { RESTARTED, CREATED }

/** tmux isn't installed on the host (the UI explains how to install it). */
public class TmuxNotInstalledException : Exception("tmux is not installed on this host")

/**
 * Agentless tmux/Claude operations over one [SshConnection]. Each call is a single `sh -s` script,
 * so it is cheap to call from UI refreshes.
 */
public class TmuxController(
    private val connection: SshConnection,
) {
    private val random = SecureRandom()

    public suspend fun probe(): HostProbe =
        TmuxParsers.parseProbe(run(TmuxScripts.probe(HostHelper.INSTALL_PATH)).stdout)

    /**
     * All sessions, newest activity first. With [useHelper], Claude status from bridle-helper
     * is merged in (one extra round trip).
     */
    public suspend fun listSessions(
        useHelper: Boolean = false,
        claudeHint: (String) -> Boolean = { false },
    ): List<SessionSummary> {
        val (sessions, panes) = TmuxParsers.parseList(run(TmuxScripts.listSessions()).stdout)
        val status =
            if (useHelper && sessions.isNotEmpty()) {
                HostHelper.parseStates(connection.runScript(HostHelper.listCommand()).stdout)
            } else {
                emptyMap()
            }
        return TmuxParsers.summarize(sessions, panes, status, claudeHint)
    }

    public suspend fun preview(
        session: String,
        lines: Int = TmuxScripts.DEFAULT_PREVIEW_LINES,
    ): String = run(TmuxScripts.capturePane(session, lines)).stdout

    /**
     * The last [lines] lines of each of [sessions]' active panes, in one round trip. Sessions whose
     * names Bridle can't target (created outside Bridle with spaces or the like) are left out.
     */
    public suspend fun previews(
        sessions: List<String>,
        lines: Int = TmuxScripts.DEFAULT_PREVIEW_LINES,
    ): Map<String, String> {
        val targetable = sessions.filter { Validators.sessionName(it) == Validation.Valid }
        if (targetable.isEmpty()) return emptyMap()
        // Fresh per call: what a pane prints can't predict it to fake another session's capture.
        val marker = "@@BRIDLE_CAPTURE_" + ByteArray(MARKER_BYTES).also(random::nextBytes).toHex() + "@@"
        val output = run(TmuxScripts.capturePanes(targetable, lines, marker)).stdout
        return TmuxParsers.parseCaptures(output, marker)
    }

    public suspend fun launch(
        profile: LaunchProfile,
        columns: Int? = null,
        rows: Int? = null,
    ): LaunchResult =
        when (val out = run(TmuxScripts.launch(profile, columns, rows)).stdout.trim()) {
            "created" -> LaunchResult.CREATED
            "exists" -> LaunchResult.ALREADY_RUNNING
            else -> throw RemoteCommandException("Unexpected launch output: $out", ExecResult(0, out, ""))
        }

    public suspend fun restart(profile: LaunchProfile): RestartResult =
        when (val out = run(TmuxScripts.restart(profile)).stdout.trim()) {
            "restarted" -> RestartResult.RESTARTED
            "created" -> RestartResult.CREATED
            else -> throw RemoteCommandException("Unexpected restart output: $out", ExecResult(0, out, ""))
        }

    public suspend fun kill(session: String) {
        run(TmuxScripts.kill(session))
    }

    public suspend fun sendText(
        session: String,
        text: String,
        enter: Boolean = true,
    ) {
        run(TmuxScripts.sendText(session, text, enter))
    }

    public suspend fun sendKeys(
        session: String,
        vararg keys: String,
    ) {
        run(TmuxScripts.sendKeys(session, keys.toList()))
    }

    /** Uploads bridle-helper (checksum-verified) and optionally installs the Claude Code hooks. */
    public suspend fun installHelper(installHooks: Boolean) {
        val result = connection.runScript(HostHelper.installScript(installHooks = installHooks))
        if (!result.isSuccess || HostHelper.INSTALLED_MARKER !in result.stdout) {
            throw RemoteCommandException("Installing bridle-helper failed", result)
        }
    }

    public suspend fun uninstallHelper() {
        val result = connection.runScript(HostHelper.uninstallScript())
        if (!result.isSuccess) throw RemoteCommandException("Removing bridle-helper failed", result)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private suspend fun run(script: String): ExecResult {
        val result = connection.runScript(script)
        return when {
            result.exitStatus == TmuxScripts.NO_TMUX_EXIT -> throw TmuxNotInstalledException()
            !result.isSuccess -> throw RemoteCommandException("tmux command failed", result)
            else -> result
        }
    }

    private companion object {
        const val MARKER_BYTES = 8
    }
}
