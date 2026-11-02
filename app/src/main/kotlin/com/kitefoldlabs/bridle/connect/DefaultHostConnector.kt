package com.kitefoldlabs.bridle.connect

import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.data.IdentityRepository
import com.kitefoldlabs.bridle.core.data.KnownHostRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.session.CredentialProvider
import com.kitefoldlabs.bridle.core.session.HostConnector
import com.kitefoldlabs.bridle.core.session.TargetResolver
import com.kitefoldlabs.bridle.core.ssh.ConnectOptions
import com.kitefoldlabs.bridle.core.ssh.Credential
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.core.ssh.SshEngine
import com.kitefoldlabs.bridle.core.ssh.SshKeySigner
import com.kitefoldlabs.bridle.core.ssh.TofuHostKeyVerifier
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** Produces a signer for an identity (Keystore key or decrypted imported key). */
fun interface SignerSource {
    /** Null if the key is unavailable (e.g. deleted from the Keystore). */
    suspend fun signer(identity: Identity): SshKeySigner?
}

/** Key first (if the host has one), then a password the user types only if the server asks. */
@Singleton
class HostCredentials
    @Inject
    constructor(
        private val identities: IdentityRepository,
        private val signers: SignerSource,
        private val broker: InteractionBroker,
    ) : CredentialProvider {
        override suspend fun credentialsFor(host: Host): List<Credential> = credentialsFor(host, broker.questions())

        /** Credentials whose password question belongs to one connection attempt's [questions]. */
        fun forAttempt(questions: InteractionBroker.Questions): CredentialProvider =
            CredentialProvider { credentialsFor(it, questions) }

        private suspend fun credentialsFor(
            host: Host,
            questions: InteractionBroker.Questions,
        ): List<Credential> {
            val key =
                host.identityId
                    ?.let { identities.get(it) }
                    ?.let { signers.signer(it) }
                    ?.let { Credential.Key(it) }
            val password =
                Credential.Password {
                    questions.requestPassword(host.label, host.username) ?: CharArray(0)
                }
            return listOfNotNull(key, password)
        }
    }

/** Raised when connecting to a host id that no longer exists. */
class UnknownHostException(
    hostId: EntityId,
) : IllegalArgumentException("Host $hostId no longer exists")

/** Connects to stored hosts: ProxyJump chain, credentials, TOFU host keys, keepalive. */
@Singleton
class DefaultHostConnector
    @Inject
    constructor(
        private val hosts: HostRepository,
        private val knownHosts: KnownHostRepository,
        private val credentials: HostCredentials,
        private val broker: InteractionBroker,
        private val engine: SshEngine,
        private val clock: () -> Long,
    ) : HostConnector {
        @Suppress("TooGenericExceptionCaught") // rethrown unless the attempt was cancelled
        override suspend fun connect(hostId: EntityId): SshConnection {
            val host = hosts.get(hostId) ?: throw UnknownHostException(hostId)
            val questions = broker.questions()
            return coroutineScope {
                // The SSH thread asking a question is blocked in it, so the attempt's cancellation
                // can't reach it; this closes the question (and its dialog) as soon as it happens.
                val withdrawOnCancel =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            questions.withdraw()
                        }
                    }
                try {
                    val target = TargetResolver({ hosts.get(it) }, credentials.forAttempt(questions)).resolve(host)
                    val connection =
                        engine.connect(
                            target,
                            TofuHostKeyVerifier(knownHosts, questions, clock),
                            ConnectOptions(keepAlive = host.keepAliveSeconds.seconds),
                        )
                    hosts.markConnected(hostId, clock())
                    connection
                } catch (e: Exception) {
                    // Withdrawing answers the question (no password, host not trusted), and the SSH
                    // thread can act on that before the cancellation reaches it: report the
                    // cancellation, not the authentication failure it caused.
                    ensureActive()
                    throw e
                } finally {
                    withdrawOnCancel.cancel() // the attempt is over either way: nothing more to ask
                }
            }
        }
    }
