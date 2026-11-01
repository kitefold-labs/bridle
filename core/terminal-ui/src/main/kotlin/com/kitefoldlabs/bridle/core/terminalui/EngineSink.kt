package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.session.TerminalSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Hands terminal output from [com.kitefoldlabs.bridle.core.session.TerminalSession]'s I/O thread
 * to an [engine] on [executor], built for bursts such as Claude Code redrawing its whole TUI.
 *
 * - Each chunk is **copied** (the session reuses its read buffer) into a pending buffer.
 * - Chunks are **coalesced**: at most one feed task is queued at a time, and it feeds everything
 *   that arrived since the previous one in a single [TerminalEngine.feed] call. A burst of
 *   hundreds of reads costs a handful of tasks, not one each. Two buffers are swapped, so the
 *   steady state allocates nothing.
 * - **Back-pressure**: once [highWaterBytes] are pending, [onOutput] blocks the reader until the
 *   engine catches up, so a flood (`cat` of a huge file) slows the SSH channel down instead of
 *   growing memory. The wait is interruptible, so cancelling the session still works.
 *
 * Bytes reach the engine in order and from one task at a time. After [close], output is dropped
 * and a blocked reader is released.
 */
public class EngineSink(
    private val engine: TerminalEngine,
    private val executor: Executor = Dispatchers.Default.limitedParallelism(1).asExecutor(),
    public val highWaterBytes: Int = DEFAULT_HIGH_WATER_BYTES,
) : TerminalSink,
    AutoCloseable {
    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private var pending = ByteArray(INITIAL_CAPACITY)
    private var pendingLength = 0
    private var spare = ByteArray(INITIAL_CAPACITY)
    private var scheduled = false
    private var closed = false

    init {
        require(highWaterBytes > 0) { "highWaterBytes must be positive: $highWaterBytes" }
    }

    /** Bytes waiting for the engine; for tests and diagnostics. */
    public val pendingBytes: Int
        get() = lock.withLock { pendingLength }

    override fun onOutput(
        buffer: ByteArray,
        length: Int,
    ) {
        if (length <= 0) return
        val schedule =
            lock.withLock {
                while (!closed && pendingLength >= highWaterBytes) drained.await()
                if (closed) return
                if (pendingLength + length > pending.size) {
                    pending = pending.copyOf(maxOf(pendingLength + length, pending.size * 2))
                }
                System.arraycopy(buffer, 0, pending, pendingLength, length)
                pendingLength += length
                val idle = !scheduled
                scheduled = true
                idle
            }
        if (schedule) executor.execute(::drain)
    }

    /** Stops feeding the engine; pending output is dropped. Does not close the engine. */
    override fun close() {
        lock.withLock {
            closed = true
            pendingLength = 0
            drained.signalAll()
        }
    }

    private fun drain() {
        val batch: ByteArray
        val length: Int
        lock.withLock {
            if (closed) {
                scheduled = false
                return
            }
            batch = pending
            length = pendingLength
            pending = spare
            spare = batch
            pendingLength = 0
            drained.signalAll()
        }
        try {
            engine.feed(batch, 0, length)
        } catch (_: IllegalStateException) {
            // The engine was closed under us (the tab is going away): stop instead of crashing
            // the executor's thread.
            close()
        } finally {
            // Re-queue rather than loop, so a continuous stream never monopolises the executor.
            val again =
                lock.withLock {
                    (!closed && pendingLength > 0).also { scheduled = it }
                }
            if (again) executor.execute(::drain)
        }
    }

    public companion object {
        /** About a second of a fast link; far more than a screenful of redraws. */
        public const val DEFAULT_HIGH_WATER_BYTES: Int = 512 * 1024

        private const val INITIAL_CAPACITY = 32 * 1024
    }
}
