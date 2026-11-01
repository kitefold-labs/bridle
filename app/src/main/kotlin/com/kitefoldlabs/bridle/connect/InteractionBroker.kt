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
    )

    private val lock = Any()
    private val current = MutableStateFlow<Pending?>(null)

    private val mutablePending = MutableStateFlow<Interaction?>(null)

    /** The question the UI should show now, if any. */
    val pending: StateFlow<Interaction?> = mutablePending.asStateFlow()

    override fun confirmNewHost(prompt: HostKeyPrompt): Boolean =
        ask(Interaction.TrustHost(prompt)) as? Boolean ?: false

    /** Asks for a password; null if the user cancelled or didn't answer in time. */
    fun requestPassword(
        hostLabel: String,
        username: String,
    ): CharArray? = ask(Interaction.Password(hostLabel, username)) as? CharArray

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

    private fun ask(interaction: Interaction): Any? =
        synchronized(lock) {
            val pending = Pending(interaction, CompletableDeferred())
            current.value = pending
            mutablePending.value = interaction
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
