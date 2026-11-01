package com.kitefoldlabs.bridle.core.ssh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SshConfigParserTest {
    @Test
    fun `parses hosts, defaults and jumps`() {
        val text =
            """
            # personal machines
            Host devbox dev
                HostName devbox.tail1234.ts.net
                User nate
                IdentityFile ~/.ssh/id_ed25519

            Host gpu
              HostName=10.0.0.5
              Port 2222
              ProxyJump bastion,dev
              User "ml user"

            Host bastion
              HostName bastion.example.com
              ProxyJump none

            Host *
              User fallback
              Port 22
              IdentityFile ~/.ssh/id_rsa
            """.trimIndent()

        val result = SshConfigParser.parse(text)
        result.warnings shouldBe emptyList()
        result.entries.map { it.alias } shouldContainExactly listOf("devbox", "dev", "gpu", "bastion")

        val devbox = result.entries[0]
        devbox.hostname shouldBe "devbox.tail1234.ts.net"
        devbox.user shouldBe "nate"
        devbox.port shouldBe 22
        devbox.identityFiles shouldBe listOf("~/.ssh/id_ed25519", "~/.ssh/id_rsa")
        result.entries[1].hostname shouldBe "devbox.tail1234.ts.net"

        val gpu = result.entries[2]
        gpu.hostname shouldBe "10.0.0.5"
        gpu.port shouldBe 2222
        gpu.user shouldBe "ml user"
        gpu.proxyJump shouldBe listOf("bastion", "dev")

        val bastion = result.entries[3]
        bastion.user shouldBe "fallback"
        bastion.proxyJump shouldBe emptyList()
    }

    @Test
    fun `defaults before a host do not override later specific values except by first-wins`() {
        val result =
            SshConfigParser.parse(
                """
                Host *
                  User early
                Host a
                  User late
                """.trimIndent(),
            )
        // OpenSSH semantics: the first obtained value wins, so the earlier `Host *` applies.
        result.entries.single().user shouldBe "early"
    }

    @Test
    fun `unsupported constructs become warnings`() {
        val result =
            SshConfigParser.parse(
                """
                User orphan
                Include ~/.ssh/config.d/*
                Host web-* !bad ok
                  ProxyCommand nc %h %p
                  Port 99999
                  ForwardAgent yes
                Match host foo
                  User ignored
                Host ok2
                  HostName ok2.example

                garbage-line-without-value
                """.trimIndent(),
            )
        result.entries.map { it.alias } shouldContainExactly listOf("ok", "ok2")
        result.entries[0].port shouldBe 22
        val w = result.warnings.joinToString("\n")
        w shouldContain "outside a Host block"
        w shouldContain "Include is not supported"
        w shouldContain "'web-*' is a wildcard"
        w shouldContain "'!bad' is a wildcard"
        w shouldContain "ProxyCommand is not supported"
        w shouldContain "invalid Port '99999'"
        w shouldContain "Match blocks"
        SshConfigParser.splitDirective("   ") shouldBe null
        SshConfigParser.splitDirective("# c") shouldBe null
        SshConfigParser.splitDirective("Host") shouldBe null
        SshConfigParser.splitDirective("Port=22") shouldBe ("port" to "22")
    }

    @Test
    fun `negated patterns exclude a host from a defaults block`() {
        // The common "everything goes through the bastion except the bastion itself" idiom.
        val result =
            SshConfigParser.parse(
                """
                Host * !bastion
                  ProxyJump bastion
                  User me
                Host bastion
                  HostName bastion.example.com
                Host app
                  HostName app.internal
                """.trimIndent(),
            )
        val (bastion, app) = result.entries
        bastion.alias shouldBe "bastion"
        bastion.proxyJump shouldBe emptyList()
        bastion.user shouldBe null
        app.proxyJump shouldBe listOf("bastion")
        app.user shouldBe "me"
    }

    @Test
    fun `input size is bounded`() {
        shouldThrow<IllegalArgumentException> { SshConfigParser.parse("#".repeat(1_000_001)) }
    }
}
