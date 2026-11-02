package com.kitefoldlabs.bridle.connect

import com.kitefoldlabs.bridle.core.ssh.HostKeyPrompt
import com.kitefoldlabs.bridle.core.ssh.HostKeyPrompter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Something the connection layer needs the user to decide. */
sealed interface Interaction {
    /** First connection to a host: trust this key? */
    data class TrustHost(
        val prompt: HostKeyPrompt,
    ) : Interaction

    /** The server asked for a password (key auth failed or the host has no key). */
    data class Password(
        val hostLabel: String,
        val username: String,
    ) : Interaction
}

/**
 * Bridges blocking questions from SSH I/O threads to the UI: the I/O side waits (with a timeout,
 * because sshj's key exchange won't wait forever) while a dialog shows [pending]. One question
 * at a time; concurrent askers queue on the lock.
 *
 * A connection attempt asks through its own [Questions], which it withdraws when it is cancelled
 * or ends: the dialog then closes at once instead of waiting out the timeout for a connection
 * nobody wants any more.
 */
@Singleton
class InteractionBroker(
    private val timeout: Duration,
) : HostKeyPrompter {
    @Inject
    constructor() : this(DEFAULT_TIMEOUT)

    private class Pending(
        val interaction: Interaction,
        val answer: CompletableDeferred<Any?>,
        val owner: Questions,
    )

    /** The questions of one connection attempt; see [withdraw]. */
    inner class Questions internal constructor() : HostKeyPrompter {
        @Volatile private var withdrawn = false

        internal val isWithdrawn: Boolean get() = withdrawn

        override fun confirmNewHost(prompt: HostKeyPrompt): Boolean =
            ask(Interaction.TrustHost(prompt), this) as? Boolean ?: false

        /** Asks for a password; null if the user cancelled, didn't answer in time, or [withdraw] ran. */
        fun requestPassword(
            hostLabel: String,
            username: String,
        ): CharArray? = ask(Interaction.Password(hostLabel, username), this) as? CharArray

        /**
         * The attempt is over: closes this attempt's question if it is showing (as unanswered) and
         * answers any it asks later at once, without showing them. Safe from any thread.
         */
        fun withdraw() {
            withdrawn = true
            // After setting the flag: an ask() publishing its question meanwhile sees one or the other.
            current.value
                ?.takeIf { it.owner === this }
                ?.answer
                ?.complete(null)
        }
    }

    private val lock = Any()
    private val current = MutableStateFlow<Pending?>(null)

    private val mutablePending = MutableStateFlow<Interaction?>(null)

    /** The question the UI should show now, if any. */
    val pending: StateFlow<Interaction?> = mutablePending.asStateFlow()

    /** A new attempt's questions. */
    fun questions(): Questions = Questions()

    override fun confirmNewHost(prompt: HostKeyPrompt): Boolean = questions().confirmNewHost(prompt)

    /** Asks for a password; null if the user cancelled or didn't answer in time. */
    fun requestPassword(
        hostLabel: String,
        username: String,
    ): CharArray? = questions().requestPassword(hostLabel, username)

    /**
     * The UI's answer to [interaction] (the instance it showed from [pending]): a Boolean for
     * [Interaction.TrustHost], a CharArray (or null) for passwords.
     *
     * An answer to a question that is no longer pending (it timed out, or a newer question
     * replaced it while the dialog was up) is dropped, so a "Trust" tapped for one host can never
     * trust another; a dropped password is zeroed.
     */
    fun answer(
        interaction: Interaction,
        value: Any?,
    ) {
        val pending = current.value
        val accepted = pending != null && pending.interaction === interaction && pending.answer.complete(value)
        if (!accepted) (value as? CharArray)?.fill('\u0000')
    }

    private fun ask(
        interaction: Interaction,
        owner: Questions,
    ): Any? =
        synchronized(lock) {
            val pending = Pending(interaction, CompletableDeferred(), owner)
            current.value = pending
            // Withdrawn already (say, while queued on the lock): answer without showing anything.
            if (owner.isWithdrawn) pending.answer.complete(null) else mutablePending.value = interaction
            try {
                runBlocking {
                    // On timeout, close the question; an answer that won the race still counts.
                    withTimeoutOrNull(timeout) { pending.answer.await() }
                        ?: pending.answer.also { it.complete(null) }.await()
                }
            } finally {
                current.value = null
                mutablePending.value = null
            }
        }

    companion object {
        /** Under sshj's 30 s key-exchange wait, so a timeout is reported as "not trusted". */
        val DEFAULT_TIMEOUT: Duration = 25.seconds
    }
}
