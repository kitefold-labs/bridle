package com.kitefoldlabs.bridle.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.session.HostConnector
import com.kitefoldlabs.bridle.core.session.TerminalSession
import com.kitefoldlabs.bridle.core.session.TerminalSink
import com.kitefoldlabs.bridle.core.ssh.AuthenticationFailedException
import com.kitefoldlabs.bridle.core.ssh.ConnectionState
import com.kitefoldlabs.bridle.core.ssh.DuplexStream
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.ssh.ShellChannel
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Records scripts; captured panes show [screen]; shells never produce output. */
private class RecordingConnection : SshConnection {
    val scripts = mutableListOf<String>()

    @Volatile var screen = ""
    override val state = MutableStateFlow(ConnectionState.CONNECTED)

    override suspend fun exec(
        command: String,
        timeout: Duration,
    ) = ExecResult(0, "", "")

    override suspend fun runScript(
        script: String,
        timeout: Duration,
    ): ExecResult {
        scripts += script
        return ExecResult(0, if ("capture-pane" in script) screen else "", "")
    }

    override suspend fun openShell(
        pty: PtySpec,
        command: String?,
    ): ShellChannel =
        object : ShellChannel {
            override val input: InputStream = InputStream.nullInputStream()
            override val output: OutputStream = OutputStream.nullOutputStream()
            override val isOpen = true
            override val exitStatus: Int? = null

            override suspend fun resize(
                columns: Int,
                rows: Int,
            ) = Unit

            override fun close() = Unit
        }

    override suspend fun openDirectTcpip(
        host: String,
        port: Int,
    ): DuplexStream = throw UnsupportedOperationException()

