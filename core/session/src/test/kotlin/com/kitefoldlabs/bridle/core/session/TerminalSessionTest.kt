package com.kitefoldlabs.bridle.core.session

import com.kitefoldlabs.bridle.core.ssh.PtySpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TerminalSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screen = ByteArrayOutputStream()
    private val sink = TerminalSink { buffer, length -> synchronized(screen) { screen.write(buffer, 0, length) } }

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun screenText() = synchronized(screen) { screen.toString(Charsets.UTF_8) }

    private suspend fun eventually(condition: () -> Boolean) =
        withTimeout(5.seconds) {
            while (!condition()) delay(10.milliseconds)
        }

    private suspend fun setUp(
        attach: AttachSpec,
        connector: ScriptedConnector = ScriptedConnector(),
    ): Triple<TerminalSession, ScriptedConnector, ConnectionManager> {
        val manager = ConnectionManager(connector, scope, idleGrace = 1.seconds)
        val session = TerminalSession(manager.acquire(1), attach, sink, scope, PtySpec(100, 30))
        session.start()
        return Triple(session, connector, manager)
    }

    @Test
    fun `tmux tab pumps output, writes input in order and resizes`() =
        runBlocking<Unit> {
            val (session, connector) = setUp(AttachSpec.Tmux("claude-api", "~/src/api"))
            withTimeout(5.seconds) { session.state.first { it == TerminalState.Open } }
            val conn = connector.made.single()
            conn.commands.single()!! shouldStartWith "sh -c '"
            val shell = conn.shells.single()

            shell.emit("hello ")
            shell.emit("world")
            eventually { screenText() == "hello world" }

            for (i in 1..50) session.send("$i,".toByteArray())
            session.send(ByteArray(0))
            eventually { shell.writtenText() == (1..50).joinToString("") { "$it," } }

            session.resize(120, 40)
            shell.sizes shouldBe listOf(120 to 40)
            shell.failResize = true
            session.resize(90, 30) // swallowed
            shouldThrow<IllegalArgumentException> { session.resize(0, 0) }
            shouldThrow<IllegalStateException> { session.start() }

            session.close()
            session.state.value
                .shouldBeInstanceOf<TerminalState.Ended>()
                .reason shouldBe "Closed"
        }

    @Test
    fun `tmux tab reattaches after the connection drops`() =
        runBlocking<Unit> {
            val (session, connector) = setUp(AttachSpec.Tmux("main"))
            withTimeout(5.seconds) { session.state.first { it == TerminalState.Open } }
            val first = connector.made.single()
            first.shells.single().breakStream()
            first.drop()
            eventually { connector.made.size == 2 && connector.made[1].shells.isNotEmpty() }
            withTimeout(5.seconds) { session.state.first { it == TerminalState.Open } }
            connector.made[1]
                .shells
                .single()
                .emit("redrawn by tmux")
            eventually { screenText().contains("redrawn by tmux") }
            session.close()
        }

    @Test
    fun `stream break without a drop marks the tab reattaching`() =
        runBlocking<Unit> {
            val (session, connector) = setUp(AttachSpec.Tmux("main"))
            withTimeout(5.seconds) { session.state.first { it == TerminalState.Open } }
            connector.made
                .single()
                .shells
                .single()
                .breakStream()
            withTimeout(5.seconds) { session.state.first { it == TerminalState.Reattaching } }
            session.close()
        }

    @Test
    fun `plain shell ends on exit or when the connection is lost`() =
        runBlocking<Unit> {
            val (exiting, c1) = setUp(AttachSpec.Shell)
            withTimeout(5.seconds) { exiting.state.first { it == TerminalState.Open } }
            c1.made
                .single()
                .commands
                .single() shouldBe null
            c1.made
                .single()
                .shells
                .single()
                .eof(status = 3)
            val ended = withTimeout(5.seconds) { exiting.state.first { it is TerminalState.Ended } }
            (ended as TerminalState.Ended).exitStatus shouldBe 3
            exiting.send("ignored".toByteArray())
            exiting.close()

            val (lost, c2) = setUp(AttachSpec.Shell)
            withTimeout(5.seconds) { lost.state.first { it == TerminalState.Open } }
            c2.made
                .single()
                .shells
                .single()
                .breakStream()
            val gone = withTimeout(5.seconds) { lost.state.first { it is TerminalState.Ended } }
            (gone as TerminalState.Ended).reason shouldBe "Connection lost"
            lost.close()

            val (dropped, c3) = setUp(AttachSpec.Shell)
            withTimeout(5.seconds) { dropped.state.first { it == TerminalState.Open } }
            c3.made.single().drop()
            withTimeout(5.seconds) { dropped.state.first { it is TerminalState.Ended } }
            dropped.close()
        }

    @Test
    fun `failed host connection ends the tab`() =
        runBlocking<Unit> {
            val connector =
                ScriptedConnector(
                    com.kitefoldlabs.bridle.core.ssh
                        .AuthenticationFailedException("denied"),
                )
            val (session) = setUp(AttachSpec.Tmux("x"), connector)
            val ended = withTimeout(5.seconds) { session.state.first { it is TerminalState.Ended } }
            (ended as TerminalState.Ended).reason shouldBe "denied"
            session.close()
        }

    @Test
    fun `shell open failure waits for the next connection`() =
        runBlocking<Unit> {
            val broken = FakeConnection().apply { failOpen = true }
            val (session, connector, manager) = setUp(AttachSpec.Tmux("x"), ScriptedConnector(broken))
            eventually { connector.attempts.size == 1 }
            delay(50.milliseconds)
            session.state.value shouldBe TerminalState.Connecting
            broken.drop()
            withTimeout(5.seconds) { session.state.first { it == TerminalState.Open } }
            session.close()
            manager.closeAll()
        }
}
