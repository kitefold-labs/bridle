package com.kitefoldlabs.bridle.feature

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.session.HostConnector
import com.kitefoldlabs.bridle.core.ssh.ConnectionState
import com.kitefoldlabs.bridle.core.ssh.DuplexStream
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.core.ssh.PtySpec
import com.kitefoldlabs.bridle.core.ssh.ShellChannel
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.core.terminalui.CellMetrics
import com.kitefoldlabs.bridle.core.terminalui.TerminalEngine
import com.kitefoldlabs.bridle.core.terminalui.TerminalGridSize
import com.kitefoldlabs.bridle.core.terminalui.TerminalViewHost
import com.kitefoldlabs.bridle.service.SessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** An emulator that records what it is fed and draws a tappable box. */
class FakeEngine(
    initial: TerminalGridSize = TerminalGridSize(100, 30),
) : TerminalEngine {
    private val fedBytes = ByteArrayOutputStream()
    var closed = false

    override val size = MutableStateFlow(initial)
    override val title = MutableStateFlow("")
    override val isBracketedPasteEnabled = true
    override val isApplicationCursorKeys = false

    @Volatile override var onRemoteInput: ((ByteArray) -> Unit)? = null

    override fun feed(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        synchronized(fedBytes) { fedBytes.write(bytes, offset, length) }
    }

    fun fed(): String = synchronized(fedBytes) { fedBytes.toString(Charsets.UTF_8) }

    override fun resize(size: TerminalGridSize) {
        this.size.value = size
    }

    override fun copySelection(): String? = null

    override fun cellMetrics(fontSizePx: Float) = CellMetrics(10f, 20f)

    override fun close() {
        closed = true
    }

    @Composable
    override fun Content(
        host: TerminalViewHost,
        onPasteRequest: () -> Unit,
        onOpenUrl: (String) -> Unit,
        modifier: Modifier,
    ) {
        Box(modifier.testTag(TAG).clickable { host.onTerminalTap() }) { Text("font ${host.fontSizeSp}") }
    }

    companion object {
        const val TAG = "fake-terminal"
    }
}

/** A tmux session as [ScriptedHost] lists it. */
data class FakeSession(
    val name: String,
    val command: String = "bash",
    val path: String = "/home/me",
    val activity: Long = 1_700_000_000,
    val attached: Int = 0,
    val windows: Int = 1,
)

/**
 * A host that answers Bridle's tmux scripts from fields the test sets, records every script, and
 * opens shells whose output the test writes.
 */
class ScriptedHost : SshConnection {
    @Volatile var tmux = "tmux 3.4"

    @Volatile var claude = "/usr/local/bin/claude"

    @Volatile var helper = ""

    @Volatile var sessions: List<FakeSession> = emptyList()

    @Volatile var helperStates = ""

    @Volatile var screens: Map<String, String> = emptyMap()

    @Volatile var failing = false

    @Volatile var noTmux = false

    @Volatile var failPreview = false

    val scripts = CopyOnWriteArrayList<String>()
    val shells = CopyOnWriteArrayList<FakeShell>()
    override val state = MutableStateFlow(ConnectionState.CONNECTED)

    fun scriptsContaining(text: String): List<String> = scripts.filter { text in it }

    override suspend fun exec(
        command: String,
        timeout: Duration,
    ) = ExecResult(0, "", "")

    override suspend fun runScript(
        script: String,
        timeout: Duration,
    ): ExecResult {
        scripts += script
        if (failing) return ExecResult(1, "", "boom")
        if (noTmux && "command -v tmux" in script) return ExecResult(127, "", "tmux is not installed")
        val sep = ":|:"
        val out =
            when {
                // The install script embeds the whole helper, so it is recognised first.
                "BRIDLE_HELPER_INSTALLED" in script -> "BRIDLE_HELPER_INSTALLED\n"
                "uninstall-hooks" in script -> "removed\n"
                "printf 'tmux=%s" in script -> "tmux=$tmux\nclaude=$claude\nhelper=$helper\n"
                "list-sessions" in script ->
                    sessions.joinToString(
                        "\n",
                    ) { "${it.name}${sep}1$sep${it.attached}$sep${it.activity}$sep${it.windows}" } +
                        "\n@@BRIDLE_PANES@@\n" +
                        sessions.joinToString("\n") {
                            "${it.name}$sep%1${sep}1${sep}1${sep}0$sep${it.command}$sep${it.path}"
                        }
                "capture-pane" in script ->
                    if (failPreview) {
                        return ExecResult(1, "", "can't find session")
                    } else {
                        captures(script)
                    }
                "bridle-helper' list" in script || "bridle-helper list" in script -> helperStates
                "echo restarted" in script -> "restarted\n"
                "echo exists" in script -> "created\n"
                else -> ""
            }
        return ExecResult(0, out, "")
    }

