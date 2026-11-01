package com.kitefoldlabs.bridle.feature.hosts

import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Validation
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Test

class HostFormTest {
    private val valid = HostForm(label = "Dev box", hostname = "devbox.tail1234.ts.net", username = "nate")

    @Test
    fun `a filled-in form has no errors and becomes a host`() {
        valid.errors().shouldBeEmpty()
        val host =
            valid
                .copy(
                    port = " 2222 ",
                    tags = "work, gpu,, ",
                    env = "FOO=bar\n\nBAZ = qux=1",
                    tmuxSession = " main ",
                    startupCommand = "  ",
                    keepAlive = "0",
                    favorite = true,
                    color = 7,
                    identityId = 3,
                    jumpHostId = 4,
                    groupId = 5,
                ).toHost(null)
        host shouldBe
            Host(
                label = "Dev box",
                hostname = "devbox.tail1234.ts.net",
                port = 2222,
                username = "nate",
                identityId = 3,
                jumpHostId = 4,
                groupId = 5,
                tags = setOf("work", "gpu"),
                color = 7,
                favorite = true,
                keepAliveSeconds = 0,
                defaultTmuxSession = "main",
                startupCommand = null,
                env = mapOf("FOO" to "bar", "BAZ" to " qux=1"),
            )
    }

    @Test
    fun `editing keeps the id and history and round-trips every field`() {
        val base =
            Host(
                id = 9,
                label = "GPU",
                hostname = "10.0.0.2",
                port = 2200,
                username = "ml",
                tags = setOf("b", "a"),
                env = mapOf("A" to "1", "B" to "2"),
                defaultTmuxSession = "claude",
                startupCommand = "cd ~/src",
                lastConnectedAtEpochMs = 42,
            )
        val form = HostForm.of(base)
        form.tags shouldBe "a, b"
        form.env shouldBe "A=1\nB=2"
        form.toHost(base) shouldBe base
    }

    @Test
    fun `each field reports its own problem`() {
        HostForm().errors() shouldBe
            mapOf(
                HostField.LABEL to Validation.Reason.BLANK,
                HostField.HOSTNAME to Validation.Reason.BLANK,
                HostField.USERNAME to Validation.Reason.BLANK,
            )
        valid.copy(port = "x").errors() shouldBe mapOf(HostField.PORT to Validation.Reason.OUT_OF_RANGE)
        valid.copy(port = "70000").errors() shouldBe mapOf(HostField.PORT to Validation.Reason.OUT_OF_RANGE)
        valid.copy(keepAlive = "-1").errors() shouldBe mapOf(HostField.KEEPALIVE to Validation.Reason.OUT_OF_RANGE)
        valid.copy(keepAlive = "").errors() shouldBe mapOf(HostField.KEEPALIVE to Validation.Reason.OUT_OF_RANGE)
        valid.copy(tmuxSession = "a:b").errors() shouldBe
            mapOf(HostField.TMUX_SESSION to Validation.Reason.ILLEGAL_CHARACTERS)
        valid.copy(env = "no equals sign").errors() shouldBe
            mapOf(HostField.ENV to Validation.Reason.ILLEGAL_CHARACTERS)
        valid.copy(env = "1BAD=x").errors() shouldBe mapOf(HostField.ENV to Validation.Reason.ILLEGAL_CHARACTERS)
        valid.copy(hostname = "bad host").errors() shouldBe
            mapOf(HostField.HOSTNAME to Validation.Reason.ILLEGAL_CHARACTERS)
        valid.copy(username = "x".repeat(40)).errors() shouldBe mapOf(HostField.USERNAME to Validation.Reason.TOO_LONG)
    }

    @Test
    fun `tag and env parsing`() {
        valid.copy(env = "bad").toHost(null).env shouldBe emptyMap()
        HostForm.parseTags(" ") shouldBe emptySet()
        HostForm.parseEnv("") shouldBe emptyMap()
        HostForm.parseEnv("=x") shouldBe null
    }
}
