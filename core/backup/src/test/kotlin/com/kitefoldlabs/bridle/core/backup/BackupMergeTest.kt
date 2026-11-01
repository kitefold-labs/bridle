package com.kitefoldlabs.bridle.core.backup

import com.kitefoldlabs.bridle.core.backup.Fixtures.identity
import com.kitefoldlabs.bridle.core.backup.Fixtures.knownHost
import com.kitefoldlabs.bridle.core.backup.ImportIssue.Duplicate
import com.kitefoldlabs.bridle.core.backup.ImportIssue.Invalid
import com.kitefoldlabs.bridle.core.backup.ImportIssue.KnownHostKeyConflict
import com.kitefoldlabs.bridle.core.backup.ImportIssue.MissingIdentity
import com.kitefoldlabs.bridle.core.backup.ImportIssue.Orphaned
import com.kitefoldlabs.bridle.core.backup.ImportIssue.ReferenceCleared
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.model.KnownHost
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.core.model.Snippet
import com.kitefoldlabs.bridle.core.model.Validation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Base64

class BackupMergeTest {
    private fun backup(
        hosts: List<Host> = emptyList(),
        groups: List<Group> = emptyList(),
        identities: List<IdentityMetadata> = emptyList(),
        launchProfiles: List<LaunchProfile> = emptyList(),
        snippets: List<Snippet> = emptyList(),
        portForwards: List<PortForward> = emptyList(),
        knownHosts: List<KnownHost> = emptyList(),
    ) = BackupPayload(
        createdAtEpochMs = 1,
        appVersion = "1",
        hosts = hosts,
        groups = groups,
        identities = identities,
        launchProfiles = launchProfiles,
        snippets = snippets,
        portForwards = portForwards,
        knownHosts = knownHosts,
    )

    private fun host(
        id: Long,
        name: String = "h$id",
        jump: Long? = null,
        group: Long? = null,
        identity: Long? = null,
        username: String = "dev",
    ) = Host(
        id = id,
        label = name,
        hostname = "$name.example.test",
        username = username,
        jumpHostId = jump,
        groupId = group,
        identityId = identity,
    )

    private fun existingIdentity(
        id: Long,
        filler: Int,
    ) = Identity(
        id = id,
        alias = "local$id",
        source = KeySource.IMPORTED,
        algorithm = KeyAlgorithm.ED25519,
        publicKeyOpenSsh = Fixtures.publicKeyLine(filler, comment = "other-comment"),
    )

    @Test
    fun `fresh device imports everything with provisional ids and reports missing keys`() {
        val plan = BackupMerge.plan(ExistingData(), Fixtures.samplePayload())
        plan.groups shouldContainExactly listOf(Group(id = -1, name = "Work", defaultIdentityId = null))
        plan.hosts.map { it.id } shouldContainExactly listOf(-1L, -2L)
        plan.hosts[0].identityId.shouldBeNull()
        plan.hosts[1].groupId shouldBe -1L
        plan.launchProfiles.single().hostId shouldBe -1L
        plan.launchProfiles.single().id shouldBe 0L
        plan.portForwards.single().hostId shouldBe -1L
        plan.snippets.single().id shouldBe 0L
        plan.knownHosts shouldContainExactly listOf(knownHost())
        plan.issues shouldContainExactly
            listOf(
                MissingIdentity(
                    label = "Pixel key",
                    algorithm = KeyAlgorithm.ED25519,
                    source = KeySource.KEYSTORE,
                    publicKeyOpenSsh = Fixtures.publicKeyLine(9),
                    usedBy = listOf("Dev box", "Work"),
                ),
            )
        plan.issues.single().kind shouldBe EntityKind.IDENTITY
        plan.isEmpty shouldBe false
    }

    @Test
    fun `identities are matched by public key, ignoring the comment`() {
        val existing = ExistingData(identities = listOf(existingIdentity(40, filler = 9)))
        val plan = BackupMerge.plan(existing, Fixtures.samplePayload())
        plan.hosts[0].identityId shouldBe 40L
        plan.groups.single().defaultIdentityId shouldBe 40L
        plan.issues.shouldBeEmpty()
    }

