package com.kitefoldlabs.bridle.core.model

/** Result of validating user input. Each [Invalid] carries a stable, localizable [reason]. */
public sealed interface Validation {
    public data object Valid : Validation

    public data class Invalid(
        val field: String,
        val reason: Reason,
    ) : Validation

    public enum class Reason {
        BLANK,
        TOO_LONG,
        ILLEGAL_CHARACTERS,
        OUT_OF_RANGE,
    }
}

/**
 * Input rules shared by the UI, importers and the SSH/tmux layers. Everything that is later
 * interpolated into a remote command is validated here *and* shell-quoted at the point of use.
 */
public object Validators {
    public const val MAX_LABEL: Int = 64
    public const val MAX_HOSTNAME: Int = 253
    public const val MAX_USERNAME: Int = 32
    public const val MAX_SESSION_NAME: Int = 64
    public const val MIN_PORT: Int = 1
    public const val MAX_PORT: Int = 65_535

    private val hostnameLabel = Regex("^(?!-)[A-Za-z0-9-]{1,63}(?<!-)$")
    private val ipv6 = Regex("^[0-9A-Fa-f:.]+(%[A-Za-z0-9_.-]+)?$")
    private val username = Regex("^[A-Za-z0-9._][A-Za-z0-9._-]*\\$?$")
    private val sessionName = Regex("^[A-Za-z0-9_-]+$")
    private val envName = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    public fun label(value: String): Validation =
        when {
            value.isBlank() -> invalid("label", Validation.Reason.BLANK)
            value.length > MAX_LABEL -> invalid("label", Validation.Reason.TOO_LONG)
            value.any { it.isISOControl() } -> invalid("label", Validation.Reason.ILLEGAL_CHARACTERS)
            else -> Validation.Valid
        }

    /** Accepts DNS names (including Tailscale MagicDNS), IPv4 and IPv6 literals (bracketed or not). */
    public fun hostname(value: String): Validation {
        val host = value.removeSurrounding("[", "]")
        return when {
            host.isBlank() -> invalid("hostname", Validation.Reason.BLANK)
            host.length > MAX_HOSTNAME -> invalid("hostname", Validation.Reason.TOO_LONG)
            host.contains(':') && ipv6.matches(host) -> Validation.Valid
            host.trimEnd('.').split('.').all { hostnameLabel.matches(it) } -> Validation.Valid
            else -> invalid("hostname", Validation.Reason.ILLEGAL_CHARACTERS)
        }
    }

    public fun port(value: Int): Validation =
        if (value in MIN_PORT..MAX_PORT) Validation.Valid else invalid("port", Validation.Reason.OUT_OF_RANGE)

    public fun username(value: String): Validation =
        when {
            value.isBlank() -> invalid("username", Validation.Reason.BLANK)
            value.length > MAX_USERNAME -> invalid("username", Validation.Reason.TOO_LONG)
            !username.matches(value) -> invalid("username", Validation.Reason.ILLEGAL_CHARACTERS)
            else -> Validation.Valid
        }

    /**
     * tmux session names: a conservative subset that never needs quoting. tmux itself rewrites '.' and ':'
     * (they are target separators), so they are rejected rather than silently renamed.
     */
    public fun sessionName(value: String): Validation =
        when {
            value.isBlank() -> invalid("sessionName", Validation.Reason.BLANK)
            value.length > MAX_SESSION_NAME -> invalid("sessionName", Validation.Reason.TOO_LONG)
            !sessionName.matches(value) || value.startsWith("-") ->
                invalid("sessionName", Validation.Reason.ILLEGAL_CHARACTERS)
            else -> Validation.Valid
        }

    public fun envName(value: String): Validation =
        when {
            value.isBlank() -> invalid("env", Validation.Reason.BLANK)
            !envName.matches(value) -> invalid("env", Validation.Reason.ILLEGAL_CHARACTERS)
            else -> Validation.Valid
        }

    /**
     * A remote working directory (`~/src/api`, `/srv/app`). Bridle attaches to tmux with a command
     * that must parse the same in sh, bash, zsh and fish, so backslashes and control characters
     * are refused; anything else is shell-quoted where it is used.
     */
    public fun workingDirectory(value: String): Validation =
        when {
            value.isBlank() -> invalid("workingDir", Validation.Reason.BLANK)
            '\\' in value || value.any { it.isISOControl() } ->
                invalid("workingDir", Validation.Reason.ILLEGAL_CHARACTERS)
            else -> Validation.Valid
        }

    /** Returns the first failure among [checks], or [Validation.Valid]. */
    public fun all(vararg checks: Validation): Validation = all(checks.asList())

    /** Returns the first failure among [checks], or [Validation.Valid]. */
    public fun all(checks: List<Validation>): Validation =
        checks.firstOrNull { it is Validation.Invalid } ?: Validation.Valid

    private fun invalid(
        field: String,
        reason: Validation.Reason,
    ) = Validation.Invalid(field, reason)
}
