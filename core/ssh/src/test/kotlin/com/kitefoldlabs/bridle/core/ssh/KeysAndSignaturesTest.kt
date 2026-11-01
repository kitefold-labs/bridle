package com.kitefoldlabs.bridle.core.ssh

import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import net.schmizz.sshj.common.Buffer
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.PublicKey
import java.security.Signature

class KeysAndSignaturesTest {
    @Test
    fun `authorized key lines round trip for every supported key type`() {
        for ((pair, type) in listOf(
            TestKeys.ec() to "ecdsa-sha2-nistp256",
            TestKeys.ec("secp384r1") to "ecdsa-sha2-nistp384",
            TestKeys.ed25519() to "ssh-ed25519",
            TestKeys.rsa() to "ssh-rsa",
        )) {
            val line = OpenSshKeys.authorizedKeyLine(pair.public, "phone\nkey")
            line shouldStartWith "$type "
            line.endsWith(" phone key") shouldBe true
            val parsed = OpenSshKeys.parseAuthorizedKeyLine(line)
            OpenSshKeys.blob(parsed).contentEquals(OpenSshKeys.blob(pair.public)) shouldBe true
            OpenSshKeys.fingerprintSha256(parsed) shouldBe OpenSshKeys.fingerprintSha256(pair.public)
        }
        OpenSshKeys.algorithm(TestKeys.ed25519().public) shouldBe KeyAlgorithm.ED25519
        OpenSshKeys.authorizedKeyLine(TestKeys.ed25519().public).split(" ").size shouldBe 2
        OpenSshKeys.authorizedKeyLine(TestKeys.ed25519().public, "  ").split(" ").size shouldBe 2
    }

    @Test
    fun `fingerprint has ssh-keygen format`() {
        OpenSshKeys.fingerprintSha256(byteArrayOf(1, 2, 3)) shouldBe
            "SHA256:A5BYxvLAy0ksUzsKTRTvd8wPeKvMztUofYShogEc+4E"
        OpenSshKeys.fingerprintSha256(TestKeys.ec().public) shouldMatch Regex("^SHA256:[A-Za-z0-9+/]{43}$")
    }

    @Test
    fun `malformed authorized key lines are rejected`() {
        val ec = OpenSshKeys.authorizedKeyLine(TestKeys.ec().public)
        shouldThrow<IllegalArgumentException> { OpenSshKeys.parseAuthorizedKeyLine("ssh-ed25519") }
        shouldThrow<IllegalArgumentException> { OpenSshKeys.parseAuthorizedKeyLine("ssh-ed25519 !!notbase64") }
        shouldThrow<IllegalArgumentException> {
            OpenSshKeys.parseAuthorizedKeyLine(ec.replace("ecdsa-sha2-nistp256", "ssh-ed25519"))
        }
        val unknown =
            object : PublicKey {
                override fun getAlgorithm() = "DSA-ish"

                override fun getFormat() = "X.509"

                override fun getEncoded() = ByteArray(0)
            }
        shouldThrow<IllegalArgumentException> { OpenSshKeys.typeName(unknown) }
    }

    @Test
    fun `jca signer produces verifiable ssh signatures`() {
        verifyRoundTrip(TestKeys.ec(), "ecdsa-sha2-nistp256", "SHA256withECDSA")
        verifyRoundTrip(TestKeys.ec("secp384r1"), "ecdsa-sha2-nistp384", "SHA384withECDSA")
        verifyRoundTrip(TestKeys.ec("secp521r1"), "ecdsa-sha2-nistp521", "SHA512withECDSA")
        verifyRoundTrip(TestKeys.ed25519(), "ssh-ed25519", "Ed25519")
        val rsa = TestKeys.rsa()
        verifyRoundTrip(rsa, "rsa-sha2-512", "SHA512withRSA")
        verifyRoundTrip(rsa, "rsa-sha2-256", "SHA256withRSA")
        verifyRoundTrip(rsa, "ssh-rsa", "SHA1withRSA")
    }

    @Test
    fun `explicit provider is honoured and unknown algorithms rejected`() {
        val ec = TestKeys.ec()
        val signer = JcaSshKeySigner(ec.public, ec.private, provider = "SunEC")
        signer.sign("ecdsa-sha2-nistp256", byteArrayOf(1)).isNotEmpty() shouldBe true
        shouldThrow<IllegalArgumentException> { signer.sign("ssh-dss", byteArrayOf(1)) }
    }

    @Test
    fun `der conversion handles edge cases`() {
        val r = BigInteger("00ff", 16)
        val s = BigInteger.ONE
        val der = byteArrayOf(0x30, 7, 0x02, 2, 0x00, 0xff.toByte(), 0x02, 1, 0x01)
        val ssh = Buffer.PlainBuffer(SshSignatures.ecdsaDerToSsh(der))
        ssh.readMPInt() shouldBe r
        ssh.readMPInt() shouldBe s

        // Long-form length (0x81) as produced for P-521 signatures.
        val longForm = byteArrayOf(0x30, 0x81.toByte(), 6, 0x02, 1, 0x05, 0x02, 1, 0x07)
        Buffer.PlainBuffer(SshSignatures.ecdsaDerToSsh(longForm)).readMPInt() shouldBe BigInteger.valueOf(5)

        val bad =
            listOf(
                byteArrayOf(0x31, 0),
                byteArrayOf(0x30, 3, 0x02, 1, 0x01, 0x02),
                byteArrayOf(0x30, 6, 0x02, 1, 0x01, 0x02, 1, 0x01, 0x00),
                byteArrayOf(0x30, 7, 0x02, 1, 0x01, 0x02, 1, 0x01, 0x05),
                byteArrayOf(0x30, 6, 0x03, 1, 0x01, 0x02, 1, 0x01),
                byteArrayOf(0x30, 6, 0x02, 0, 0x02, 1, 0x01, 0x00),
                byteArrayOf(0x30, 6, 0x02, 9, 0x01, 0x02, 1, 0x01),
                byteArrayOf(0x30, 6, 0x02, 1, 0xff.toByte(), 0x02, 1, 0x01),
                byteArrayOf(0x30, 0x83.toByte(), 0, 0, 6),
                byteArrayOf(0x30, 0x80.toByte()),
                byteArrayOf(0x30),
            )
        for (b in bad) shouldThrow<IllegalArgumentException> { SshSignatures.ecdsaDerToSsh(b) }
    }

    private fun verifyRoundTrip(
        pair: KeyPair,
        sshAlgorithm: String,
        jca: String,
    ) {
        val data = "session-id+request".toByteArray()
        val blob = JcaSshKeySigner(pair.public, pair.private).sign(sshAlgorithm, data)
        val raw =
            if (sshAlgorithm.startsWith("ecdsa")) {
                val buf = Buffer.PlainBuffer(blob)
                derEncode(buf.readMPInt(), buf.readMPInt())
            } else {
                blob
            }
        Signature.getInstance(jca).apply { initVerify(pair.public) }.run {
            update(data)
            verify(raw)
        } shouldBe true
    }

    private fun derEncode(
        r: BigInteger,
        s: BigInteger,
    ): ByteArray {
        fun int(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return byteArrayOf(0x02) + len(b.size) + b
        }
        val body = int(r) + int(s)
        return byteArrayOf(0x30) + len(body.size) + body
    }

    private fun len(n: Int): ByteArray =
        if (n <
            0x80
        ) {
            byteArrayOf(n.toByte())
        } else {
            byteArrayOf(0x81.toByte(), n.toByte())
        }
}
