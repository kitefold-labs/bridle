package com.kitefoldlabs.bridle.core.data

import android.database.sqlite.SQLiteConstraintException
import app.cash.turbine.test
import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.model.LaunchMode
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.core.model.RestartMode
import com.kitefoldlabs.bridle.core.model.Snippet
import com.kitefoldlabs.bridle.core.model.SnippetRunMode
import com.kitefoldlabs.bridle.core.model.Validation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileSnippetForwardRepositoryTest {
    private val db: BridleDatabase = Fixtures.database()
    private val hosts = HostRepository(db)
    private val profiles = LaunchProfileRepository(db)
    private val snippets = SnippetRepository(db)
    private val forwards = PortForwardRepository(db)

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `launch profiles round-trip and update in place`() =
        runTest {
            val hostId = hosts.upsert(Fixtures.host())
            val profile =
                LaunchProfile(
                    hostId = hostId,
                    name = "API",
                    sessionName = "api",
                    workingDir = "~/src/api",
                    command = "claude --model opus",
                    restartMode = RestartMode.SEND_KEYS,
                    restartCommand = null,
                    env = mapOf("NODE_ENV" to "development"),
                    mode = LaunchMode.DIRECT,
                    pinned = true,
                )
            val id = profiles.upsert(profile)

            profiles.get(id) shouldBe profile.copy(id = id)
            profiles.upsert(profile.copy(id = id, name = "API server")) shouldBe id
            profiles.get(id)?.name shouldBe "API server"
            profiles.delete(id) shouldBe true
            profiles.delete(id) shouldBe false
            profiles.get(id).shouldBeNull()
        }

    @Test
    fun `launch profiles are listed pinned first, then by name`() =
        runTest {
            val one = hosts.upsert(Fixtures.host(label = "one"))
            val two = hosts.upsert(Fixtures.host(label = "two"))
            profiles.upsert(LaunchProfile.claude(one, "~/b", "b").copy(name = "beta"))
            profiles.upsert(LaunchProfile.claude(one, "~/a", "a").copy(name = "Alpha"))
            profiles.upsert(LaunchProfile.claude(one, "~/z", "z").copy(name = "zulu", pinned = true))
            profiles.upsert(LaunchProfile.claude(two, "~/c", "c").copy(name = "charlie"))

            profiles.observeForHost(one).test {
                awaitItem().map { it.name } shouldBe listOf("zulu", "Alpha", "beta")
                cancelAndIgnoreRemainingEvents()
            }
            profiles.observeAll().test {
                awaitItem().map { it.name } shouldBe listOf("zulu", "Alpha", "beta", "charlie")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `launch profiles are validated and need their host`() =
        runTest {
            shouldThrow<InvalidEntityException> {
                profiles.upsert(LaunchProfile.claude(1, "~/src", "bad.name"))
            }.problem shouldBe Validation.Invalid("sessionName", Validation.Reason.ILLEGAL_CHARACTERS)
            shouldThrow<SQLiteConstraintException> { profiles.upsert(LaunchProfile.claude(99, "~/src", "ok")) }
        }

    @Test
    fun `snippets round-trip and are listed by package, then name`() =
        runTest {
            val disk = Snippet(name = "disk", body = "df -h {{path:/}}", runMode = SnippetRunMode.EXEC)
            val id = snippets.upsert(disk)
            snippets.upsert(Snippet(name = "Logs", body = "journalctl -f", packageName = "systemd"))
            snippets.upsert(Snippet(name = "Apt", body = "apt update", packageName = "debian"))
            snippets.upsert(Snippet(name = "Clear", body = "clear"))

            snippets.get(id) shouldBe disk.copy(id = id)
            snippets.observeAll().test {
                awaitItem().map { it.name } shouldBe listOf("Clear", "disk", "Apt", "Logs")
                snippets.upsert(disk.copy(id = id, name = "Disk usage")) shouldBe id
                awaitItem().map { it.name } shouldBe listOf("Clear", "Disk usage", "Apt", "Logs")
                cancelAndIgnoreRemainingEvents()
            }
            snippets.delete(id) shouldBe true
            snippets.delete(id) shouldBe false
            snippets.get(id).shouldBeNull()
        }

    @Test
    fun `snippets need a name`() =
        runTest {
            shouldThrow<InvalidEntityException> { snippets.upsert(Snippet(name = "  ", body = "ls")) }.problem shouldBe
                Validation.Invalid("name", Validation.Reason.BLANK)
        }

    @Test
    fun `port forwards round-trip and are listed per host by port`() =
        runTest {
            val hostId = hosts.upsert(Fixtures.host())
            val other = hosts.upsert(Fixtures.host(label = "other"))
            val web =
                PortForward(
                    hostId = hostId,
                    type = PortForwardType.LOCAL,
                    bindPort = 8080,
                    destHost = "localhost",
                    destPort = 80,
                    autoStart = true,
                )
            val id = forwards.upsert(web)
            forwards.upsert(PortForward(hostId = hostId, type = PortForwardType.DYNAMIC, bindPort = 1080))
            forwards.upsert(PortForward(hostId = other, type = PortForwardType.DYNAMIC, bindPort = 1081))

            forwards.get(id) shouldBe web.copy(id = id)
            forwards.observeForHost(hostId).test {
                awaitItem().map { it.bindPort } shouldBe listOf(1080, 8080)
                forwards.upsert(web.copy(id = id, bindPort = 80)) shouldBe id
                awaitItem().map { it.bindPort } shouldBe listOf(80, 1080)
                forwards.delete(id) shouldBe true
                awaitItem().map { it.bindPort } shouldBe listOf(1080)
                cancelAndIgnoreRemainingEvents()
            }
            forwards.delete(id) shouldBe false
            forwards.get(id).shouldBeNull()
        }

    @Test
    fun `port forwards are validated and need their host`() =
        runTest {
            shouldThrow<InvalidEntityException> {
                forwards.upsert(PortForward(hostId = 1, type = PortForwardType.LOCAL, bindPort = 8080))
            }.problem.field shouldBe "hostname"
            shouldThrow<SQLiteConstraintException> {
                forwards.upsert(PortForward(hostId = 99, type = PortForwardType.DYNAMIC, bindPort = 1080))
            }
        }
}
