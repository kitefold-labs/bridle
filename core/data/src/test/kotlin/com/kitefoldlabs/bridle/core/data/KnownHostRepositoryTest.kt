package com.kitefoldlabs.bridle.core.data

import app.cash.turbine.test
import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.ssh.HostKeyChangedException
import com.kitefoldlabs.bridle.core.ssh.HostKeyPrompter
import com.kitefoldlabs.bridle.core.ssh.TofuHostKeyVerifier
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
class KnownHostRepositoryTest {
    private val db: BridleDatabase = Fixtures.database()
    private val store = KnownHostRepository(db)

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `host names are normalised`() {
        KnownHostRepository.normalizeHost("[::1]") shouldBe "::1"
        KnownHostRepository.normalizeHost(" [FE80::1%eth0] ") shouldBe "fe80::1%eth0"
        KnownHostRepository.normalizeHost("DevBox.Example.TEST") shouldBe "devbox.example.test"
        KnownHostRepository.normalizeHost("10.0.0.1") shouldBe "10.0.0.1"
    }

    @Test
    fun `bracketed and bare IPv6 literals name the same host`() {
        store.trust(Fixtures.knownHost(host = "[::1]"))

        store.find("::1", 22).map { it.host } shouldBe listOf("::1")
        store.find("[::1]", 22) shouldHaveSize 1
        store.find("::1", 2222).shouldBeEmpty()
    }

    @Test
    fun `host names match regardless of case`() {
        store.trust(Fixtures.knownHost(host = "DevBox.example.test"))

        store.find("devbox.EXAMPLE.test", 22) shouldBe listOf(Fixtures.knownHost(host = "devbox.example.test"))
    }

    @Test
    fun `trust never overwrites a trusted key`() {
        val first = Fixtures.knownHost(filler = 1)
        store.trust(first)
        store.trust(Fixtures.knownHost(filler = 2))

        store.find(first.host, 22) shouldBe listOf(first)
    }

    @Test
    fun `keys are listed oldest first`() {
        store.trust(Fixtures.knownHost(keyType = "ssh-rsa", addedAt = 5))
        store.trust(Fixtures.knownHost(keyType = "ssh-ed25519", addedAt = 9))
        store.trust(Fixtures.knownHost(keyType = "ecdsa-sha2-nistp256", addedAt = 5))

        store.find("devbox.example.test", 22).map { it.keyType } shouldBe
            listOf("ecdsa-sha2-nistp256", "ssh-rsa", "ssh-ed25519")
    }

    @Test
    fun `touch records when a key was last seen`() {
        store.trust(Fixtures.knownHost(host = "[::1]"))

        store.touch("::1", 22, "ssh-ed25519", nowEpochMs = 99)
        store.touch("::1", 22, "ssh-rsa", nowEpochMs = 100)

        store.find("::1", 22).single().lastSeenAtEpochMs shouldBe 99
    }

    @Test
    fun `replace swaps every key of the host for the accepted one`() =
        runTest {
            store.trust(Fixtures.knownHost(keyType = "ssh-rsa"))
            store.trust(Fixtures.knownHost(keyType = "ssh-ed25519"))
            store.trust(Fixtures.knownHost(port = 2222))
            val accepted = Fixtures.knownHost(host = "DEVBOX.example.test", filler = 7)

            store.replace(accepted)

            store.find("devbox.example.test", 22) shouldBe listOf(accepted.copy(host = "devbox.example.test"))
            store.find("devbox.example.test", 2222) shouldHaveSize 1
        }

    @Test
    fun `keys can be deleted one at a time or per host`() =
        runTest {
            store.trust(Fixtures.knownHost(keyType = "ssh-rsa"))
            store.trust(Fixtures.knownHost(keyType = "ssh-ed25519"))
            store.trust(Fixtures.knownHost(keyType = "ssh-ed25519", port = 2222))

            store.delete("DevBox.example.test", 22, "ssh-rsa") shouldBe true
            store.delete("devbox.example.test", 22, "ssh-rsa") shouldBe false
            store.forget("devbox.example.test", 2222) shouldBe 1
            store.forget("devbox.example.test", 2222) shouldBe 0

            store.find("devbox.example.test", 22).map { it.keyType } shouldBe listOf("ssh-ed25519")
        }

    @Test
    fun `observeAll lists every key`() =
        runTest {
            store.observeAll().test {
                awaitItem().shouldBeEmpty()
                store.trust(Fixtures.knownHost(host = "b.example.test"))
                awaitItem().map { it.host } shouldBe listOf("b.example.test")
                store.trust(Fixtures.knownHost(host = "a.example.test"))
                awaitItem().map { it.host } shouldBe listOf("a.example.test", "b.example.test")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `works as the store of the trust-on-first-use verifier`() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val key = generator.generateKeyPair().public
        var prompts = 0
        val verifier = TofuHostKeyVerifier(store, HostKeyPrompter { prompts++ == 0 }, clock = { 5 })

        verifier.verify("[::1]", 22, key)
        verifier.verify("::1", 22, key)
        verifier.knownKeyTypes("::1", 22) shouldBe listOf("ecdsa-sha2-nistp256")

        prompts shouldBe 1
        shouldThrow<HostKeyChangedException> { verifier.verify("::1", 22, generator.generateKeyPair().public) }
    }
}
