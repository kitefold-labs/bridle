package com.kitefoldlabs.bridle.core.session

import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.ssh.ConnectionFailedException
import com.kitefoldlabs.bridle.core.ssh.Credential
import com.kitefoldlabs.bridle.core.ssh.ReconnectPolicy
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SessionEdgeCasesTest {
    @Test
    fun `manager state, active hosts and shutdown in every state`() =
        runTest {
            val connector = ScriptedConnector(FakeConnection(), ConnectionFailedException("x"))
            val m = ConnectionManager(connector, backgroundScope, ReconnectPolicy(), idleGrace = 30.seconds)
            val lease = m.acquire(1)
            lease.hostId shouldBe 1L
            val conn = lease.awaitConnection()
            m.state(1).value.shouldBeInstanceOf<HostConnectionState.Connected>()
            m.onNetworkChanged() // connected: nothing to do
            runCurrent()
            m
                .state(1)
                .value
                .shouldBeInstanceOf<HostConnectionState.Connected>()
                .connection shouldBe conn

            lease.close()
            runCurrent()
            m.activeHosts() shouldBe emptySet() // in grace period: tracked but not active
            m.state(1).value.shouldBeInstanceOf<HostConnectionState.Connected>()
            m.closeAll() // while connected → connection closed
            (conn as FakeConnection).closed shouldBe true

            val second = m.acquire(2) // next connect fails → reconnecting
            runCurrent()
            second.state.value
                .shouldBeInstanceOf<HostConnectionState.Reconnecting>()
                .attempt shouldBe 1
            m.closeAll() // while reconnecting
            m.state(2).value shouldBe HostConnectionState.Idle
            advanceTimeBy(5.minutes)
        }

    @Test
    fun `resolver handles a direct host`() =
        runTest {
            val host = Host(id = 3, label = "box", hostname = "box.ts.net", username = "me")
            val target =
                TargetResolver({
                    null
                }, { emptyList<Credential>().ifEmpty { listOf(Credential.Password { CharArray(0) }) } }).resolve(host)
            target.jump shouldBe null
            target.hostname shouldBe "box.ts.net"
        }

    @Test
    fun `terminal session lifecycle corners`() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val gate = FakeConnection()
                val m = ConnectionManager(ScriptedConnector(gate), scope)
                val unstarted = TerminalSession(m.acquire(9), AttachSpec.Shell, { _, _ -> }, scope)
                unstarted.resize(80, 24) // no channel yet: remembered for later
                unstarted.close()
                unstarted.close() // already ended
                unstarted.state.value.shouldBeInstanceOf<TerminalState.Ended>()

                // Keys typed while no shell is open are dropped, not queued forever.
                val lease =
                    object : ConnectionLease {
                        override val hostId = 1L
                        override val state = MutableStateFlow<HostConnectionState>(HostConnectionState.Connecting)

                        override suspend fun awaitConnection() = gate

                        override fun close() = Unit
                    }
                val waiting = TerminalSession(lease, AttachSpec.Tmux("t"), { _, _ -> }, scope)
                waiting.start()
                waiting.send("lost".toByteArray())
                delay(50.milliseconds)
                lease.state.value = HostConnectionState.Connected(gate)
                withTimeout(5.seconds) { waiting.state.first { it == TerminalState.Open } }
                gate.shells.single().writtenText() shouldBe ""
                gate.shells.single().open = false
                waiting.send("fails quietly".toByteArray())
                delay(50.milliseconds)
                lease.state.value = HostConnectionState.Reconnecting(1, 1.seconds, null)
                withTimeout(5.seconds) { waiting.state.first { it == TerminalState.Reattaching } }
                waiting.close()
            } finally {
                scope.cancel()
            }
        }
}
