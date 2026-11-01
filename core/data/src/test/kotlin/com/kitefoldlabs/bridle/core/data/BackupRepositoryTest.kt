package com.kitefoldlabs.bridle.core.data

import android.database.sqlite.SQLiteConstraintException
import com.kitefoldlabs.bridle.core.backup.BackupMerge
import com.kitefoldlabs.bridle.core.backup.BackupPayload
import com.kitefoldlabs.bridle.core.backup.ExistingData
import com.kitefoldlabs.bridle.core.backup.IdentityMetadata
import com.kitefoldlabs.bridle.core.backup.ImportIssue
import com.kitefoldlabs.bridle.core.backup.ImportPlan
import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.core.model.Snippet
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupRepositoryTest {
    private val source: BridleDatabase = Fixtures.database()
    private val target: BridleDatabase = Fixtures.database()

    @After
    fun close() {
        source.close()
        target.close()
    }

    /** Fills [source] with one of everything and returns its export. */
    private suspend fun populatedExport(): BackupPayload {
        val identityId = IdentityRepository(source).insert(Fixtures.identity(), byteArrayOf(1, 2, 3))
        val groupId = GroupRepository(source).upsert(Group(name = "Work", defaultIdentityId = identityId))
        val bastion = HostRepository(source).upsert(Fixtures.host(label = "Bastion", hostname = "bastion.example.test"))
        val dev =
            HostRepository(source).upsert(
                Fixtures.host().copy(
                    identityId = identityId,
                    groupId = groupId,
                    jumpHostId = bastion,
                    tags = setOf("x"),
                ),
            )
        LaunchProfileRepository(source).upsert(LaunchProfile.claude(dev, "~/src/app", "claude"))
        SnippetRepository(source).upsert(Snippet(name = "Disk", body = "df -h"))
        PortForwardRepository(source).upsert(PortForward(hostId = dev, type = PortForwardType.DYNAMIC, bindPort = 1080))
        KnownHostRepository(source).trust(Fixtures.knownHost())
        ComposeHistoryRepository(source).save(dev, listOf("secret-ish command"))
        return BackupRepository(source).export(createdAtEpochMs = 123, appVersion = "1.2.3")
    }

    @Test
    fun `export carries configuration and identity metadata only`() =
        runTest {
            val payload = populatedExport()

            payload.createdAtEpochMs shouldBe 123
            payload.appVersion shouldBe "1.2.3"
            payload.hosts.map { it.label } shouldBe listOf("Bastion", "Dev box")
            payload.groups.map { it.name } shouldBe listOf("Work")
            payload.identities shouldBe listOf(IdentityMetadata.from(Fixtures.identity().copy(id = 1)))
            payload.launchProfiles shouldHaveSize 1
            payload.snippets shouldHaveSize 1
            payload.portForwards shouldHaveSize 1
            payload.knownHosts shouldBe listOf(Fixtures.knownHost())
        }

    @Test
    fun `existingData is what the merge planner needs`() =
        runTest {
            populatedExport()

            val existing = BackupRepository(source).existingData()

            existing.identities shouldBe listOf(Fixtures.identity().copy(id = 1))
            existing.hosts shouldHaveSize 2
            existing.knownHosts shouldHaveSize 1
        }

    @Test
    fun `import restores a backup on an empty device, remapping references`() =
        runTest {
            val payload = populatedExport()
            // The new phone already has a key with the same public key, under another id.
            IdentityRepository(target).insert(Fixtures.identity(alias = "spare", filler = 1))
            val identityId = IdentityRepository(target).insert(Fixtures.identity(alias = "New phone"))

            val plan = BackupRepository(target).import(payload)

            plan.issues.shouldBeEmpty()
            val restored = BackupRepository(target).existingData()
            val bastion = restored.hosts.single { it.label == "Bastion" }
            val dev = restored.hosts.single { it.label == "Dev box" }
            dev.identityId shouldBe identityId
            dev.jumpHostId shouldBe bastion.id
            dev.groupId shouldBe restored.groups.single().id
            dev.tags shouldBe setOf("x")
            restored.groups.single().defaultIdentityId shouldBe identityId
            restored.launchProfiles.single().hostId shouldBe dev.id
            restored.portForwards.single().hostId shouldBe dev.id
            restored.snippets.map { it.name } shouldBe listOf("Disk")
            restored.knownHosts shouldBe payload.knownHosts
            ComposeHistoryRepository(target).snapshot() shouldBe emptyMap()
        }

    @Test
    fun `importing the same backup twice adds nothing`() =
        runTest {
            val payload = populatedExport()
            val backup = BackupRepository(target)
            backup.import(payload)
            val before = backup.existingData()

            val again = backup.import(payload)

            again.isEmpty shouldBe true
            backup.existingData() shouldBe before
        }

    @Test
    fun `apply inserts a previewed plan`() =
        runTest {
            val payload = populatedExport()
            val backup = BackupRepository(target)
            val plan = BackupMerge.plan(backup.existingData(), payload)

            backup.apply(plan)

            backup.existingData().hosts shouldHaveSize 2
        }

    @Test
    fun `a failing import stores nothing`() =
        runTest {
            val backup = BackupRepository(target)
            val plan =
                ImportPlan(
                    groups = listOf(Group(id = -1, name = "Work")),
                    hosts =
                        listOf(
                            Host(id = -1, label = "h", hostname = "h.example.test", username = "u", groupId = -1),
                        ),
                    launchProfiles = emptyList(),
                    snippets = listOf(Snippet(name = "s", body = "ls")),
                    // Refers to a host that does not exist: violates the foreign key after the
                    // group, host and snippet were inserted.
                    portForwards = listOf(PortForward(hostId = 77, type = PortForwardType.DYNAMIC, bindPort = 1)),
                    knownHosts = emptyList(),
                    issues = emptyList(),
                )

            shouldThrow<SQLiteConstraintException> { backup.apply(plan) }

            backup.existingData() shouldBe ExistingData()
        }

    @Test
    fun `imported host keys never replace trusted ones, whatever the host spelling`() =
        runTest {
            val trusted = Fixtures.knownHost(host = "::1", filler = 1)
            KnownHostRepository(target).trust(trusted)
            val payload =
                BackupPayload(
                    createdAtEpochMs = 1,
                    appVersion = "1",
                    knownHosts =
                        listOf(
                            Fixtures.knownHost(host = "[::1]", filler = 2),
                            Fixtures.knownHost(host = "[::1]", keyType = "ssh-rsa", filler = 3),
                        ),
                )

            BackupRepository(target).import(payload)

            KnownHostRepository(target).find("::1", 22) shouldBe
                listOf(trusted, Fixtures.knownHost(host = "::1", keyType = "ssh-rsa", filler = 3))
        }

    @Test
    fun `import reports what it could not restore`() =
        runTest {
            val payload = populatedExport()

            val plan = BackupRepository(target).import(payload)

            plan.issues.map { it::class } shouldContain ImportIssue.MissingIdentity::class
            BackupRepository(target)
                .existingData()
                .hosts
                .single { it.label == "Dev box" }
                .identityId shouldBe null
        }
}
