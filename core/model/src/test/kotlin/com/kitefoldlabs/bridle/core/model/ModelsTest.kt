package com.kitefoldlabs.bridle.core.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class ModelsTest {
    private val host = Host(label = "Dev", hostname = "devbox.ts.net", username = "nate")

    @Test
    fun `host validation passes for a sane host`() {
        host.validate() shouldBe Validation.Valid
        host.copy(defaultTmuxSession = "main", env = mapOf("TERM_PROGRAM" to "bridle")).validate() shouldBe
            Validation.Valid
    }

    @Test
    fun `host validation reports each field`() {
        fun field(h: Host) = h.validate().shouldBeInstanceOf<Validation.Invalid>().field
        field(host.copy(label = "")) shouldBe "label"
        field(host.copy(hostname = "bad host")) shouldBe "hostname"
        field(host.copy(port = 0)) shouldBe "port"
        field(host.copy(username = "")) shouldBe "username"
        field(host.copy(defaultTmuxSession = "a:b")) shouldBe "sessionName"
        field(host.copy(env = mapOf("1X" to ""))) shouldBe "env"
        field(host.copy(keepAliveSeconds = -1)) shouldBe "keepAliveSeconds"
        field(host.copy(keepAliveSeconds = 3_601)) shouldBe "keepAliveSeconds"
    }

    @Test
    fun `display target`() {
        host.displayTarget shouldBe "nate@devbox.ts.net"
        host.copy(port = 2222).displayTarget shouldBe "nate@devbox.ts.net:2222"
        host.copy(hostname = "::1").displayTarget shouldBe "nate@[::1]"
        host.copy(hostname = "[::1]", port = 2200).displayTarget shouldBe "nate@[::1]:2200"
    }

    @Test
    fun `key algorithm lookup`() {
        KeyAlgorithm.fromSshName("ssh-ed25519") shouldBe KeyAlgorithm.ED25519
        KeyAlgorithm.fromSshName("ecdsa-sha2-nistp256") shouldBe KeyAlgorithm.ECDSA_P256
        KeyAlgorithm.fromSshName("ssh-dss") shouldBe null
    }

    @Test
    fun `identity invariants`() {
        shouldThrow<IllegalArgumentException> {
            Identity(
                alias = "a",
                source = KeySource.KEYSTORE,
                algorithm = KeyAlgorithm.ECDSA_P256,
                publicKeyOpenSsh = "x",
            )
        }
        shouldThrow<IllegalArgumentException> {
            Identity(
                alias = "a",
                source = KeySource.IMPORTED,
                algorithm = KeyAlgorithm.ED25519,
                publicKeyOpenSsh = "x",
                authValiditySeconds = -1,
            )
        }
        Identity(
            alias = "phone",
            source = KeySource.KEYSTORE,
            algorithm = KeyAlgorithm.ECDSA_P256,
            publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAA phone",
            keystoreAlias = "bridle-key-1",
        ).alias shouldBe "phone"
    }

    @Test
    fun `launch profile claude preset and restart command`() {
        val p = LaunchProfile.claude(hostId = 1, workingDir = "~/src/bridle/", sessionName = "bridle")
        p.name shouldBe "Claude · bridle"
        p.command shouldBe "claude"
        p.effectiveRestartCommand shouldBe "claude --continue"
        p.validate() shouldBe Validation.Valid
        LaunchProfile.claude(1, "/", "root").name shouldBe "Claude · ~"
        p.copy(restartCommand = " ").effectiveRestartCommand shouldBe "claude"
        p.copy(restartCommand = null).effectiveRestartCommand shouldBe "claude"
        p.copy(restartMode = RestartMode.RESPAWN_FRESH).effectiveRestartCommand shouldBe "claude"
        p.copy(restartMode = RestartMode.SEND_KEYS).effectiveRestartCommand shouldBe "claude"
    }

    @Test
    fun `launch profile validation`() {
        val p = LaunchProfile.claude(1, "~/src", "s")

        fun field(x: LaunchProfile) = x.validate().shouldBeInstanceOf<Validation.Invalid>().field
        field(p.copy(name = "")) shouldBe "label"
        field(p.copy(sessionName = "a b")) shouldBe "sessionName"
        field(p.copy(workingDir = " ")) shouldBe "workingDir"
        field(p.copy(command = "")) shouldBe "command"
        field(p.copy(env = mapOf("bad-name" to "1"))) shouldBe "env"
    }

    @Test
    fun `port forward validation`() {
        PortForward(hostId = 1, type = PortForwardType.DYNAMIC, bindPort = 1080).validate() shouldBe Validation.Valid
        PortForward(hostId = 1, type = PortForwardType.LOCAL, bindPort = 8080, destHost = "localhost", destPort = 3000)
            .validate() shouldBe Validation.Valid
        PortForward(hostId = 1, type = PortForwardType.LOCAL, bindPort = 8080)
            .validate()
            .shouldBeInstanceOf<Validation.Invalid>()
            .field shouldBe "hostname"
        PortForward(hostId = 1, type = PortForwardType.LOCAL, bindPort = 8080, destHost = "localhost")
            .validate()
            .shouldBeInstanceOf<Validation.Invalid>()
            .field shouldBe "port"
        PortForward(hostId = 1, type = PortForwardType.DYNAMIC, bindPort = 0)
            .validate()
            .shouldBeInstanceOf<Validation.Invalid>()
    }

    @Test
    fun `models round trip through json`() {
        val json = Json
        val kh = KnownHost("h", 22, "ssh-ed25519", "AAAA", "SHA256:abc")
        json.decodeFromString(KnownHost.serializer(), json.encodeToString(KnownHost.serializer(), kh)) shouldBe kh
        val g = Group(name = "Home")
        json.decodeFromString(Group.serializer(), json.encodeToString(Group.serializer(), g)) shouldBe g
        json.decodeFromString(Host.serializer(), json.encodeToString(Host.serializer(), host)) shouldBe host
    }
}
