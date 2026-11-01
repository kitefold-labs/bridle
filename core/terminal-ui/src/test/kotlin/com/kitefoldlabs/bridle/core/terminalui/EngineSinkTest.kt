package com.kitefoldlabs.bridle.core.terminalui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class EngineSinkTest {
    private val engine = FakeTerminalEngine()
    private val executor = ManualExecutor()

    private fun EngineSink.emit(text: String) {
        val bytes = text.toByteArray()
        // Like TerminalSession: one reused buffer, larger than the chunk.
        val buffer = ByteArray(64)
        bytes.copyInto(buffer)
        onOutput(buffer, bytes.size)
        buffer.fill('!'.code.toByte()) // the session overwrites its buffer after the call
    }

    @Test
    fun `copies each chunk so the reused buffer can change`() {
        val sink = EngineSink(engine, executor)
        sink.emit("hello")
        executor.runAll()
        engine.fedText() shouldBe "hello"
    }

    @Test
    fun `coalesces a burst into one task and one feed`() {
        val sink = EngineSink(engine, executor)
        repeat(100) { sink.emit("$it,") }
        executor.size shouldBe 1
        sink.pendingBytes shouldBe (0 until 100).sumOf { "$it,".length }
        executor.runAll()
        engine.feedCalls shouldBe 1
        engine.fedText() shouldBe (0 until 100).joinToString("") { "$it," }
        sink.pendingBytes shouldBe 0
    }

    @Test
    fun `output arriving during a feed is fed by one follow-up task`() {
        val sink = EngineSink(engine, executor)
        sink.emit("a")
        val task = executor.tasks.removeFirst()
        task.run()
        sink.emit("b")
        sink.emit("c")
        executor.size shouldBe 1
        executor.runAll()
        engine.fedText() shouldBe "abc"
        engine.feedCalls shouldBe 2
    }

    @Test
    fun `re-queues instead of looping when more output arrived mid-feed`() {
        lateinit var sink: EngineSink
        var injected = false
        val reentrant =
            object : TerminalEngine by engine {
                override fun feed(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    engine.feed(bytes, offset, length)
                    if (!injected) {
                        injected = true
                        sink.emit("2")
                    }
                }
            }
        sink = EngineSink(reentrant, executor)
        sink.emit("1")
        executor.tasks.removeFirst().run()
        executor.size shouldBe 1 // the follow-up, not a loop inside the first task
        executor.runAll()
        engine.fedText() shouldBe "12"
    }

    @Test
    fun `grows the buffer for large chunks`() {
        val sink = EngineSink(engine, executor)
        val big = ByteArray(100_000) { (it % 251).toByte() }
        sink.onOutput(big, big.size)
        sink.onOutput(big, 10)
        executor.runAll()
        engine.fed.toByteArray().toList() shouldBe (big.toList() + big.take(10))
    }

    @Test
    fun `ignores empty chunks`() {
        val sink = EngineSink(engine, executor)
        sink.onOutput(ByteArray(8), 0)
        executor.size shouldBe 0
    }

    @Test
    fun `close drops pending output and later chunks`() {
        val sink = EngineSink(engine, executor)
        sink.emit("lost")
        sink.close()
        sink.pendingBytes shouldBe 0
        executor.runAll()
        sink.emit("also lost")
        executor.runAll()
        engine.feedCalls shouldBe 0
        engine.closed shouldBe false // the sink does not own the engine
    }

    @Test
    fun `stops when the engine has been closed`() {
        val sink = EngineSink(engine, executor)
        engine.failFeed = true
        sink.emit("x")
        executor.runAll()
        sink.emit("y")
        executor.size shouldBe 0
        engine.feedCalls shouldBe 0
    }

    @Test
    fun `blocks the reader at the high-water mark until the engine catches up`() {
        val sink = EngineSink(engine, executor, highWaterBytes = 4)
        sink.emit("1234")
        val released = CountDownLatch(1)
        val reader =
            thread {
                sink.emit("5")
                released.countDown()
            }
        released.await(200, TimeUnit.MILLISECONDS) shouldBe false
        executor.runAll() // drains "1234" and signals the reader
        released.await(5, TimeUnit.SECONDS) shouldBe true
        reader.join()
        executor.runAll()
        engine.fedText() shouldBe "12345"
    }

    @Test
    fun `close releases a blocked reader`() {
        val sink = EngineSink(engine, executor, highWaterBytes = 1)
        sink.emit("1")
        val reader = thread { sink.emit("2") }
        Thread.sleep(50)
        sink.close()
        reader.join(5_000)
        reader.isAlive shouldBe false
    }

    @Test
    fun `a blocked reader can be interrupted`() {
        val sink = EngineSink(engine, executor, highWaterBytes = 1)
        sink.emit("1")
        val failure = AtomicReference<Throwable?>()
        val reader =
            thread {
                try {
                    sink.emit("2")
                } catch (e: InterruptedException) {
                    failure.set(e)
                }
            }
        Thread.sleep(50)
        reader.interrupt()
        reader.join(5_000)
        (failure.get() is InterruptedException) shouldBe true
    }

    @Test
    fun `keeps every byte in order under a real reader and executor`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val sink = EngineSink(engine, pool, highWaterBytes = 1024)
            val buffer = ByteArray(32 * 1024)
            val expected = StringBuilder()
            repeat(5_000) { i ->
                val chunk = "line $i\r\n".toByteArray()
                chunk.copyInto(buffer)
                sink.onOutput(buffer, chunk.size)
                expected.append("line $i\r\n")
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (engine.fedText().length < expected.length && System.nanoTime() < deadline) Thread.sleep(5)
            engine.fedText() shouldBe expected.toString()
            engine.feedCalls shouldBeLessThan 5_000
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `uses a background executor by default`() {
        val sink = EngineSink(engine)
        sink.emit("default")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (engine.fedText().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        engine.fedText() shouldBe "default"
        sink.highWaterBytes shouldBe EngineSink.DEFAULT_HIGH_WATER_BYTES
    }

    @Test
    fun `rejects a non-positive high-water mark`() {
        shouldThrow<IllegalArgumentException> { EngineSink(engine, executor, highWaterBytes = 0) }
    }
}