    @Test
    fun `repeated backup ids are resolved by their first occurrence`() {
        val plan =
            BackupMerge.plan(
                ExistingData(identities = listOf(existingIdentity(40, filler = 9))),
                backup(
                    identities =
                        listOf(identity(7, filler = 9), identity(7, filler = 1), identity(8, 2), identity(8, 2)),
                    hosts = listOf(host(1, identity = 7), host(2, identity = 8)),
                    groups =
                        listOf(
                            Group(id = 1, name = "No key"),
                            Group(id = 2, name = "Keyed", defaultIdentityId = 8),
                            Group(id = 3, name = "Matched", defaultIdentityId = 7),
                        ),
                ),
            )
        plan.hosts.map { it.identityId } shouldBe listOf(40L, null)
        plan.issues.filterIsInstance<MissingIdentity>().map { it.usedBy } shouldBe listOf(listOf("h2", "Keyed"))
    }

    @Test
    fun `dangling optional references are cleared and reported`() {
        val plan =
            BackupMerge.plan(
                ExistingData(),
                backup(
                    hosts = listOf(host(1, jump = 99, group = 98, identity = 97)),
                    groups = listOf(Group(id = 5, name = "G", defaultIdentityId = 96)),
                ),
            )
        plan.hosts.single().let {
            it.jumpHostId.shouldBeNull()
            it.groupId.shouldBeNull()
            it.identityId.shouldBeNull()
        }
        plan.groups
            .single()
            .defaultIdentityId
            .shouldBeNull()
        plan.issues shouldContainExactlyInAnyOrder
            listOf(
                ReferenceCleared(EntityKind.GROUP, "G", "defaultIdentityId"),
                ReferenceCleared(EntityKind.HOST, "h1", "identityId"),
                ReferenceCleared(EntityKind.HOST, "h1", "groupId"),
                ReferenceCleared(EntityKind.HOST, "h1", "jumpHostId"),
            )
    }

    @Test
    fun `groups match by name ignoring case, and invalid groups are dropped`() {
        val plan =
            BackupMerge.plan(
                ExistingData(groups = listOf(Group(id = 10, name = "Work"))),
                backup(
                    groups =
                        listOf(
                            Group(id = 1, name = " work "),
                            Group(id = 2, name = "Home"),
                            Group(id = 3, name = "HOME"),
                            Group(id = 4, name = "  "),
                        ),
                    hosts = listOf(host(1, group = 1), host(2, group = 3), host(3, group = 4)),
                ),
            )
        plan.groups shouldContainExactly listOf(Group(id = -1, name = "Home"))
        plan.hosts.map { it.groupId } shouldBe listOf(10L, -1L, null)
        plan.issues shouldContainExactly
            listOf(
                Duplicate(EntityKind.GROUP, " work "),
                Duplicate(EntityKind.GROUP, "HOME"),
                Invalid(EntityKind.GROUP, "  ", Validation.Invalid("label", Validation.Reason.BLANK)),
                ReferenceCleared(EntityKind.HOST, "h3", "groupId"),
            )
    }

    @Test
    fun `group default usernames are validated like user input`() {
        val plan =
            BackupMerge.plan(
                ExistingData(),
                backup(
                    groups =
                        listOf(
                            Group(id = 1, name = "Ok", defaultUsername = "deploy"),
                            Group(id = 2, name = "Bad", defaultUsername = "root;reboot"),
                        ),
                ),
            )
        plan.groups.map { it.name } shouldBe listOf("Ok")
        plan.issues shouldContainExactly
            listOf(
                Invalid(
                    EntityKind.GROUP,
                    "Bad",
                    Validation.Invalid("username", Validation.Reason.ILLEGAL_CHARACTERS),
                ),
            )
    }

    @Test
    fun `identities without a key never match each other`() {
        val blank = existingIdentity(40, filler = 9).copy(publicKeyOpenSsh = "  ")
        val plan =
            BackupMerge.plan(
                ExistingData(identities = listOf(blank)),
                backup(
                    identities = listOf(identity(7, filler = 9).copy(publicKeyOpenSsh = "")),
                    hosts = listOf(host(1, identity = 7)),
                ),
            )
        plan.hosts
            .single()
            .identityId
            .shouldBeNull()
        plan.issues.single().kind shouldBe EntityKind.IDENTITY
    }

