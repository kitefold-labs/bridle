package com.kitefoldlabs.bridle.core.ssh

/** Outcome of installing a public key on a host. */
public enum class KeyInstallResult { ADDED, ALREADY_PRESENT }

/**
 * Installs a public key into the remote `~/.ssh/authorized_keys`, idempotently and with the
 * permissions OpenSSH's StrictModes requires. The key line is validated first, so nothing but a
 * well-formed public key can reach the remote file.
 */
public object AuthorizedKeys {
    internal const val ADDED_MARKER = "BRIDLE_KEY_ADDED"
    internal const val PRESENT_MARKER = "BRIDLE_KEY_PRESENT"

    /** POSIX `sh` script that appends [authorizedKeyLine] unless already present. */
    public fun installScript(authorizedKeyLine: String): String {
        val line = authorizedKeyLine.trim()
        require('\n' !in line && '\r' !in line) { "Key must be a single line" }
        OpenSshKeys.parseAuthorizedKeyLine(line) // throws if malformed
        val (type, data) = line.split(Regex("\\s+")).let { it[0] to it[1] }
        val q = ShellQuote.quote(line)
        // Presence is decided on key type + data, so the same key with another comment (or with
        // options such as from="…") is not added twice. Commented-out lines don't count: sshd ignores
        // them, so a key the user disabled must be added again.
        val awkProgram =
            "/^[[:space:]]*#/ { next } " +
                "{ for (i = 1; i < NF; i++) if (${'$'}i == t && ${'$'}(i + 1) == k) found = 1 } " +
                "END { exit !found }"
        val present =
            "awk -v t=${ShellQuote.quote(type)} -v k=${ShellQuote.quote(data)} " +
                "${ShellQuote.quote(awkProgram)} \"${'$'}f\""
        return """
            |set -e
            |umask 077
            |d="${'$'}HOME/.ssh"
            |f="${'$'}d/authorized_keys"
            |mkdir -p "${'$'}d"
            |chmod 700 "${'$'}d"
            |touch "${'$'}f"
            |chmod 600 "${'$'}f"
            |if $present; then
            |  echo $PRESENT_MARKER
            |else
            |  if [ -s "${'$'}f" ] && [ "${'$'}(tail -c 1 "${'$'}f")" != "" ]; then printf '\n' >> "${'$'}f"; fi
            |  printf '%s\n' $q >> "${'$'}f"
            |  echo $ADDED_MARKER
            |fi
            |
            """.trimMargin()
    }

    /** Runs [installScript] over [connection]. */
    public suspend fun install(
        connection: SshConnection,
        authorizedKeyLine: String,
    ): KeyInstallResult {
        val result = connection.runScript(installScript(authorizedKeyLine))
        return when {
            !result.isSuccess -> throw RemoteCommandException("Could not install key", result)
            PRESENT_MARKER in result.stdout -> KeyInstallResult.ALREADY_PRESENT
            ADDED_MARKER in result.stdout -> KeyInstallResult.ADDED
            else -> throw RemoteCommandException("Unexpected output while installing key", result)
        }
    }
}

/** A remote command failed; [result] holds its exit status and output for diagnostics. */
public class RemoteCommandException(
    message: String,
    public val result: ExecResult,
) : Exception("$message (exit ${result.exitStatus}): ${result.stderr.take(MAX_ERROR_CHARS).trim()}") {
    private companion object {
        const val MAX_ERROR_CHARS = 500
    }
}
