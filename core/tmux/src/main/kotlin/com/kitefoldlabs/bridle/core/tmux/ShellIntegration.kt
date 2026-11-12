package com.kitefoldlabs.bridle.core.tmux

import com.kitefoldlabs.bridle.core.ssh.ShellQuote

/** A shell Bridle has an integration script for. */
public enum class IntegratedShell(
    public val id: String,
) {
    BASH("bash"),
    ZSH("zsh"),
    ;

    public companion object {
        public fun fromId(id: String): IntegratedShell? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The shell integration scripts, which mark prompts (OSC 133) and report the working directory
 * (OSC 7) for command-line suggestions, and the host scripts that install and remove them.
 * Installing goes through `bridle-helper install-shell`, which edits `~/.bashrc` / `~/.zshrc`.
 */
public object ShellIntegration {
    internal const val INSTALLED_MARKER = "BRIDLE_SHELL_INSTALLED"
    internal const val REMOVED_MARKER = "BRIDLE_SHELL_REMOVED"

    /** The first line of the block `install-shell` adds to an rc file. */
    internal const val BLOCK_BEGIN = "# >>> bridle shell integration >>>"

    /** Where [shell]'s script is installed (`~` is the remote home directory). */
    public fun path(shell: IntegratedShell): String = "${HostHelper.SHARE_DIR}/shell-integration.${shell.id}"

    private val scripts: Map<IntegratedShell, String> by lazy {
        IntegratedShell.entries.associateWith { shell -> HostHelper.resource("shell-integration.${shell.id}") }
    }

    /** The script for [shell] bundled with this build. */
    public fun script(shell: IntegratedShell): String = scripts.getValue(shell)

    /** SHA-256 of [script], shown to the user before installing and verified after upload. */
    public fun sha256(shell: IntegratedShell): String = HostHelper.sha256Hex(script(shell).toByteArray(Charsets.UTF_8))

    /**
     * Uploads the helper and both scripts (each checksum-verified), then runs `install-shell` for
     * [shells], or, when empty, for each rc file that exists plus the login shell's. Re-uploading
     * the helper makes sure it knows `install-shell`; its Claude Code hooks are left as they are.
     */
    public fun installScript(shells: Set<IntegratedShell> = emptySet()): String =
        buildString {
            appendLine("set -e")
            appendLine("umask 077")
            appendLine("mkdir -p ${HostHelper.SHARE_DIR}")
            with(HostHelper) {
                appendUpload(HostHelper.script, HostHelper.INSTALL_PATH, executable = true)
                for (shell in IntegratedShell.entries) appendUpload(script(shell), path(shell), executable = false)
            }
            val args = shells.sorted().joinToString("") { " ${it.id}" }
            appendLine("${ShellQuote.quotePath(HostHelper.INSTALL_PATH)} install-shell$args")
            appendLine("echo $INSTALLED_MARKER")
        }

    /** Removes the block from the rc files and deletes the scripts; fails if a block is damaged. */
    public fun uninstallScript(): String {
        val helper = ShellQuote.quotePath(HostHelper.INSTALL_PATH)
        val files = IntegratedShell.entries.joinToString(" ") { ShellQuote.quotePath(path(it)) }
        return buildString {
            appendLine("set -e")
            appendLine("if [ -x $helper ]; then $helper uninstall-shell; else rm -f $files; fi")
            appendLine("echo $REMOVED_MARKER")
        }
    }

    /**
     * A probe line, `shell=` followed by the ids of the shells whose rc file has the block and
     * whose script is installed, e.g. `shell= bash zsh`. Parsed by [parseProbeValue].
     */
    internal fun probeLine(): String {
        val checks =
            IntegratedShell.entries.joinToString(" ") { shell ->
                val rc =
                    when (shell) {
                        IntegratedShell.BASH -> "\"\$HOME/.bashrc\""
                        IntegratedShell.ZSH -> "\"\${ZDOTDIR:-\$HOME}/.zshrc\""
                    }
                "if [ -r ${ShellQuote.quotePath(path(shell))} ] && grep -qxF ${ShellQuote.quote(BLOCK_BEGIN)} $rc " +
                    "2>/dev/null; then s=\"\$s ${shell.id}\"; fi;"
            }
        return "s=; $checks printf 'shell=%s\\n' \"\$s\""
    }

    internal fun parseProbeValue(value: String?): Set<IntegratedShell> =
        value
            .orEmpty()
            .split(' ')
            .mapNotNull(IntegratedShell::fromId)
            .toSet()
}
