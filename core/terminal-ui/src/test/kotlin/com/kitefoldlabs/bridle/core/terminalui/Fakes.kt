package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.kitefoldlabs.bridle.core.session.TerminalState
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/** An engine that records what it is fed and renders a tappable box. */
class FakeTerminalEngine(
    initial: TerminalGridSize = TerminalGridSize(80, 24),
) : TerminalEngine {
    val fed = ByteArrayOutputStream()
    var feedCalls = 0
    val resizes = mutableListOf<TerminalGridSize>()
    var selection: String? = null
    var closed = false
    var cell = CellMetrics(10f, 20f)
    var failFeed = false
    var lastHost: TerminalViewHost? = null

    @Volatile override var isBracketedPasteEnabled = false

    @Volatile override var isApplicationCursorKeys = false

    override val size = MutableStateFlow(initial)
    override val title = MutableStateFlow("")
    override var onRemoteInput: ((ByteArray) -> Unit)? = null

    override fun feed(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(!failFeed) { "closed" }
        synchronized(fed) {
            feedCalls++
            fed.write(bytes, offset, length)
        }
    }

    fun fedText(): String = synchronized(fed) { fed.toString(Charsets.UTF_8) }

    override fun resize(size: TerminalGridSize) {
        resizes += size
        this.size.value = size
    }

    override fun copySelection(): String? = selection.also { selection = null }

    override fun cellMetrics(fontSizePx: Float): CellMetrics = cell

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
        lastHost = host
        Box(modifier.testTag(TERMINAL_TAG).clickable { host.onTerminalTap() }) {
            Column {
                Text("font ${host.fontSizeSp}")
                Text("ime ${host.showSoftKeyboard}")
                Text("paste", Modifier.clickable { onPasteRequest() })
                Text("link", Modifier.clickable { onOpenUrl("https://example.com/") })
                Text("bad link", Modifier.clickable { onOpenUrl("intent://evil") })
            }
        }
    }

    companion object {
        const val TERMINAL_TAG = "terminal"
    }
}

/** A connection that records what the screen sends. */
class FakeConnection(
    initial: TerminalState = TerminalState.Open,
) : TerminalConnection {
    override val state = MutableStateFlow(initial)
    val sent = mutableListOf<ByteArray>()
    val resizes = mutableListOf<Pair<Int, Int>>()

    override fun send(bytes: ByteArray) {
        sent += bytes
    }

    override suspend fun resize(
        columns: Int,
        rows: Int,
    ) {
        resizes += columns to rows
    }

    fun sentText(): String = sent.joinToString("") { it.toString(Charsets.UTF_8) }
}

/** Runs queued tasks only when told to. */
class ManualExecutor : Executor {
    val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        synchronized(tasks) { tasks.addLast(command) }
    }

    fun runAll() {
        while (true) {
            val next = synchronized(tasks) { tasks.removeFirstOrNull() } ?: return
            next.run()
        }
    }

    val size: Int get() = synchronized(tasks) { tasks.size }
}
