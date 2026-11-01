package com.kitefoldlabs.bridle.core.data

import app.cash.turbine.test
import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.terminal.ComposeHistories
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComposeHistoryRepositoryTest {
    private val db: BridleDatabase = Fixtures.database()
    private val hosts = HostRepository(db)
    private val history = ComposeHistoryRepository(db)

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `save replaces a host's history, keeping its order`() =
        runTest {
            val id = hosts.upsert(Fixtures.host())

            history.save(id, listOf("git status", "ls -la")) shouldBe true
            history.get(id) shouldBe listOf("git status", "ls -la")

            history.save(id, listOf("make test")) shouldBe true
            history.get(id) shouldBe listOf("make test")
        }

    @Test
    fun `save skips hosts that no longer exist`() =
        runTest {
            history.save(42, listOf("ls")) shouldBe false
            history.snapshot().shouldBeEmpty()
        }

    @Test
    fun `observe follows one host`() =
        runTest {
            val id = hosts.upsert(Fixtures.host())

            history.observe(id).test {
                awaitItem().shouldBeEmpty()
                history.save(id, listOf("htop"))
                awaitItem() shouldBe listOf("htop")
                history.clear(id) shouldBe true
                awaitItem().shouldBeEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `snapshots round-trip through ComposeHistories`() =
        runTest {
            val one = hosts.upsert(Fixtures.host(label = "one"))
            val two = hosts.upsert(Fixtures.host(label = "two"))
            val three = hosts.upsert(Fixtures.host(label = "three"))
            history.save(three, listOf("stale"))

            val histories = ComposeHistories()
            histories.forHost(one).add("ls")
            histories.forHost(one).add("pwd")
            histories.forHost(two).add("uptime")
            history.saveAll(histories.snapshot() + (99L to listOf("deleted host")))

            history.snapshot() shouldBe mapOf(one to listOf("pwd", "ls"), two to listOf("uptime"))
            ComposeHistories(history.snapshot()).forHost(one).previous("") shouldBe "pwd"
        }

    @Test
    fun `clearAll forgets everything`() =
        runTest {
            val id = hosts.upsert(Fixtures.host())
            history.save(id, listOf("ls"))

            history.clearAll() shouldBe 1
            history.clear(id) shouldBe false

            history.snapshot().shouldBeEmpty()
        }
}
