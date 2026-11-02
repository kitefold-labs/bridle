package com.kitefoldlabs.bridle.core.session

import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.ssh.AuthenticationFailedException
import com.kitefoldlabs.bridle.core.ssh.ConnectionFailedException
import com.kitefoldlabs.bridle.core.ssh.Credential
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.HostKeyChangedException
import com.kitefoldlabs.bridle.core.ssh.ReconnectPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {
    private val policy = ReconnectPolicy(initial = 1.seconds, max = 4.seconds, maxAttempts = 4, random = Random(3))

    /** backgroundScope work isn't driven by advanceUntilIdle, so advance virtual time explicitly. */
    private fun TestScope.settle() {
        advanceTimeBy(10.minutes)
        runCurrent()
    }

    private fun TestScope.manager(connector: HostConnector) =
        ConnectionManager(connector, backgroundScope, policy, idleGrace = 60.seconds)

    @Test
    fun `leases share one connection and close after the grace period`() =
        runTest {
            val connector = ScriptedConnector()
            val m = manager(connector)
            val a = m.acquire(1)
            val b = m.acquire(1)
            val conn = a.awaitConnection()
            b.awaitConnection() shouldBe conn
            connector.attempts shouldBe listOf(1L)
            m.activeHosts() shouldBe setOf(1L)

            a.close()
            a.close() // idempotent
            b.close()
            advanceTimeBy(30.seconds)
            runCurrent()
            connector.made.single().closed shouldBe false
            // Re-acquiring within the grace period cancels the close.
            val c = m.acquire(1)
            advanceTimeBy(120.seconds)
            connector.made.single().closed shouldBe false
            c.close()
            advanceTimeBy(61.seconds)
            runCurrent()
            connector.made.single().closed shouldBe true
            m.state(1).value shouldBe HostConnectionState.Idle
            m.activeHosts() shouldBe emptySet()
        }

    @Test
    fun `transient failures back off then connect`() =
        runTest {
            val connector =
                ScriptedConnector(ConnectionFailedException("timeout"), ConnectionFailedException("refused"))
            val m = manager(connector)
            val lease = m.acquire(7)
            runCurrent()
            lease.state.value
                .shouldBeInstanceOf<HostConnectionState.Reconnecting>()
                .lastError shouldBe "timeout"
            settle()
            lease.state.value.shouldBeInstanceOf<HostConnectionState.Connected>()
            connector.attempts.size shouldBe 3
            lease.close()
        }

    @Test
    fun `host key and auth failures are not retried`() =
        runTest {
            val changed = HostKeyChangedException("h", 22, "ssh-ed25519", listOf("SHA256:a"), "SHA256:b")
            val m = manager(ScriptedConnector(changed, AuthenticationFailedException("denied")))
            val lease = m.acquire(1)
            shouldThrow<HostKeyChangedException> { lease.awaitConnection() }
            lease.state.value
                .shouldBeInstanceOf<HostConnectionState.Failed>()
                .retryable shouldBe false
            m.onNetworkChanged() // not retryable → stays failed
            runCurrent()
            lease.state.value.shouldBeInstanceOf<HostConnectionState.Failed>()
            m.reconnect(1) // explicit retry → auth failure, also final
            shouldThrow<AuthenticationFailedException> { lease.awaitConnection() }
            lease.close()
        }

    @Test
    fun `gives up after max attempts and network change retries`() =
        runTest {
            val fail = Array<Any>(4) { ConnectionFailedException("down") }
            val m = manager(ScriptedConnector(*fail))
            val lease = m.acquire(1)
            settle()
            val failed = lease.state.value.shouldBeInstanceOf<HostConnectionState.Failed>()
            failed.retryable shouldBe true
            failed.error.message shouldContain "down"
            m.onNetworkChanged()
            settle()
            lease.state.value.shouldBeInstanceOf<HostConnectionState.Connected>()
            lease.close()
        }

    @Test
    fun `dropped connection reconnects, and network change skips the wait`() =
        runTest {
            val connector = ScriptedConnector()
            val m = manager(connector)
            val lease = m.acquire(1)
            lease.awaitConnection()
            connector.made[0].drop()
            runCurrent()
            lease.state.value.shouldBeInstanceOf<HostConnectionState.Reconnecting>()
            m.onNetworkChanged()
            runCurrent()
            lease.state.value
                .shouldBeInstanceOf<HostConnectionState.Connected>()
                .connection shouldBe connector.made[1]
            m.closeAll()
            m.state(1).value shouldBe HostConnectionState.Idle
            m.reconnect(1) // unknown host: no-op
        }

    @Test
    fun `network change probes a connected host and reconnects it if the link is dead`() =
        runTest {
            val connector = ScriptedConnector()
            val m = manager(connector)
            val lease = m.acquire(1)
            val old = lease.awaitConnection() as FakeConnection
            old.onExec = { awaitCancellation() } // the TCP link died without a FIN

            m.onNetworkChanged()
            runCurrent()
            old.execs shouldBe listOf("true")
            lease.state.value.shouldBeInstanceOf<HostConnectionState.Connected>() // still probing
            advanceTimeBy(6.seconds)
            runCurrent()
            old.closed shouldBe true
            lease.state.value
                .shouldBeInstanceOf<HostConnectionState.Connected>()
                .connection shouldBe connector.made[1]

            // A probe that fails outright reconnects at once.
            connector.made[1].onExec = { throw IOException("broken pipe") }
            m.onNetworkChanged()
            runCurrent()
            lease.state.value
                .shouldBeInstanceOf<HostConnectionState.Connected>()
                .connection shouldBe connector.made[2]

            // One that answers, whatever the exit status, keeps the connection.
            connector.made[2].onExec = { ExecResult(1, "", "") }
            m.onNetworkChanged()
            settle()
            connector.attempts.size shouldBe 3
            lease.close()
        }

    @Test
    fun `a probe that times out leaves alone a host that reconnected or was released meanwhile`() =
        runTest {
            val connector = ScriptedConnector()
            val m = manager(connector)
            val lease = m.acquire(1)
            val first = lease.awaitConnection() as FakeConnection
            first.onExec = { awaitCancellation() }
            m.onNetworkChanged()
            runCurrent()
            first.drop() // noticed by the transport first: the manager reconnects on its own
            advanceTimeBy(2.seconds)
            runCurrent()
            val second = lease.awaitConnection() as FakeConnection
            second shouldBe connector.made[1]
            advanceTimeBy(10.seconds)
            runCurrent()
            connector.attempts.size shouldBe 2 // the stale probe didn't restart the new connection

            second.onExec = { awaitCancellation() }
            m.onNetworkChanged()
            runCurrent()
            lease.close()
            advanceTimeBy(10.seconds) // the probe times out within the idle grace period
            runCurrent()
            connector.attempts.size shouldBe 2
            second.closed shouldBe false
        }

    @Test
    fun `failed host is retried on next acquire and idle hosts ignore network changes`() =
        runTest {
            val m = manager(ScriptedConnector(AuthenticationFailedException("no")))
            val first = m.acquire(2)
            shouldThrow<AuthenticationFailedException> { first.awaitConnection() }
            val second = m.acquire(2)
            second.awaitConnection()
            first.close()
            second.close()
            advanceTimeBy(1.seconds)
            m.onNetworkChanged() // no leases → nothing happens
            m.reconnect(2) // no leases → nothing happens
            GaveUpException(null).message shouldBe "Could not reconnect"
        }

    @Test
    fun `a lease released after closeAll leaves the host's new connection alone`() =
        runTest {
            val connector = ScriptedConnector()
            val m = manager(connector)
            val stale = m.acquire(1)
            stale.awaitConnection()
            m.closeAll()
            val fresh = m.acquire(1)
            val conn = fresh.awaitConnection()
            stale.close() // must not schedule a close (or a map removal) for the new entry
            settle()
            (conn as FakeConnection).closed shouldBe false
            m.state(1).value.shouldBeInstanceOf<HostConnectionState.Connected>()
            m.acquire(1).awaitConnection() shouldBe conn // still shared, no second connection
            connector.made.size shouldBe 2
            m.activeHosts() shouldBe setOf(1L)
        }

    @Test
    fun `explicit reconnect closes the connection it replaces`() =
        runTest {
            val connector = ScriptedConnector()
            val m = manager(connector)
            val lease = m.acquire(1)
            val old = lease.awaitConnection() as FakeConnection
            m.reconnect(1)
            settle()
            old.closed shouldBe true
            lease.state.value
                .shouldBeInstanceOf<HostConnectionState.Connected>()
                .connection shouldBe connector.made[1]
            connector.made[1].closed shouldBe false
            m.closeAll()
        }

    @Test
    fun `waiting for a connection ends when everything is closed`() =
        runTest {
            val m = manager { awaitCancellation() }
            val lease = m.acquire(1)
            val waiting = async { runCatching { lease.awaitConnection() } }
            runCurrent()
            m.closeAll()
            waiting
                .await()
                .exceptionOrNull()
                .shouldBeInstanceOf<ConnectionClosedException>()
                .message shouldBe "Disconnected"
        }

    @Test
    fun `a superseded connect loop closes what it connected and never publishes it`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val late = FakeConnection()
            val current = FakeConnection()
            var calls = 0
            val m =
                manager {
                    if (calls++ == 0) {
                        // Like a blocking sshj connect that finishes after its coroutine was cancelled.
                        withContext(NonCancellable) { gate.await() }
                        late
                    } else {
                        current
                    }
                }
            val lease = m.acquire(1)
            runCurrent()
            m.reconnect(1) // cancels the first loop while it is still connecting
            lease.awaitConnection() shouldBe current
            gate.complete(Unit)
            runCurrent()
            late.closed shouldBe true
            lease.state.value
                .shouldBeInstanceOf<HostConnectionState.Connected>()
                .connection shouldBe current
            current.closed shouldBe false
            m.closeAll()
            current.closed shouldBe true
        }

    @Test
    fun `target resolver follows jump chains and rejects loops`() =
        runTest {
            val bastion = Host(id = 1, label = "bastion", hostname = "b.example.com", username = "ops")
            val gpu = Host(id = 2, label = "gpu", hostname = "[fd00::5]", port = 2222, username = "ml", jumpHostId = 1)
            val hosts = mutableMapOf(1L to bastion, 2L to gpu)
            val creds = CredentialProvider { listOf(Credential.Password { "x".toCharArray() }) }
            val resolver = TargetResolver({ hosts[it] }, creds)
            val target = resolver.resolve(gpu)
            target.hostname shouldBe "fd00::5"
            target.port shouldBe 2222
            target.jump!!.hostname shouldBe "b.example.com"

            hosts[1] = bastion.copy(jumpHostId = 2)
            shouldThrow<InvalidJumpChainException> { resolver.resolve(gpu) }.message shouldContain "loop"
            hosts.remove(1)
            shouldThrow<InvalidJumpChainException> { resolver.resolve(gpu) }.message shouldContain "deleted"
            shouldThrow<IllegalArgumentException> { resolver.resolve(gpu.copy(port = 0)) }

            val chain =
                (1L..10L).associateWith {
                    Host(id = it, label = "h$it", hostname = "h$it", username = "u", jumpHostId = it + 1)
                }
            val long = TargetResolver({ chain[it] ?: chain.getValue(1) }, creds)
            shouldThrow<InvalidJumpChainException> { long.resolve(chain.getValue(1)) }
        }
}
