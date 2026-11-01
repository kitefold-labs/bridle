package com.kitefoldlabs.bridle.core.terminal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ComposeHistoryTest {
    @Test
    fun `entries are most recent first and de-duplicated`() {
        val h = ComposeHistory()
        h.add("one") shouldBe true
        h.add("two")
        h.add("one")
        h.entries shouldBe listOf("one", "two")
        h.capacity shouldBe ComposeHistory.DEFAULT_CAPACITY
    }

    @Test
    fun `blank and oversized submissions are not recorded`() {
        val h = ComposeHistory()
        h.add("  \n") shouldBe false
        h.add("x".repeat(ComposeHistory.MAX_ENTRY_LENGTH + 1)) shouldBe false
        h.add("x".repeat(ComposeHistory.MAX_ENTRY_LENGTH)) shouldBe true
        h.entries.size shouldBe 1
    }

    @Test
    fun `capacity drops the oldest entries`() {
        val h = ComposeHistory(capacity = 3)
        listOf("a", "b", "c", "d").forEach(h::add)
        h.entries shouldBe listOf("d", "c", "b")
    }

    @Test
    fun `default capacity is 200`() {
        val h = ComposeHistory()
        repeat(250) { h.add("cmd $it") }
        h.entries.size shouldBe 200
        h.entries.first() shouldBe "cmd 249"
    }

    @Test
    fun `restored entries are filtered, de-duplicated and capped`() {
        ComposeHistory(listOf("a", "", "b", "a", "c", "d"), capacity = 3).entries shouldBe listOf("a", "b", "c")
        shouldThrow<IllegalArgumentException> { ComposeHistory(capacity = 0) }
    }

    @Test
    fun `up and down navigate and restore the draft`() {
        val h = ComposeHistory(listOf("newest", "middle", "oldest"))
        h.isNavigating shouldBe false
        h.next() shouldBe null
        h.previous("my draft") shouldBe "newest"
        h.isNavigating shouldBe true
        h.previous("newest (edited)") shouldBe "middle"
        h.previous("middle") shouldBe "oldest"
        h.previous("oldest") shouldBe null
        h.next() shouldBe "middle"
        h.next() shouldBe "newest"
        h.next() shouldBe "my draft"
        h.isNavigating shouldBe false
        h.next() shouldBe null
    }

    @Test
    fun `empty history has nothing to navigate`() {
        val h = ComposeHistory()
        h.previous("draft") shouldBe null
        h.isNavigating shouldBe false
    }

    @Test
    fun `submitting or resetting ends navigation`() {
        val h = ComposeHistory(listOf("a", "b"))
        h.previous("draft")
        h.add("c")
        h.isNavigating shouldBe false
        h.previous("") shouldBe "c"
        h.resetNavigation()
        h.isNavigating shouldBe false
        h.previous("new draft") shouldBe "c"
        h.next() shouldBe "new draft"
    }

    @Test
    fun `remove and clear forget entries`() {
        val h = ComposeHistory(listOf("secret", "safe"))
        h.previous("")
        h.remove("secret") shouldBe true
        h.remove("missing") shouldBe false
        h.isNavigating shouldBe false
        h.entries shouldBe listOf("safe")
        h.previous("")
        h.clear()
        h.isNavigating shouldBe false
        h.entries shouldBe emptyList()
    }

    @Test
    fun `entries stay unique and bounded`() =
        runBlocking<Unit> {
            checkAll(Arb.list(Arb.element("a", "b", "c", "d", "e", " ", ""), 0..40)) { submissions ->
                val h = ComposeHistory(capacity = 4)
                submissions.forEach(h::add)
                h.entries.shouldBeUnique()
                h.entries.size shouldBeLessThanOrEqual 4
                submissions.lastOrNull { it.isNotBlank() }?.let { h.entries.first() shouldBe it }
            }
        }
}

class ComposeHistoriesTest {
    @Test
    fun `histories are per host and created on demand`() {
        val all = ComposeHistories()
        val one = all.forHost(1)
        all.forHost(1) shouldBeSameInstanceAs one
        one.add("on host 1")
        all.forHost(2).entries shouldBe emptyList()
        all.snapshot() shouldBe mapOf(1L to listOf("on host 1"))
    }

    @Test
    fun `restores from a snapshot and drops removed hosts`() {
        val all = ComposeHistories(mapOf(1L to listOf("a", "b"), 2L to listOf("c")), capacity = 1)
        all.capacity shouldBe 1
        all.forHost(1).entries shouldBe listOf("a")
        all.forHost(3).capacity shouldBe 1
        all.removeHost(2)
        all.snapshot() shouldBe mapOf(1L to listOf("a"))
    }
}