    @Test
    fun `duplicate hosts keep the existing host and attach children to it`() {
        val existingHost = Host(id = 50, label = "Mine", hostname = "H1.Example.Test", username = "dev")
        val existing =
            ExistingData(
                hosts = listOf(existingHost),
                launchProfiles = listOf(LaunchProfile.claude(50, "~/a", "claude-a")),
                portForwards = listOf(PortForward(hostId = 50, type = PortForwardType.DYNAMIC, bindPort = 1080)),
            )
        val plan =
            BackupMerge.plan(
                existing,
                backup(
                    hosts =
                        listOf(
                            host(1),
                            host(2, name = "h1"),
                            host(3, jump = 1),
                            host(4, username = "other", name = "h1"),
                        ),
                    launchProfiles =
                        listOf(
                            LaunchProfile.claude(1, "~/a", "claude-a"),
                            LaunchProfile.claude(2, "~/b", "claude-b"),
                            LaunchProfile.claude(1, "~/c", "claude-b"),
                        ),
                    portForwards =
                        listOf(
                            PortForward(id = 1, hostId = 1, type = PortForwardType.DYNAMIC, bindPort = 1080),
                            PortForward(2, 2, PortForwardType.LOCAL, 5432, destHost = "db", destPort = 5432),
                            PortForward(3, 1, PortForwardType.LOCAL, 5432, destHost = "DB", destPort = 5432),
                        ),
                ),
            )
        plan.hosts.map { it.label to it.jumpHostId } shouldBe listOf("h3" to 50L, "h1" to null)
        plan.launchProfiles.map { it.hostId to it.sessionName } shouldBe listOf(50L to "claude-b")
        plan.portForwards.map { it.hostId to it.bindPort } shouldBe listOf(50L to 5432)
        plan.portForwards.single().id shouldBe 0L
        plan.issues shouldContainExactly
            listOf(
                Duplicate(EntityKind.HOST, "h1"),
                Duplicate(EntityKind.HOST, "h1"),
                Duplicate(EntityKind.LAUNCH_PROFILE, "Claude · a"),
                Duplicate(EntityKind.LAUNCH_PROFILE, "Claude · c"),
                Duplicate(EntityKind.PORT_FORWARD, "dynamic :1080"),
                Duplicate(EntityKind.PORT_FORWARD, "local :5432"),
            )
    }

    @Test
    fun `duplicates inside the backup resolve to the first copy`() {
        val hosts = listOf(host(1), host(2, name = "h1"), host(3, jump = 2))
        val plan = BackupMerge.plan(ExistingData(), backup(hosts = hosts))
        plan.hosts.map { it.id to it.jumpHostId } shouldBe listOf(-1L to null, -2L to -1L)
        plan.issues shouldContainExactly listOf(Duplicate(EntityKind.HOST, "h1"))
    }

    @Test
    fun `jump hosts are inserted before the hosts that use them`() {
        val hosts = listOf(host(1, jump = 2), host(2, jump = 3), host(3))
        val plan = BackupMerge.plan(ExistingData(), backup(hosts = hosts))
        plan.hosts.map { it.label to it.jumpHostId } shouldBe listOf("h3" to null, "h2" to -3L, "h1" to -2L)
        plan.issues.shouldBeEmpty()
    }

    @Test
    fun `jump host cycles are broken and reported`() {
        val plan =
            BackupMerge.plan(
                ExistingData(),
                backup(hosts = listOf(host(1, jump = 2), host(2, jump = 1), host(3, jump = 3), host(4, jump = 1))),
            )
        plan.hosts.map { it.label to it.jumpHostId } shouldBe
            listOf("h1" to null, "h2" to -1L, "h4" to -1L, "h3" to null)
        plan.issues shouldContainExactly
            listOf(
                ReferenceCleared(EntityKind.HOST, "h1", "jumpHostId"),
                ReferenceCleared(EntityKind.HOST, "h3", "jumpHostId"),
            )
    }

    @Test
    fun `a host that only jumps into a cycle keeps its jump host`() {
        // h4 is listed first and waits on the h1 <-> h2 cycle; only a host on the cycle is cut.
        val plan =
            BackupMerge.plan(
                ExistingData(),
                backup(hosts = listOf(host(4, jump = 1), host(1, jump = 2), host(2, jump = 1))),
            )
        plan.hosts.map { it.label to it.jumpHostId } shouldBe
            listOf("h1" to null, "h4" to -2L, "h2" to -2L)
        plan.issues shouldContainExactly listOf(ReferenceCleared(EntityKind.HOST, "h1", "jumpHostId"))
    }

