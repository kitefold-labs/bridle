package com.kitefoldlabs.bridle.core.session

import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.ssh.ConnectTarget
import com.kitefoldlabs.bridle.core.ssh.Credential

/** Supplies credentials for a host (Keystore signer, decrypted imported key, or a password prompt). */
public fun interface CredentialProvider {
    public suspend fun credentialsFor(host: Host): List<Credential>
}

/** Raised when a host's ProxyJump chain refers to itself or a missing host. */
public class InvalidJumpChainException(
    message: String,
) : Exception(message)

/** Turns a [Host] (and its ProxyJump chain) into a [ConnectTarget]. */
public class TargetResolver(
    private val hostById: suspend (EntityId) -> Host?,
    private val credentials: CredentialProvider,
) {
    public suspend fun resolve(host: Host): ConnectTarget = resolve(host, visited = emptyList())

    private suspend fun resolve(
        host: Host,
        visited: List<EntityId>,
    ): ConnectTarget {
        val validation = host.validate()
        require(validation is Validation.Valid) { "Host '${host.label}' is invalid: $validation" }
        if (host.id in visited) invalidChain("ProxyJump loop through '${host.label}'")
        if (visited.size >= MAX_HOPS) invalidChain("ProxyJump chain is longer than $MAX_HOPS hops")
        val jump =
            host.jumpHostId?.let { id ->
                val next = hostById(id) ?: invalidChain("Jump host of '${host.label}' was deleted")
                resolve(next, visited + host.id)
            }
        return ConnectTarget(
            hostname = host.hostname.removeSurrounding("[", "]"),
            port = host.port,
            username = host.username,
            credentials = credentials.credentialsFor(host),
            jump = jump,
        )
    }

    private fun invalidChain(message: String): Nothing = throw InvalidJumpChainException(message)

    private companion object {
        const val MAX_HOPS = 8
    }
}
