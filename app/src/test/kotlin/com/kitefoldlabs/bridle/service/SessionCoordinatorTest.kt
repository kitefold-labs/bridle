package com.kitefoldlabs.bridle.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.session.HostConnector
import com.kitefoldlabs.bridle.core.session.TerminalSession
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration

/** Records scripts; shells never produce output. */
private class RecordingConnection : SshConnection {
    val scripts = mutableListOf<String>()
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
        return ExecResult(0, "", "")
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

    private class Closeable : AutoCloseable {
        var closed = 0

        override fun close() {
            closed++
        }
    }

    @Test
    fun `tabs are wired before their session starts and close their attachment`() =
        runBlocking<Unit> {
            var created: TerminalSession? = null
            val first = Closeable()
            val tab = coordinator.openTab(1, "a", AttachSpec.Shell, { _, _ -> }, attachment = first) { created = it }
            created shouldBe tab.session
            coordinator.closeTab(tab.id)
            first.closed shouldBe 1
            val second = Closeable()
            coordinator.openTab(1, "b", AttachSpec.Shell, { _, _ -> }, attachment = second)
            coordinator.disconnectAll()
            second.closed shouldBe 1
            shouldThrow<IllegalArgumentException> {
                coordinator.openTab(1, "bad", AttachSpec.Tmux("bad name"), { _, _ -> })
            }
            coordinator.tabs.value shouldBe emptyList()
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
    fun `notification actions answer claude prompts over tmux`() =
        runBlocking<Unit> {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val receiver = SessionActions(coordinator)

            fun intent(action: String) =
                Intent(
                    action,
                ).putExtra(SessionActionReceiver.EXTRA_HOST, 1L).putExtra(SessionActionReceiver.EXTRA_SESSION, "api")
            receiver.handle(context, intent(SessionActionReceiver.ACTION_APPROVE))
            connection.scripts.last() shouldContain "send-keys -t '=api:' -- 1"
            receiver.handle(context, intent(SessionActionReceiver.ACTION_DENY))
            connection.scripts.last() shouldContain "-- Escape"
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
