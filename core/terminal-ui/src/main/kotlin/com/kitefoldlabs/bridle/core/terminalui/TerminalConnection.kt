package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.session.TerminalSession
import com.kitefoldlabs.bridle.core.session.TerminalState
import kotlinx.coroutines.flow.StateFlow

/**
 * The remote side of a terminal screen: what [TerminalScreenState] needs from a
 * [TerminalSession]. A narrow interface so the UI can be tested without SSH.
 */
public interface TerminalConnection {
    public val state: StateFlow<TerminalState>

    /** Sends keystroke or paste bytes to the remote side. */
    public fun send(bytes: ByteArray)

    /** Resizes the remote PTY. */
    public suspend fun resize(
        columns: Int,
        rows: Int,
    )
}

/** This session as a [TerminalConnection]. */
public fun TerminalSession.asTerminalConnection(): TerminalConnection = SessionConnection(this)

/**
 * Routes the bytes this engine generates (query replies, its own keyboard input) to
 * [connection]. Do this where the session and engine are created (the service), not in the UI:
 * tmux and Claude Code query the terminal while no screen is showing.
 */
public fun TerminalEngine.sendRemoteInputTo(connection: TerminalConnection) {
    onRemoteInput = connection::send
}

private class SessionConnection(
    private val session: TerminalSession,
) : TerminalConnection {
    override val state: StateFlow<TerminalState> get() = session.state

    override fun send(bytes: ByteArray) = session.send(bytes)

    override suspend fun resize(
        columns: Int,
        rows: Int,
    ) = session.resize(columns, rows)
}
