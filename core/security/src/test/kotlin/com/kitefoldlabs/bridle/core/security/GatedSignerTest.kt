package com.kitefoldlabs.bridle.core.security

import android.security.keystore.UserNotAuthenticatedException
import com.kitefoldlabs.bridle.core.security.testdata.TestKeyFiles
import com.kitefoldlabs.bridle.core.ssh.JcaSshKeySigner
import com.kitefoldlabs.bridle.core.ssh.SshKeySigner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.InvalidKeyException
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import javax.crypto.Cipher

/** Signer that throws the queued exceptions before succeeding. */
private class ScriptedSigner(
    vararg failures: Exception,
) : SshKeySigner {
    private val pending = failures.toMutableList()
    var calls = 0
    override val publicKey: PublicKey = TestKeyFiles.ec().public

    override fun sign(
        algorithm: String,
        data: ByteArray,
    ): ByteArray {
        calls++
        if (pending.isNotEmpty()) throw pending.removeAt(0)
        return byteArrayOf(7)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatedSignerTest {
    @Test
    fun `keystore keys sign without prompting while unlocked`() {
        val delegate = ScriptedSigner()
        val gate = FakeGate()
        val signer = GatedSigner(delegate, gate, "why")
        signer.publicKey shouldBeSameInstanceAs delegate.publicKey
        signer.sign("x", byteArrayOf()) shouldBe byteArrayOf(7)
        gate.reasons.shouldHaveSize(0)
    }

    @Test
    fun `expired unlock prompts and retries once`() {
        val delegate = ScriptedSigner(UserNotAuthenticatedException())
        val gate = FakeGate()
        GatedSigner(delegate, gate, "Sign in to host").sign("x", byteArrayOf()) shouldBe byteArrayOf(7)
        gate.reasons shouldContainExactly listOf("Sign in to host")
        delegate.calls shouldBe 2
    }

    @Test
    fun `wrapped user-not-authenticated is recognised`() {
        val wrapped = InvalidKeyException("wrapped", UserNotAuthenticatedException())
        val delegate = ScriptedSigner(wrapped)
        GatedSigner(delegate, FakeGate(), "r").sign("x", byteArrayOf()) shouldBe byteArrayOf(7)
    }

    @Test
    fun `declined prompt fails the signature`() {
        val delegate = ScriptedSigner(UserNotAuthenticatedException())
        shouldThrow<UserAuthenticationException> {
            GatedSigner(delegate, FakeGate(false), "r").sign("x", byteArrayOf())
        }
        delegate.calls shouldBe 1
    }

    @Test
    fun `second failure after authenticating propagates`() {
        val delegate = ScriptedSigner(UserNotAuthenticatedException(), UserNotAuthenticatedException())
        shouldThrow<UserNotAuthenticatedException> {
            GatedSigner(delegate, FakeGate(), "r").sign("x", byteArrayOf())
        }
    }

    @Test
    fun `other signing errors are not retried`() {
        val delegate = ScriptedSigner(SignatureException("boom"))
        val gate = FakeGate()
        shouldThrow<SignatureException> { GatedSigner(delegate, gate, "r").sign("x", byteArrayOf()) }
        gate.reasons.shouldHaveSize(0)
    }

    @Test
    fun `app-enforced validity window`() {
        var now = 1_000L
        val gate = FakeGate()
        val signer = GatedSigner(ScriptedSigner(), gate, "r", appEnforcedValidityMillis = 5_000, clock = { now })
        signer.sign("x", byteArrayOf())
        gate.reasons.shouldHaveSize(1)
        now += 4_999
        signer.sign("x", byteArrayOf())
        gate.reasons.shouldHaveSize(1)
        now += 1
        signer.sign("x", byteArrayOf())
        gate.reasons.shouldHaveSize(2)
    }

    @Test
    fun `app-enforced zero validity prompts every time and declines block signing`() {
        val gate = FakeGate(true, false)
        val delegate = ScriptedSigner()
        val signer = GatedSigner(delegate, gate, "r", appEnforcedValidityMillis = 0)
        signer.sign("x", byteArrayOf())
        shouldThrow<UserAuthenticationException> { signer.sign("x", byteArrayOf()) }
        gate.reasons.shouldHaveSize(2)
        delegate.calls shouldBe 1
        shouldThrow<IllegalArgumentException> { GatedSigner(delegate, gate, "r", appEnforcedValidityMillis = -1) }
    }

    @Test
    fun `per-use signer authorises the initialised signature`() {
        for ((pair, algorithm, jca) in listOf(
            Triple(TestKeyFiles.ec(), "ecdsa-sha2-nistp256", null),
            Triple(TestKeyFiles.rsa(), "rsa-sha2-256", "SHA256withRSA"),
        )) {
            val gate = FakeGate()
            val signer = PerUseKeystoreSigner(pair.public, pair.private, gate, "use key")
            val signature = signer.sign(algorithm, byteArrayOf(1, 2))
            gate.signatures.shouldHaveSize(1)
            gate.reasons shouldContainExactly listOf("use key")
            signer.publicKey shouldBeSameInstanceAs pair.public
            if (jca != null) {
                Signature.getInstance(jca).run {
                    initVerify(pair.public)
                    update(byteArrayOf(1, 2))
                    verify(signature)
                } shouldBe true
            } else {
                // ECDSA comes back in SSH encoding (mpint r || mpint s), not DER.
                (signature[0].toInt() == 0x30) shouldBe false
            }
        }
    }

    @Test
    fun `per-use signer refuses when the prompt is declined`() {
        val pair = TestKeyFiles.ec()
        shouldThrow<UserAuthenticationException> {
            PerUseKeystoreSigner(
                pair.public,
                pair.private,
                FakeGate(false),
                "r",
            ).sign("ecdsa-sha2-nistp256", byteArrayOf())
        }
    }

    @Test
    fun `gate default for signatures falls back to plain authentication`() {
        val gate = PlainGate(true)
        runBlocking { gate.authenticate("r", Signature.getInstance("SHA256withECDSA")) } shouldBe true
        gate.calls shouldBe 1
        val pair = TestKeyFiles.ec()
        val jca = JcaSshKeySigner(pair.public, pair.private)
        GatedSigner(jca, gate, "r", appEnforcedValidityMillis = 0).sign("ecdsa-sha2-nistp256", byteArrayOf(1))
        gate.calls shouldBe 2
        runBlocking { gate.authenticate("r", Cipher.getInstance("AES/GCM/NoPadding")) } shouldBe true
        gate.calls shouldBe 3
        UserAuthenticationException().message shouldBe "User authentication was cancelled or failed"
    }
}