    @Test
    fun `invalid hosts are rejected and their children dropped`() {
        val bad = Host(id = 1, label = "Bad", hostname = "bad host;rm", username = "dev")
        val plan =
            BackupMerge.plan(
                ExistingData(),
                backup(
                    hosts = listOf(bad, host(2, jump = 1)),
                    launchProfiles = listOf(LaunchProfile.claude(1, "~/x", "claude-x")),
                    portForwards = listOf(PortForward(hostId = 1, type = PortForwardType.DYNAMIC, bindPort = 1080)),
                ),
            )
        plan.hosts.map { it.label to it.jumpHostId } shouldBe listOf("h2" to null)
        plan.launchProfiles.shouldBeEmpty()
        plan.portForwards.shouldBeEmpty()
        plan.issues shouldContainExactly
            listOf(
                Invalid(EntityKind.HOST, "Bad", Validation.Invalid("hostname", Validation.Reason.ILLEGAL_CHARACTERS)),
                ReferenceCleared(EntityKind.HOST, "h2", "jumpHostId"),
                Orphaned(EntityKind.LAUNCH_PROFILE, "Claude · x"),
                Orphaned(EntityKind.PORT_FORWARD, "dynamic :1080"),
            )
    }

    @Test
    fun `invalid profiles and port forwards are rejected`() {
        val plan =
            BackupMerge.plan(
                ExistingData(),
                backup(
                    hosts = listOf(host(1)),
                    launchProfiles = listOf(LaunchProfile.claude(1, "~/x", "bad;session")),
                    portForwards = listOf(PortForward(hostId = 1, type = PortForwardType.LOCAL, bindPort = 8080)),
                ),
            )
        plan.launchProfiles.shouldBeEmpty()
        plan.portForwards.shouldBeEmpty()
        plan.issues shouldContainExactly
            listOf(
                Invalid(
                    EntityKind.LAUNCH_PROFILE,
                    "Claude · x",
                    Validation.Invalid("sessionName", Validation.Reason.ILLEGAL_CHARACTERS),
                ),
                Invalid(
                    EntityKind.PORT_FORWARD,
                    "local :8080",
                    Validation.Invalid("hostname", Validation.Reason.BLANK),
                ),
            )
    }

    @Test
    fun `snippets match by name and body`() {
        val plan =
            BackupMerge.plan(
                ExistingData(snippets = listOf(Snippet(id = 9, name = "Disk", body = "df -h"))),
                backup(
                    snippets =
                        listOf(
                            Snippet(id = 1, name = "Disk", body = "df -h"),
                            Snippet(id = 2, name = "Disk", body = "df -i"),
                            Snippet(id = 3, name = "Disk", body = "df -i"),
                            Snippet(id = 4, name = " ", body = "ls"),
                        ),
                ),
            )
        plan.snippets shouldContainExactly listOf(Snippet(id = 0, name = "Disk", body = "df -i"))
        plan.issues shouldContainExactly
            listOf(
                Duplicate(EntityKind.SNIPPET, "Disk"),
                Duplicate(EntityKind.SNIPPET, "Disk"),
                Invalid(EntityKind.SNIPPET, " ", Validation.Invalid("name", Validation.Reason.BLANK)),
            )
    }

    @Test
    fun `a trusted host key is never replaced`() {
        val trusted = knownHost(filler = 1)
        val changed = knownHost(host = "DEVBOX.example.test", filler = 2)
        val plan =
            BackupMerge.plan(
                ExistingData(knownHosts = listOf(trusted)),
                backup(knownHosts = listOf(trusted, changed, knownHost(host = "new.example.test"))),
            )
        plan.knownHosts shouldContainExactly listOf(knownHost(host = "new.example.test"))
        val conflict =
            KnownHostKeyConflict(
                host = "devbox.example.test",
                port = 22,
                keyType = "ssh-ed25519",
                existingFingerprint = trusted.fingerprintSha256,
                backupFingerprint = changed.fingerprintSha256,
            )
        plan.issues shouldContainExactly
            listOf(Duplicate(EntityKind.KNOWN_HOST, "devbox.example.test:22 ssh-ed25519"), conflict)
        conflict.kind shouldBe EntityKind.KNOWN_HOST
        conflict.label shouldBe "devbox.example.test:22 ssh-ed25519"
    }

