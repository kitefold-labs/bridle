package com.kitefoldlabs.bridle.core.data

import android.database.sqlite.SQLiteConstraintException
import app.cash.turbine.test
import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.core.model.Validation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostRepositoryTest {
    private val db: BridleDatabase = Fixtures.database()
    private val hosts = HostRepository(db)

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `upsert inserts, then updates in place`() =
        runTest {
            val host =
                Fixtures.host().copy(
                    tags = setOf("work", "arm64"),
                    env = mapOf("LANG" to "C.UTF-8"),
                    color = 0xFF00FF,
                    defaultTmuxSession = "main",
                    startupCommand = "uptime",
                    keepAliveSeconds = 15,
                )
            val id = hosts.upsert(host)

            hosts.get(id) shouldBe host.copy(id = id)

            val renamed = host.copy(id = id, label = "Renamed", favorite = true)
            hosts.upsert(renamed) shouldBe id
            hosts.get(id) shouldBe renamed
        }

    @Test
    fun `upsert with an unknown id inserts with that id`() =
        runTest {
            hosts.upsert(Fixtures.host().copy(id = 42)) shouldBe 42
            hosts.get(42)?.label shouldBe "Dev box"
        }

    @Test
    fun `upsert rejects invalid hosts`() =
        runTest {
            val error = shouldThrow<InvalidEntityException> { hosts.upsert(Fixtures.host(hostname = "bad host")) }

            error.problem shouldBe Validation.Invalid("hostname", Validation.Reason.ILLEGAL_CHARACTERS)
            error.message shouldBe "Invalid hostname: ILLEGAL_CHARACTERS"
        }

    @Test
    fun `upsert rejects dangling references`() =
        runTest {
            shouldThrow<SQLiteConstraintException> { hosts.upsert(Fixtures.host().copy(groupId = 99)) }
        }

    @Test
    fun `get returns null for unknown ids`() =
        runTest {
            hosts.get(1).shouldBeNull()
        }

    @Test
    fun `observeAll lists favourites, then recent, then the rest by label`() =
        runTest {
            val zed = hosts.upsert(Fixtures.host(label = "zed"))
            val alpha = hosts.upsert(Fixtures.host(label = "Alpha"))
            val beta = hosts.upsert(Fixtures.host(label = "beta"))
            val old = hosts.upsert(Fixtures.host(label = "old").copy(lastConnectedAtEpochMs = 10))
            val recent = hosts.upsert(Fixtures.host(label = "recent").copy(lastConnectedAtEpochMs = 20))
            val favourite = hosts.upsert(Fixtures.host(label = "fav").copy(favorite = true))

            hosts.observeAll().test {
                awaitItem().map { it.id } shouldBe listOf(favourite, recent, old, alpha, beta, zed)

                hosts.markConnected(zed, atEpochMs = 30) shouldBe true
                awaitItem().map { it.id } shouldBe listOf(favourite, zed, recent, old, alpha, beta)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `markConnected reports unknown hosts`() =
        runTest {
            hosts.markConnected(7, atEpochMs = 1) shouldBe false
        }

    @Test
    fun `observe follows one host until it is deleted`() =
        runTest {
            val id = hosts.upsert(Fixtures.host())

            hosts.observe(id).test {
                awaitItem()?.label shouldBe "Dev box"
                hosts.delete(id) shouldBe true
                awaitItem().shouldBeNull()
                cancelAndIgnoreRemainingEvents()
            }
            hosts.delete(id) shouldBe false
        }

    @Test
    fun `deleting a referenced row clears the reference`() =
        runTest {
            val identityId = IdentityRepository(db).insert(Fixtures.identity())
            val groupId = GroupRepository(db).upsert(Group(name = "Work"))
            val jump = hosts.upsert(Fixtures.host(label = "Bastion"))
            val id =
                hosts.upsert(
                    Fixtures.host().copy(identityId = identityId, groupId = groupId, jumpHostId = jump),
                )

            IdentityRepository(db).delete(identityId)
            GroupRepository(db).delete(groupId)
            hosts.delete(jump)

            val host = hosts.get(id)
            host?.identityId.shouldBeNull()
            host?.groupId.shouldBeNull()
            host?.jumpHostId.shouldBeNull()
        }

    @Test
    fun `deleting a host deletes what belongs to it`() =
        runTest {
            val id = hosts.upsert(Fixtures.host())
            val profiles = LaunchProfileRepository(db)
            val forwards = PortForwardRepository(db)
            val history = ComposeHistoryRepository(db)
            val profileId = profiles.upsert(LaunchProfile.claude(id, "~/src", "claude"))
            val forwardId =
                forwards.upsert(PortForward(hostId = id, type = PortForwardType.DYNAMIC, bindPort = 1080))
            history.save(id, listOf("ls")) shouldBe true

            hosts.delete(id) shouldBe true

            profiles.get(profileId).shouldBeNull()
            forwards.get(forwardId).shouldBeNull()
            history.get(id).shouldBeEmpty()
        }
}