    override fun close() {
        state.value = ConnectionState.DISCONNECTED
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connection = RecordingConnection()
    private val coordinator = SessionCoordinator(ConnectionManager(HostConnector { connection }, scope), scope)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `tabs open, are found and close`() =
        runBlocking<Unit> {
            coordinator.hasActiveWork shouldBe false
            val tab = coordinator.openTab(1, "devbox", AttachSpec.Tmux("main"), { _, _ -> }, attachment = "emulator")
            coordinator.tabs.value shouldBe listOf(tab)
            coordinator.hasActiveWork shouldBe true
            coordinator.tab(tab.id)!!.attachment shouldBe "emulator"
            coordinator.tab("missing") shouldBe null
            coordinator.closeTab("missing")
            coordinator.closeTab(tab.id)
            coordinator.tabs.value shouldBe emptyList()
        }

    private class Owned(
        private val fail: Boolean = false,
    ) : AutoCloseable {
        var closed = 0

        override fun close() {
            closed++
            check(!fail) { "already gone" }
        }
    }

    private class ClosingSink :
        TerminalSink,
        AutoCloseable {
        var closed = false

        override fun onOutput(
            buffer: ByteArray,
            length: Int,
        ) = Unit

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `tabs are wired before their session starts and close their attachment`() =
        runBlocking<Unit> {
            var created: TerminalSession? = null
            val first = Owned()
            val tab = coordinator.openTab(1, "a", AttachSpec.Shell, { _, _ -> }, attachment = first) { created = it }
            created shouldBe tab.session
            coordinator.closeTab(tab.id)
            first.closed shouldBe 1
            val second = Owned()
            coordinator.openTab(1, "b", AttachSpec.Shell, { _, _ -> }, attachment = second)
            coordinator.disconnectAll()
            second.closed shouldBe 1
            shouldThrow<IllegalArgumentException> {
                coordinator.openTab(1, "bad", AttachSpec.Tmux("bad name"), { _, _ -> })
            }
            coordinator.tabs.value shouldBe emptyList()
        }

    @Test
    fun `closing a tab releases its sink and emulator`() =
        runBlocking<Unit> {
            val sink = ClosingSink()
            val emulator = Owned()
            val tab = coordinator.openTab(1, "a", AttachSpec.Shell, sink, attachment = emulator)
            tab.sink shouldBe sink
            coordinator.closeTab(tab.id)
            sink.closed shouldBe true
            emulator.closed shouldBe 1

            val failing = Owned(fail = true)
            val other = Owned()
            coordinator.openTab(1, "b", AttachSpec.Shell, { _, _ -> }, attachment = failing)
            coordinator.openTab(1, "c", AttachSpec.Shell, { _, _ -> }, attachment = other)
            coordinator.disconnectAll()
            failing.closed shouldBe 1
            other.closed shouldBe 1 // one failing close doesn't leak the rest
        }

    @Test
    fun `disconnect all closes tabs and connections`() =
        runBlocking<Unit> {
            coordinator.openTab(1, "a", AttachSpec.Shell, { _, _ -> })
            coordinator.openTab(1, "b", AttachSpec.Shell, { _, _ -> })
            coordinator.disconnectAll()
            coordinator.tabs.value shouldBe emptyList()
        }

    @Test
    fun `notification actions give up on a host that does not answer`() =
        runBlocking<Unit> {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val unreachable = SessionCoordinator(ConnectionManager(HostConnector { awaitCancellation() }, scope), scope)
            val actions = SessionActions(unreachable).apply { timeout = 200.milliseconds }
            val approve =
                Intent(SessionActionReceiver.ACTION_APPROVE)
                    .putExtra(SessionActionReceiver.EXTRA_HOST, 1L)
                    .putExtra(SessionActionReceiver.EXTRA_SESSION, "api")
            withTimeout(5.seconds) { actions.handle(context, approve) }
        }

    @Test
    fun `notification actions swallow failures but not cancellation`() =
        runBlocking<Unit> {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val approve =
                Intent(SessionActionReceiver.ACTION_APPROVE)
                    .putExtra(SessionActionReceiver.EXTRA_HOST, 1L)
                    .putExtra(SessionActionReceiver.EXTRA_SESSION, "api")
            val rejected = HostConnector { throw AuthenticationFailedException("denied") }
            SessionActions(SessionCoordinator(ConnectionManager(rejected, scope), scope)).handle(context, approve)

            val hanging = SessionCoordinator(ConnectionManager(HostConnector { awaitCancellation() }, scope), scope)
            var finished = false
            val job =
                launch {
                    SessionActions(hanging).handle(context, approve)
                    finished = true
                }
            delay(100)
            job.cancelAndJoin()
            job.isCancelled shouldBe true
            finished shouldBe false
        }

    @Test
    fun `notification actions answer claude prompts over tmux`() =
        runBlocking<Unit> {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val receiver = SessionActions(coordinator)

            fun intent(action: String) =
                Intent(
                    action,
                ).putExtra(SessionActionReceiver.EXTRA_HOST, 1L).putExtra(SessionActionReceiver.EXTRA_SESSION, "api")
            connection.screen = "Bash command\n  make clean\n Do you want to proceed?\n ❯ 1. Yes\n   2. No\n"
            receiver.handle(context, intent(SessionActionReceiver.ACTION_APPROVE))
            connection.scripts.dropLast(1).last() shouldContain "capture-pane -p -J -t '=api:'"
            connection.scripts.last() shouldContain "send-keys -t '=api:' -- 1"
            receiver.handle(context, intent(SessionActionReceiver.ACTION_DENY))
            connection.scripts.last() shouldContain "-- Escape"

            // Answered at the desk in the meantime: the keys would land in Claude's input box.
            connection.screen = "> \n  ? for shortcuts\n"
            val checked = connection.scripts.size
            receiver.handle(context, intent(SessionActionReceiver.ACTION_APPROVE))
            receiver.handle(context, intent(SessionActionReceiver.ACTION_DENY))
            connection.scripts.drop(checked).none { "send-keys" in it } shouldBe true
            connection.scripts.size shouldBe checked + 2
            val before = connection.scripts.size
            receiver.handle(
                context,
                Intent(SessionActionReceiver.ACTION_APPROVE).putExtra(SessionActionReceiver.EXTRA_HOST, 1L),
            )
            receiver.handle(
                context,
                Intent(SessionActionReceiver.ACTION_DENY).putExtra(SessionActionReceiver.EXTRA_SESSION, "x"),
            )
            receiver.handle(context, Intent("unknown"))
            connection.scripts.size shouldBe before
            coordinator.openTab(1, "a", AttachSpec.Shell, { _, _ -> })
            receiver.handle(context, Intent(SessionActionReceiver.ACTION_DISCONNECT_ALL))
            coordinator.tabs.value shouldBe emptyList()
        }
}
