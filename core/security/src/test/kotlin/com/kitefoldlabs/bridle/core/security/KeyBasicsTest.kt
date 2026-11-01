package com.kitefoldlabs.bridle.core.security

import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.security.testdata.TestKeyFiles
import com.kitefoldlabs.bridle.core.ssh.OpenSshKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.PublicKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyBasicsTest {
    @Test
    fun `public keys sshj knows are returned unchanged`() {
        val ec = TestKeyFiles.ec().public
        PublicKeys.normalize(ec) shouldBeSameInstanceAs ec
    }

    @Test
    fun `keystore-style ed25519 keys are re-created`() {
        val software = TestKeyFiles.ed25519().public
        val normalized = PublicKeys.normalize(OidNamedPublicKey(software))
        OpenSshKeys.typeName(normalized) shouldBe "ssh-ed25519"
        OpenSshKeys.blob(normalized) shouldBe OpenSshKeys.blob(software)
    }

    @Test
    fun `unknown keys are passed through`() {
        val noEncoding = UnknownKey(null)
        PublicKeys.normalize(noEncoding) shouldBeSameInstanceAs noEncoding
        val wrongLength = UnknownKey(ByteArray(10))
        PublicKeys.normalize(wrongLength) shouldBeSameInstanceAs wrongLength
        val wrongPrefix = UnknownKey(ByteArray(44))
        PublicKeys.normalize(wrongPrefix) shouldBeSameInstanceAs wrongPrefix
    }

    @Test
    fun `fingerprints match ssh-keygen format`() {
        val pair = TestKeyFiles.ed25519()
        val line = OpenSshKeys.authorizedKeyLine(pair.public, "me@phone")
        val identity =
            Identity(
                alias = "a",
                source = KeySource.IMPORTED,
                algorithm = KeyAlgorithm.ED25519,
                publicKeyOpenSsh = line,
            )
        val expected = OpenSshKeys.fingerprintSha256(pair.public)
        KeyFingerprints.sha256(identity) shouldBe expected
        KeyFingerprints.sha256(line) shouldBe expected
        KeyFingerprints.sha256(pair.public) shouldBe expected
        expected shouldMatch Regex("^SHA256:[A-Za-z0-9+/]{43}$")
        KeyFingerprints.publicKeyWithoutComment(identity) shouldBe line.substringBeforeLast(" ")
        shouldThrow<IllegalArgumentException> { KeyFingerprints.sha256("ssh-ed25519") }
    }

    @Test
    fun `key generation specs and auth policies`() {
        shouldThrow<IllegalArgumentException> { KeyGenSpec(KeyAlgorithm.ECDSA_P521) }
        KeyGenSpec(KeyAlgorithm.RSA).preferStrongBox shouldBe true
        KeyGenSpec.GENERATABLE shouldBe
            setOf(KeyAlgorithm.ECDSA_P256, KeyAlgorithm.ECDSA_P384, KeyAlgorithm.ED25519, KeyAlgorithm.RSA)
        shouldThrow<IllegalArgumentException> { UserAuthPolicy.TimeBound(0) }
        UserAuthPolicy.of(requireUserAuth = false, authValiditySeconds = 0) shouldBe UserAuthPolicy.None
        UserAuthPolicy.of(requireUserAuth = true, authValiditySeconds = 0) shouldBe UserAuthPolicy.PerUse
        UserAuthPolicy.of(requireUserAuth = true, authValiditySeconds = 30) shouldBe UserAuthPolicy.TimeBound(30)
        UserAuthPolicy.None.requiresAuth shouldBe false
        UserAuthPolicy.None.validitySeconds shouldBe UserAuthPolicy.DEFAULT_VALIDITY_SECONDS
        UserAuthPolicy.PerUse.requiresAuth shouldBe true
        UserAuthPolicy.PerUse.validitySeconds shouldBe 0
        UserAuthPolicy.TimeBound(5).validitySeconds shouldBe 5
        KeyGenerationException("x").cause shouldBe null
    }

    private class UnknownKey(
        private val bytes: ByteArray?,
    ) : PublicKey {
        override fun getAlgorithm() = "1.2.3"

        override fun getFormat() = "X.509"

        override fun getEncoded() = bytes
    }
}
