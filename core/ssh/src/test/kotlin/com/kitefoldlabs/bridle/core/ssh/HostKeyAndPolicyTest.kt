package com.kitefoldlabs.bridle.core.ssh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HostKeyAndPolicyTest {
    private val key = TestKeys.ed25519().public

    @Test
    fun `first connection prompts then trusts`() {
        val store = MemoryKnownHosts()
        val prompter = AcceptingPrompter()
        val verifier = TofuHostKeyVerifier(store, prompter) { 42 }
        verifier.knownKeyTypes("h", 22) shouldBe emptyList()

        verifier.verify("h", 22, key)
        prompter.prompts.single().fingerprintSha256 shouldBe OpenSshKeys.fingerprintSha256(key)
        store.hosts.single().keyType shouldBe "ssh-ed25519"
        store.hosts.single().addedAtEpochMs shouldBe 42
        verifier.knownKeyTypes("h", 22) shouldBe listOf("ssh-ed25519")

        verifier.verify("h", 22, key) // known: no new prompt
        prompter.prompts shouldHaveSize 1
        store.touched shouldBe listOf("h:22:ssh-ed25519@42")
    }

    @Test
    fun `declined host is rejected and not stored`() {
        val store = MemoryKnownHosts()
        val e =
            shouldThrow<HostKeyRejectedException> {
                TofuHostKeyVerifier(
                    store,
                    AcceptingPrompter(false),
                ).verify("h", 22, key)
            }
        e.prompt.host shouldBe "h"
        e.message shouldNotBe null
        store.hosts shouldBe emptyList()
    }

    @Test
    fun `changed or downgraded key is refused`() {
        val store = MemoryKnownHosts()
        val verifier = TofuHostKeyVerifier(store, AcceptingPrompter())
        verifier.verify("h", 22, key)
        val changed = shouldThrow<HostKeyChangedException> { verifier.verify("h", 22, TestKeys.ed25519().public) }
        changed.expectedFingerprints shouldBe listOf(OpenSshKeys.fingerprintSha256(key))
        changed.host shouldBe "h"
        changed.port shouldBe 22
        changed.keyType shouldBe "ssh-ed25519"
        shouldThrow<HostKeyChangedException> { verifier.verify("h", 22, TestKeys.ec().public) }
        verifier.verify("h", 2222, TestKeys.ec().public) // other port = other host
    }

    @Test
    fun `default knownKeyTypes is empty`() {
        val v =
            object : HostKeyVerifier {
                override fun verify(
                    host: String,
                    port: Int,
                    key: java.security.PublicKey,
                ) = Unit
            }
        v.knownKeyTypes("h", 1) shouldBe emptyList()
    }

    @Test
    fun `backoff grows with jitter and gives up`() {
        val policy = ReconnectPolicy(initial = 1.seconds, max = 8.seconds, maxAttempts = 6, random = Random(1))
        policy.nextDelay(0) shouldBe Duration.ZERO
        for (attempt in 1..5) {
            val d = policy.nextDelay(attempt)!!
            val ceiling = minOf(8_000L, 1_000L shl (attempt - 1)).milliseconds
            (d >= Duration.ZERO && d <= ceiling) shouldBe true
        }
        policy.nextDelay(6) shouldBe null
        (ReconnectPolicy(maxAttempts = 100).nextDelay(80)!! <= 30.seconds) shouldBe true
        shouldThrow<IllegalArgumentException> { policy.nextDelay(-1) }
        shouldThrow<IllegalArgumentException> { ReconnectPolicy(initial = Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { ReconnectPolicy(initial = 2.seconds, max = 1.seconds) }
        shouldThrow<IllegalArgumentException> { ReconnectPolicy(maxAttempts = 0) }
        shouldThrow<IllegalArgumentException> { ReconnectPolicy(max = Duration.INFINITE) }
    }

    @Test
    fun `long backoff bounds saturate instead of overflowing`() {
        // 200 days shl 30 overflows a Long; the ceiling must clamp to max, not go negative and throw.
        val policy = ReconnectPolicy(initial = 200.days, max = 300.days, maxAttempts = 40, random = Random(7))
        for (attempt in 1..39) {
            val d = policy.nextDelay(attempt)!!
            (d >= Duration.ZERO && d <= 300.days) shouldBe true
        }
    }

    @Test
    fun `value types validate and redact`() {
        shouldThrow<IllegalArgumentException> { PtySpec(0, 24) }
        shouldThrow<IllegalArgumentException> { PtySpec(80, 5_000) }
        shouldThrow<IllegalArgumentException> { ConnectTarget("h", 22, "u", emptyList()) }
        val pw = Credential.Password { "hunter2".toCharArray() }
        pw.toString() shouldBe "Credential.Password(****)"
        val target = ConnectTarget("h", 22, "u", listOf(pw))
        target.toString().contains("hunter2") shouldBe false
        Credential.Key(JcaSshKeySigner(key, TestKeys.ed25519().private)).toString() shouldBe
            "Credential.Key(ssh-ed25519)"
        ExecResult(0, "", "").isSuccess shouldBe true
        ExecResult(1, "", "").isSuccess shouldBe false
        ExecResult(null, "", "").isSuccess shouldBe false
        ExecTimeoutException(1.seconds).timeout shouldBe 1.seconds
    }
}