    /** One pane's [screens] entry, or for a batched capture each `<marker> <session>` line followed by its screen. */
    private fun captures(script: String): String {
        val batched = Regex("""echo '(@@\w+@@) (\S+)'""").findAll(script).toList()
        if (batched.isEmpty()) {
            return screens.entries
                .firstOrNull { "=${it.key}:" in script }
                ?.value
                .orEmpty()
        }
        return batched.joinToString("") { match ->
            val (marker, session) = match.destructured
            "$marker $session\n${screens[session].orEmpty()}\n"
        }
    }

    override suspend fun openShell(
        pty: PtySpec,
        command: String?,
    ): ShellChannel = FakeShell(pty, command).also { shells += it }

    override suspend fun openDirectTcpip(
        host: String,
        port: Int,
    ): DuplexStream = throw UnsupportedOperationException()

    override fun close() {
        state.value = ConnectionState.DISCONNECTED
    }
}

/** A shell channel: the test writes remote output with [emit] and reads what was typed with [typed]. */
class FakeShell(
    val pty: PtySpec,
    val command: String?,
) : ShellChannel {
    private val remote = PipedOutputStream()
    override val input: InputStream = PipedInputStream(remote, 64 * 1024)
    private val sent = ByteArrayOutputStream()
    override val output: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) {
                synchronized(sent) { sent.write(b) }
            }

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {
                synchronized(sent) { sent.write(b, off, len) }
            }
        }

    @Volatile override var isOpen = true
    override val exitStatus: Int? = null
    val resizes = CopyOnWriteArrayList<Pair<Int, Int>>()

    fun emit(text: String) {
        remote.write(text.toByteArray())
        remote.flush()
    }

    fun typed(): String = synchronized(sent) { sent.toString(Charsets.UTF_8) }

    /** The remote side closes (e.g. tmux detached): the session ends cleanly. */
    fun hangUp() {
        remote.close()
    }

    override suspend fun resize(
        columns: Int,
        rows: Int,
    ) {
        resizes += columns to rows
    }

    override fun close() {
        isOpen = false
        runCatching { remote.close() }
    }
}

/** An in-memory data layer and a coordinator connected to [host]. */
class TestWorld(
    context: Context,
    val scope: CoroutineScope,
    val host: ScriptedHost = ScriptedHost(),
) {
    val data: DataModule =
        DataModule.inMemory(
            context,
            PreferenceDataStoreFactory.create(scope = scope) {
                File(context.cacheDir, "settings-${System.nanoTime()}.preferences_pb")
            },
            allowMainThreadQueries = true,
        )
    val connector = HostConnector { host }
    val connections = ConnectionManager(connector, scope)
    val coordinator = SessionCoordinator(connections, scope)

    suspend fun addHost(
        label: String = "devbox",
        defaultTmuxSession: String? = null,
    ): Long =
        data.hosts.upsert(
            Host(
                label = label,
                hostname = "$label.example.com",
                username = "me",
                defaultTmuxSession = defaultTmuxSession,
            ),
        )
}

/** Polls [condition] until it holds or [timeoutMs] passes, then asserts it. */
fun awaitUntil(
    timeoutMs: Long = 5_000,
    message: String = "condition not met",
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { message }
        Thread.sleep(10)
    }
}

/**
 * Composes [content], then recomposes it the ways a screen is recomposed in use: with only a
 * cosmetic change ([content]'s `tick`), with new callbacks, and with each of [states] in turn. This
 * exercises Compose's skipping and remembered-lambda paths, not just the first composition.
 */
fun <S, A> ComposeContentTestRule.recomposeThrough(
    states: List<S>,
    actions: List<A>,
    content: @Composable (state: S, actions: A, tick: Int) -> Unit,
) {
    var state by mutableStateOf(states.first())
    var current by mutableStateOf(actions.first())
    var tick by mutableIntStateOf(0)
    setContent { content(state, current, tick) }
    waitForIdle()
    for (next in states) {
        state = next
        waitForIdle()
        tick++
        waitForIdle()
        actions.forEach {
            current = it
            waitForIdle()
        }
    }
}
