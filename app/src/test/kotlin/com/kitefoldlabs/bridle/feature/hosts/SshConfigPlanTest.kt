package com.kitefoldlabs.bridle.feature.hosts

import android.app.Application
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.ssh.SshConfigParser
import com.kitefoldlabs.bridle.feature.TestEnv
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SshConfigPlanTest {
    private val env = TestEnv()

    @After
    fun tearDown() = env.close()

    private val config =
        """
        Host *
          User nate

        Host bastion
          HostName bastion.example.com

        Host gpu
          HostName 10.0.0.5
          ProxyJump bastion
          IdentityFile ~/.ssh/id_ed25519

        Host lab
          HostName lab.internal
          ProxyJump ops@jump1:2222,jump2

        Host lab2
          HostName lab2.internal
          ProxyJump ops@jump1:2222,jump2

        Host lab3
          HostName lab3.internal
          ProxyJump jump3,jump2

        Host old
          HostName old.example.com

        Host viaold
          HostName x.internal
          ProxyJump old

        Host j1
          HostName j1.example
        Host j2
          HostName j2.example
          ProxyJump j1
        Host chained
          HostName t.example
          ProxyJump j1,j2
        Host reversed
          HostName t2.example
          ProxyJump j2,j1
        Host afterold
          HostName t3.example
          ProxyJump j1,old

        Host a
          HostName a.example
          ProxyJump b
        Host b
          HostName b.example
          ProxyJump a

        Host broken
          HostName b.example
          ProxyJump bad_host!,h:notaport
        Host v6
          HostName v6.example
          ProxyJump [fe80::1]:2200
        Host badname
          HostName -nope-
        Host broken2
          HostName b2.example
          ProxyJump bad_host!,j1
        """.trimIndent()

    private val existing = listOf(Host(id = 77, label = "old", hostname = "old.example.com", username = "nate"))

    private fun plan(
        text: String = config,
        defaultUser: String = "",
    ) = ImportPlan.of(SshConfigParser.parse(text), existing, defaultUser)

    private fun ImportPlan.host(key: String) = hosts.single { it.key == key }

    @Test
    fun `entries become planned hosts with notes`() {
        val plan = plan()
        plan.host("bastion").notes shouldBe emptyList()
        plan.host("bastion").host shouldBe Host(label = "bastion", hostname = "bastion.example.com", username = "nate")
        plan.host("gpu").notes shouldContainExactly listOf(ImportNote.IDENTITY_FILE_IGNORED)
        plan.host("gpu").jump shouldBe JumpRef.Planned("bastion")
        plan.host("old").notes shouldContainExactly listOf(ImportNote.EXISTS)
        plan.host("old").importable shouldBe false
        plan.host("viaold").jump shouldBe JumpRef.Existing(77)
        plan.host("badname").notes shouldContainExactly listOf(ImportNote.INVALID)
        plan.selectable.map { it.key } shouldBe
            plan.hosts.filter { it.fromConfig && it.key !in setOf("old", "badname") }.map { it.key }
    }

    @Test
    fun `missing usernames come from the default or block the entry`() {
        val text =
            "Host solo\n  HostName solo.example\nHost via\n  HostName via.example\n  User me\n  ProxyJump solo\n" +
                "Host loner\n  HostName l.example\n  ProxyJump hop.example\n"
        val blocked = plan(text)
        blocked.host("solo").notes shouldContainExactly listOf(ImportNote.NO_USERNAME)
        blocked.host("via").jump shouldBe null
        blocked.host("via").notes shouldContainExactly listOf(ImportNote.JUMP_UNRESOLVED)
        val fixed = plan(text, defaultUser = " admin ")
        fixed.host("solo").host.username shouldBe "admin"
        fixed.host("via").jump shouldBe JumpRef.Planned("solo")
        fixed.host("hop.example").host.username shouldBe "admin"
    }

    @Test
    fun `proxyjump chains`() {
        val plan = plan()
        // user@host:port hops become hosts of their own, chained in order.
        plan.host("lab").jump shouldBe JumpRef.Planned("jump2")
        plan.host("jump2").jump shouldBe JumpRef.Planned("ops@jump1:2222")
        plan.host("jump2").fromConfig shouldBe false
        plan.host("ops@jump1:2222").host shouldBe
            Host(label = "ops@jump1:2222", hostname = "jump1", port = 2222, username = "ops")
        plan.host("lab").notes shouldBe emptyList()
        plan.host("lab2").notes shouldBe emptyList()
        plan.host("lab3").notes shouldContainExactly listOf(ImportNote.JUMP_CHAIN_SHORTENED)
        // Config entries keep their own ProxyJump; a chain that agrees with it is fine.
        plan.host("chained").jump shouldBe JumpRef.Planned("j2")
        plan.host("chained").notes shouldBe emptyList()
        plan.host("reversed").notes shouldContainExactly listOf(ImportNote.JUMP_CHAIN_SHORTENED)
        plan.host("afterold").notes shouldContainExactly listOf(ImportNote.JUMP_CHAIN_SHORTENED)
        plan.host("afterold").jump shouldBe JumpRef.Existing(77)
        // Loops are broken.
        plan.host("a").jump shouldBe null
        plan.host("a").notes shouldContainExactly listOf(ImportNote.JUMP_LOOP)
        plan.host("b").jump shouldBe JumpRef.Planned("a")
        // Unusable hops.
        plan.host("broken").jump shouldBe null
        plan.host("broken").notes shouldContainExactly listOf(ImportNote.JUMP_UNRESOLVED)
        plan.host("[fe80::1]:2200").host.hostname shouldBe "fe80::1"
        plan.host("[fe80::1]:2200").host.port shouldBe 2200
        plan.host("v6").jump shouldBe JumpRef.Planned("[fe80::1]:2200")
        plan.host("broken2").jump shouldBe JumpRef.Planned("j1")
        plan.host("broken2").notes shouldContainExactly listOf(ImportNote.JUMP_UNRESOLVED)
    }

    @Test
    fun `execute saves the selection with the jump hosts it needs`() =
        runBlocking<Unit> {
            val oldId = env.data.hosts.upsert(existing.single().copy(id = 0))
            val plan =
                ImportPlan.of(
                    SshConfigParser.parse(config),
                    env.data.hosts
                        .observeAll()
                        .first(),
                    "",
                )
            plan.execute(setOf("gpu", "bastion", "lab", "viaold", "old", "badname"), env.data.hosts) shouldBe 6
            val saved =
                env.data.hosts
                    .observeAll()
                    .first()
                    .associateBy { it.label }
            saved.getValue("gpu").jumpHostId shouldBe saved.getValue("bastion").id
            saved.getValue("lab").jumpHostId shouldBe saved.getValue("jump2").id
            saved.getValue("jump2").jumpHostId shouldBe saved.getValue("ops@jump1:2222").id
            saved.getValue("viaold").jumpHostId shouldBe oldId
            saved.getValue("bastion").jumpHostId shouldBe null
            saved.keys shouldBe setOf("old", "gpu", "bastion", "lab", "jump2", "ops@jump1:2222", "viaold")
        }
}
