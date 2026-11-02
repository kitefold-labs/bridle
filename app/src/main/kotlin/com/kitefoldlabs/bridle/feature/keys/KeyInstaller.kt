package com.kitefoldlabs.bridle.feature.keys

import com.kitefoldlabs.bridle.connect.UnknownHostException
import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.session.HostConnector
import com.kitefoldlabs.bridle.core.ssh.AuthenticationFailedException
import com.kitefoldlabs.bridle.core.ssh.AuthorizedKeys
import com.kitefoldlabs.bridle.core.ssh.ConnectionFailedException
import com.kitefoldlabs.bridle.core.ssh.HostKeyChangedException
import com.kitefoldlabs.bridle.core.ssh.HostKeyRejectedException
import com.kitefoldlabs.bridle.core.ssh.KeyInstallResult
import com.kitefoldlabs.bridle.core.ssh.RemoteCommandException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** How installing a key on a host ended. */
sealed interface InstallOutcome {
    data class Installed(
        val result: KeyInstallResult,
    ) : InstallOutcome

    data class Failed(
        val reason: InstallFailure,
    ) : InstallOutcome
}

enum class InstallFailure {
    /** The password (and any key the host already had) was rejected, or the prompt was cancelled. */
    AUTH_FAILED,

    /** The host couldn't be reached. */
    UNREACHABLE,

    /** The user didn't trust the host's key. */
    HOST_NOT_TRUSTED,

    /** The host's key changed; the full-screen warning is showing. */
    HOST_KEY_CHANGED,

    /** Writing `~/.ssh/authorized_keys` failed on the host. */
    REMOTE_FAILED,

    /** The host was deleted meanwhile. */
    HOST_MISSING,

    /** Anything else (e.g. a broken ProxyJump chain). */
    OTHER,
}

/**
 * "Install on host": logs in once (with the host's current key, else the password the user types
 * into the password prompt), appends the public key to `~/.ssh/authorized_keys`, and makes the
 * key the host's identity so later connections use it. The password is never stored.
 */
@Singleton
class KeyInstaller
    @Inject
    constructor(
        private val connector: HostConnector,
        private val hosts: HostRepository,
        private val alerts: HostKeyAlerts,
    ) {
        suspend fun install(
            identity: Identity,
            hostId: EntityId,
        ): InstallOutcome =
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        connector.connect(hostId).use { AuthorizedKeys.install(it, identity.publicKeyOpenSsh) }
                    }
                hosts.get(hostId)?.let { hosts.upsert(it.copy(identityId = identity.id)) }
                InstallOutcome.Installed(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostKeyChangedException) {
                alerts.report(e)
                InstallOutcome.Failed(InstallFailure.HOST_KEY_CHANGED)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                InstallOutcome.Failed(failureOf(e))
            }

        private fun failureOf(e: Exception): InstallFailure =
            when (e) {
                is AuthenticationFailedException -> InstallFailure.AUTH_FAILED
                is ConnectionFailedException -> InstallFailure.UNREACHABLE
                is HostKeyRejectedException -> InstallFailure.HOST_NOT_TRUSTED
                is RemoteCommandException -> InstallFailure.REMOTE_FAILED
                is UnknownHostException -> InstallFailure.HOST_MISSING
                else -> InstallFailure.OTHER
            }
    }
