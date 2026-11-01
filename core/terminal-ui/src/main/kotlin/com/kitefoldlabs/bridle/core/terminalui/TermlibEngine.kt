package com.kitefoldlabs.bridle.core.terminalui

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulatorFactory
import kotlin.math.ceil

/**
 * [TerminalEngine] backed by ConnectBot's termlib (libvterm through JNI, drawn by Compose).
 *
 * Deliberately thin: termlib does the emulation, drawing, selection, scrollback and soft
 * keyboard. This adapter adds what termlib does not expose (the modes and title, via
 * [TerminalModeTracker]), applies the key bar's sticky modifiers to termlib's keyboard, and
 * routes termlib's paste menu through Bridle's sanitising paste. It can live in the service:
 * only [Content] needs a UI.
 */
public class TermlibEngine(
    initialSize: TerminalGridSize = TerminalGridSize(DEFAULT_COLUMNS, DEFAULT_ROWS),
) : TerminalEngine {
    private val modes = TerminalModeTracker()
    private val mutableSize = MutableStateFlow(initialSize)

    /** Set by termlib's view once it is shown. */
    @Volatile internal var selection: SelectionController? = null

    @Volatile override var onRemoteInput: ((ByteArray) -> Unit)? = null

    // The native library loads on first use (feed/resize), not here.
    private val emulator =
        TerminalEmulatorFactory.create(
            initialRows = initialSize.rows,
            initialCols = initialSize.columns,
            onKeyboardInput = ::deliverRemoteInput,
            onResize = ::onEmulatorResized,
        )

    override val size: StateFlow<TerminalGridSize> = mutableSize.asStateFlow()
    override val title: StateFlow<String> get() = modes.title
    override val isBracketedPasteEnabled: Boolean get() = modes.isBracketedPasteEnabled
    override val isApplicationCursorKeys: Boolean get() = modes.isApplicationCursorKeys

    override fun feed(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        modes.feed(bytes, offset, length)
        emulator.writeInput(bytes, offset, length)
    }

    override fun resize(size: TerminalGridSize) {
        val current = emulator.dimensions
        if (current.columns != size.columns || current.rows != size.rows) emulator.resize(size.rows, size.columns)
    }

    override fun copySelection(): String? = selection?.copySelection()?.ifEmpty { null }

    override fun cellMetrics(fontSizePx: Float): CellMetrics = measureCell(fontSizePx)

    /** termlib's keyboard output and query replies (posted to the main looper). */
    internal fun deliverRemoteInput(bytes: ByteArray) {
        onRemoteInput?.invoke(bytes)
    }

    /** termlib resized the emulator, from its own layout or from [resize]. */
    internal fun onEmulatorResized(dimensions: TerminalDimensions) {
        mutableSize.value = TerminalGridSize(dimensions.columns, dimensions.rows)
    }

    override fun close(): Unit = emulator.close()

    @Composable
    override fun Content(
        host: TerminalViewHost,
        onPasteRequest: () -> Unit,
        onOpenUrl: (String) -> Unit,
        modifier: Modifier,
    ) {
        val sticky = host.stickyModifiers
        val bridge = remember(sticky) { StickyModifierBridge(sticky) }
        Terminal(
            terminalEmulator = emulator,
            modifier = modifier,
            initialFontSize = host.fontSizeSp.sp,
            keyboardEnabled = true,
            showSoftKeyboard = host.showSoftKeyboard,
            onTerminalTap = host::onTerminalTap,
            onImeVisibilityChanged = host::onImeVisibilityChanged,
            modifierManager = bridge,
            onSelectionControllerAvailable = { selection = it },
            onHyperlinkClick = onOpenUrl,
            onPasteRequest = onPasteRequest,
        )
    }

    public companion object {
        public const val DEFAULT_COLUMNS: Int = 80
        public const val DEFAULT_ROWS: Int = 24

        /** termlib's row height when a font reports no metrics. */
        private const val FALLBACK_CELL_HEIGHT = 20f

        /** termlib's cell size for [fontSizePx]: the advance of "M" by the line height, rounded up. */
        public fun measureCell(fontSizePx: Float): CellMetrics {
            val paint =
                TextPaint().apply {
                    typeface = Typeface.MONOSPACE
                    textSize = fontSizePx
                    isAntiAlias = true
                }
            val metrics = paint.fontMetrics
            val height = ceil(metrics.descent - metrics.ascent)
            return CellMetrics(paint.measureText("M"), if (height > 0f) height else FALLBACK_CELL_HEIGHT)
        }
    }
}
