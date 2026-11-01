package com.kitefoldlabs.bridle.feature.hosts

import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.model.Validators

/** Editor fields that can show an inline error. */
enum class HostField { LABEL, HOSTNAME, PORT, USERNAME, KEEPALIVE, TMUX_SESSION, ENV }

/**
 * The host editor's text, exactly as typed. [errors] applies the same [Validators] rules as
 * [Host.validate], field by field, so every problem can be shown next to its field.
 */
data class HostForm(
    val label: String = "",
    val hostname: String = "",
    val port: String = Host.DEFAULT_SSH_PORT.toString(),
    val username: String = "",
    val identityId: EntityId? = null,
    val jumpHostId: EntityId? = null,
    val groupId: EntityId? = null,
    /** Comma-separated. */
    val tags: String = "",
    val color: Int? = null,
    val favorite: Boolean = false,
    val keepAlive: String = Host.DEFAULT_KEEPALIVE_SECONDS.toString(),
    val tmuxSession: String = "",
    val startupCommand: String = "",
    /** One `NAME=value` per line. */
    val env: String = "",
) {
    /** Every invalid field and why; empty when the form can be saved. */
    fun errors(): Map<HostField, Validation.Reason> =
        buildMap {
            flag(HostField.LABEL, Validators.label(label.trim()))
            flag(HostField.HOSTNAME, Validators.hostname(hostname.trim()))
            flag(HostField.PORT, port.trim().toIntOrNull()?.let(Validators::port) ?: OUT_OF_RANGE)
            flag(HostField.USERNAME, Validators.username(username.trim()))
            val keepAliveValid = keepAlive.trim().toIntOrNull() in 0..Host.MAX_KEEPALIVE_SECONDS
            flag(HostField.KEEPALIVE, if (keepAliveValid) Validation.Valid else OUT_OF_RANGE)
            tmuxSession.trim().takeIf { it.isNotEmpty() }?.let {
                flag(
                    HostField.TMUX_SESSION,
                    Validators.sessionName(it),
                )
            }
            if (parseEnv(env) == null) put(HostField.ENV, Validation.Reason.ILLEGAL_CHARACTERS)
        }

    /**
     * The host this form describes, keeping [base]'s id and connection history. Only call it
     * when [errors] is empty.
     */
    fun toHost(base: Host?): Host =
        (base ?: Host(label = "", hostname = "", username = "")).copy(
            label = label.trim(),
            hostname = hostname.trim(),
            port = port.trim().toInt(),
            username = username.trim(),
            identityId = identityId,
            jumpHostId = jumpHostId,
            groupId = groupId,
            tags = parseTags(tags),
            color = color,
            favorite = favorite,
            keepAliveSeconds = keepAlive.trim().toInt(),
            defaultTmuxSession = tmuxSession.trim().ifEmpty { null },
            startupCommand = startupCommand.trim().ifEmpty { null },
            env = parseEnv(env).orEmpty(),
        )

    companion object {
        private val OUT_OF_RANGE = Validation.Invalid("form", Validation.Reason.OUT_OF_RANGE)

        /** The form for editing [host]. */
        fun of(host: Host): HostForm =
            HostForm(
                label = host.label,
                hostname = host.hostname,
                port = host.port.toString(),
                username = host.username,
                identityId = host.identityId,
                jumpHostId = host.jumpHostId,
                groupId = host.groupId,
                tags = host.tags.sorted().joinToString(", "),
                color = host.color,
                favorite = host.favorite,
                keepAlive = host.keepAliveSeconds.toString(),
                tmuxSession = host.defaultTmuxSession.orEmpty(),
                startupCommand = host.startupCommand.orEmpty(),
                env = host.env.entries.joinToString("\n") { "${it.key}=${it.value}" },
            )

        /** Comma-separated tags, trimmed, without blanks. */
        fun parseTags(text: String): Set<String> =
            text
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

        /** `NAME=value` lines (blank lines ignored); null if a line has no `=` or an invalid name. */
        fun parseEnv(text: String): Map<String, String>? {
            val result = linkedMapOf<String, String>()
            for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                val name = line.substringBefore('=', missingDelimiterValue = "").trim()
                if (Validators.envName(name) != Validation.Valid) return null
                result[name] = line.substringAfter('=')
            }
            return result
        }

        private fun MutableMap<HostField, Validation.Reason>.flag(
            field: HostField,
            validation: Validation,
        ) {
            if (validation is Validation.Invalid) put(field, validation.reason)
        }
    }
}