    @Test
    fun `a trusted host is never given a key of another type`() {
        // The TOFU verifier refuses an unknown key type for a known host, so an import must not
        // quietly make it acceptable.
        val trusted = knownHost(filler = 1)
        val ecdsa = knownHost(keyType = "ecdsa-sha2-nistp256", filler = 3)
        val otherPort = knownHost(port = 2222, keyType = "ecdsa-sha2-nistp256", filler = 4)
        val plan =
            BackupMerge.plan(
                ExistingData(knownHosts = listOf(trusted)),
                backup(knownHosts = listOf(ecdsa, otherPort)),
            )
        plan.knownHosts shouldContainExactly listOf(otherPort)
        plan.issues shouldContainExactly
            listOf(
                KnownHostKeyConflict(
                    host = "devbox.example.test",
                    port = 22,
                    keyType = "ecdsa-sha2-nistp256",
                    existingFingerprint = trusted.fingerprintSha256,
                    backupFingerprint = ecdsa.fingerprintSha256,
                ),
            )
    }

    @Test
    fun `a new host keeps every key type of the backup, and the first of conflicting keys`() {
        val ed25519 = knownHost(host = "new.example.test", filler = 1)
        val ecdsa = knownHost(host = "new.example.test", keyType = "ecdsa-sha2-nistp256", filler = 3)
        val ecdsaChanged = knownHost(host = "new.example.test", keyType = "ecdsa-sha2-nistp256", filler = 4)
        val plan = BackupMerge.plan(ExistingData(), backup(knownHosts = listOf(ed25519, ecdsa, ecdsaChanged)))
        plan.knownHosts shouldContainExactly listOf(ed25519, ecdsa)
        plan.issues shouldContainExactly
            listOf(
                KnownHostKeyConflict(
                    host = "new.example.test",
                    port = 22,
                    keyType = "ecdsa-sha2-nistp256",
                    existingFingerprint = ecdsa.fingerprintSha256,
                    backupFingerprint = ecdsaChanged.fingerprintSha256,
                ),
            )
    }

    @Test
    fun `known hosts are checked against their own key`() {
        val good = knownHost()

        fun shortBlob(bytes: ByteArray) = good.copy(publicKeyBase64 = Base64.getEncoder().encodeToString(bytes))
        val cases =
            listOf(
                good.copy(publicKeyBase64 = "not base64!") to "publicKeyBase64",
                good.copy(keyType = "ssh-rsa") to "keyType",
                shortBlob(byteArrayOf(0, 0)) to "keyType",
                shortBlob(byteArrayOf(0, 0, 0, 9, 65)) to "keyType",
                shortBlob(byteArrayOf(-1, -1, -1, -1, 65)) to "keyType",
                good.copy(fingerprintSha256 = "SHA256:forged") to "fingerprintSha256",
                good.copy(host = "bad host") to "hostname",
                good.copy(port = 0) to "port",
            )
        val plan = BackupMerge.plan(ExistingData(), backup(knownHosts = cases.map { it.first }))
        plan.knownHosts.shouldBeEmpty()
        plan.issues.map { (it as Invalid).problem.field } shouldBe cases.map { it.second }
        plan.issues.forEach { it.kind shouldBe EntityKind.KNOWN_HOST }
        KnownHostCheck.blobType(Fixtures.keyBlob("ssh-ed25519", 0)) shouldBe "ssh-ed25519"
    }

    @Test
    fun `apply inserts in dependency order and resolves provisional ids`() {
        val sink = RecordingSink()
        val mine = Host(id = 50, label = "Mine", hostname = "h9.example.test", username = "dev")
        val existing = ExistingData(hosts = listOf(mine))
        val plan =
            BackupMerge.plan(
                existing,
                backup(
                    groups = listOf(Group(id = 1, name = "G")),
                    hosts = listOf(host(1, jump = 2, group = 1), host(2, jump = 9), host(9)),
                    launchProfiles = listOf(LaunchProfile.claude(1, "~/a", "a"), LaunchProfile.claude(9, "~/b", "b")),
                    portForwards = listOf(PortForward(hostId = 2, type = PortForwardType.DYNAMIC, bindPort = 1080)),
                    snippets = listOf(Snippet(name = "s", body = "b")),
                    knownHosts = listOf(knownHost()),
                ),
            )
        plan.apply(sink)
        sink.groups shouldContainExactly listOf(Group(id = 0, name = "G"))
        sink.hosts.map { Triple(it.label, it.jumpHostId, it.groupId) } shouldBe
            listOf(Triple("h2", 50L, null), Triple("h1", 101L, 1001L))
        sink.hosts.forEach { it.id shouldBe 0L }
        sink.profiles.map { it.hostId } shouldBe listOf(102L, 50L)
        sink.forwards.map { it.hostId } shouldBe listOf(101L)
        sink.snippets shouldHaveSize 1
        sink.knownHosts shouldHaveSize 1
    }

