package com.kitefoldlabs.bridle.core.ssh

import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Validators

/** A host entry imported from an OpenSSH client config. */
public data class SshConfigEntry(
    val alias: String,
    val hostname: String,
    val user: String?,
    val port: Int,
    /** ProxyJump hops in order (first hop first); each is an alias or `user@host:port`. */
    val proxyJump: List<String>,
    /** IdentityFile paths; private keys are never read from these, the user picks or imports a key. */
    val identityFiles: List<String>,
)

public data class SshConfigImport(
    val entries: List<SshConfigEntry>,
    /** Human-readable notes about lines that were skipped (Include, Match, wildcards, …). */
    val warnings: List<String>,
)

/**
 * Parses `~/.ssh/config` text into importable hosts.
 *
 * Supports `Host` blocks with concrete aliases, `HostName`, `User`, `Port`, `ProxyJump`,
 * `IdentityFile`, and `Host *` defaults (applied to entries that don't override them, matching
 * OpenSSH's first-value-wins rule). `Match`, `Include`, `ProxyCommand` and wildcard patterns other
 * than `*` are reported as warnings rather than guessed at.
 */
public object SshConfigParser {
    private const val MAX_INPUT_CHARS = 1_000_000

    public fun parse(text: String): SshConfigImport {
        require(text.length <= MAX_INPUT_CHARS) { "ssh config is too large" }
        val warnings = mutableListOf<String>()
        val blocks = mutableListOf<Block>()
        var current: Block? = null
        var inMatch = false

        text.lineSequence().forEachIndexed { index, rawLine ->
            val (key, value) = splitDirective(rawLine) ?: return@forEachIndexed
            val lineNo = index + 1
            when (key) {
                "host" -> {
                    inMatch = false
                    current = Block(value.split(Regex("\\s+")).filter { it.isNotEmpty() }).also { blocks += it }
                }
                "match" -> {
                    inMatch = true
                    warnings += "Line $lineNo: Match blocks are not supported and were skipped"
                }
                "include" -> warnings += "Line $lineNo: Include is not supported; paste included files separately"
                else ->
                    when {
                        inMatch -> Unit
                        current == null -> warnings += "Line $lineNo: '$key' outside a Host block was ignored"
                        else -> current.set(key, value, lineNo, warnings)
                    }
            }
        }
        return SshConfigImport(buildEntries(blocks, warnings), warnings)
    }

    private fun buildEntries(
        blocks: List<Block>,
        warnings: MutableList<String>,
    ): List<SshConfigEntry> {
        val defaults = blocks.filter { "*" in it.patterns }
        val entries = linkedMapOf<String, SshConfigEntry>()
        for (block in blocks) {
            for (alias in block.patterns) {
                when {
                    alias == "*" -> Unit
                    alias.startsWith("!") || alias.any { it == '*' || it == '?' } ->
                        warnings += "Host pattern '$alias' is a wildcard and was not imported"
                    alias !in entries -> {
                        // OpenSSH: the first value obtained for each option wins, in file order.
                        val chain = blocks.filter { alias in it.patterns || it in defaults }

                        fun first(key: String) = chain.firstNotNullOfOrNull { it.options[key] }
                        entries[alias] =
                            SshConfigEntry(
                                alias = alias,
                                hostname = first("hostname") ?: alias,
                                user = first("user"),
                                port = first("port")?.toIntOrNull() ?: Host.DEFAULT_SSH_PORT,
                                proxyJump =
                                    first("proxyjump")
                                        ?.takeUnless { it.equals("none", ignoreCase = true) }
                                        ?.split(',')
                                        ?.map { it.trim() }
                                        ?.filter { it.isNotEmpty() }
                                        .orEmpty(),
                                identityFiles = chain.flatMap { it.identityFiles },
                            )
                    }
                }
            }
        }
        return entries.values.toList()
    }

    /** Splits `Key value`, `Key=value` or `Key = "value"`; returns null for blanks/comments. */
    internal fun splitDirective(rawLine: String): Pair<String, String>? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        val match = Regex("^([A-Za-z]+)\\s*(?:=|\\s)\\s*(.*)$").find(line) ?: return null
        val value = match.groupValues[2].trim().removeSurrounding("\"")
        return match.groupValues[1].lowercase() to value
    }

    private class Block(
        val patterns: List<String>,
    ) {
        val options = mutableMapOf<String, String>()
        val identityFiles = mutableListOf<String>()

        fun set(
            key: String,
            value: String,
            lineNo: Int,
            warnings: MutableList<String>,
        ) {
            when (key) {
                "identityfile" -> identityFiles += value
                "port" ->
                    if (value.toIntOrNull() in Validators.MIN_PORT..Validators.MAX_PORT) {
                        options.putIfAbsent(key, value)
                    } else {
                        warnings += "Line $lineNo: invalid Port '$value' ignored"
                    }
                "hostname", "user", "proxyjump" -> options.putIfAbsent(key, value)
                "proxycommand" -> warnings += "Line $lineNo: ProxyCommand is not supported; use ProxyJump"
                else -> Unit // Other options (Ciphers, ForwardAgent, …) don't map to Bridle settings.
            }
        }
    }
}