    @Test
    fun `an empty backup plans nothing`() {
        val plan = BackupMerge.plan(ExistingData(), backup())
        plan.isEmpty shouldBe true
        listOf(
            plan.copy(groups = listOf(Group(name = "g"))),
            plan.copy(hosts = listOf(host(1))),
            plan.copy(launchProfiles = listOf(LaunchProfile.claude(1, "~", "s"))),
            plan.copy(snippets = listOf(Snippet(name = "s", body = "b"))),
            plan.copy(portForwards = listOf(PortForward(hostId = 1, type = PortForwardType.DYNAMIC, bindPort = 1))),
            plan.copy(knownHosts = listOf(knownHost())),
        ).forEach { it.isEmpty shouldBe false }
        ImportPlan.isProvisional(-1) shouldBe true
        ImportPlan.isProvisional(0) shouldBe false
    }

    @Test
    fun `plans only ever reference existing rows or entities of the plan`() =
        runBlocking<Unit> {
            val ref = Arb.long(0L..8L).orNull(0.3)
            val existingHosts = listOf(Host(id = 100, label = "E", hostname = "h1.example.test", username = "dev"))
            checkAll(
                200,
                Arb.list(Arb.int(0..7), 0..8),
                Arb.list(ref, 8..8),
                Arb.list(ref, 8..8),
                Arb.list(Arb.long(0L..8L), 0..6),
            ) { hostNames, jumps, groups, profileHosts ->
                val hosts =
                    hostNames.mapIndexed { i, n -> host(i.toLong(), name = "h$n", jump = jumps[i], group = groups[i]) }
                val plan =
                    BackupMerge.plan(
                        ExistingData(hosts = existingHosts, groups = listOf(Group(id = 200, name = "g0"))),
                        backup(
                            hosts = hosts,
                            groups = (0..3).map { Group(id = it.toLong(), name = "g${it % 2}") },
                            launchProfiles = profileHosts.map { LaunchProfile.claude(it, "~/$it", "s$it") },
                        ),
                    )
                val existingIds = setOf(100L)
                val placed = mutableSetOf<EntityId>()
                plan.hosts.forEach { h ->
                    h.jumpHostId?.let { (it in existingIds || it in placed) shouldBe true }
                    h.groupId?.let { (it == 200L || it in plan.groups.map(Group::id)) shouldBe true }
                    placed += h.id
                }
                plan.launchProfiles.forEach { (it.hostId in existingIds || it.hostId in placed) shouldBe true }
                plan.apply(RecordingSink())
                plan.hosts.map { it.id }.toSet() shouldHaveSize plan.hosts.size
                if (plan.hosts.isNotEmpty()) plan.hosts.map { it.id } shouldContain -1L
            }
        }

    private class RecordingSink : ImportSink {
        val groups = mutableListOf<Group>()
        val hosts = mutableListOf<Host>()
        val profiles = mutableListOf<LaunchProfile>()
        val snippets = mutableListOf<Snippet>()
        val forwards = mutableListOf<PortForward>()
        val knownHosts = mutableListOf<KnownHost>()

        override fun insertGroup(group: Group): EntityId {
            groups += group
            return 1000L + groups.size
        }

        override fun insertHost(host: Host): EntityId {
            hosts += host
            return 100L + hosts.size
        }

        override fun insertLaunchProfile(profile: LaunchProfile) {
            profiles += profile
        }

        override fun insertSnippet(snippet: Snippet) {
            snippets += snippet
        }

        override fun insertPortForward(portForward: PortForward) {
            forwards += portForward
        }

        override fun insertKnownHost(knownHost: KnownHost) {
            knownHosts += knownHost
        }
    }
}
