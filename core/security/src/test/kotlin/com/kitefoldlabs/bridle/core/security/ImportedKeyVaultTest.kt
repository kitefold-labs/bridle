package com.kitefoldlabs.bridle.core.security

import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.security.testdata.TestKeyFiles
import com.kitefoldlabs.bridle.core.ssh.OpenSshKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Signature
import javax.security.auth.DestroyFailedException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedKeyVaultTest {
    private val aead = MemoryAeadProvider()
    private val vault = ImportedKeyVault(aead, clock = { 7L })

    private fun import(
        text: String,
        passphrase: String? = null,
        userAuth: UserAuthPolicy = UserAuthPolicy.None,
    ): ImportedKey = vault.import(text.toCharArray(), passphrase?.toCharArray(), "laptop key", "me@laptop", userAuth)

    private fun assertSigns(
        imported: ImportedKey,
        pair: KeyPair,
        sshAlgorithm: String,
        jca: String,
    ) {
        val signer = vault.signer(imported.identity, imported.encryptedKey)
        OpenSshKeys.blob(signer.publicKey) shouldBe OpenSshKeys.blob(pair.public)
        val data = byteArrayOf(1, 2, 3, 4)
        val signature = signer.sign(sshAlgorithm, data)
        if (!sshAlgorithm.startsWith("ecdsa-")) {
            Signature.getInstance(jca).run {
                initVerify(pair.public)
                update(data)
                verify(signature)
            } shouldBe true
        }
    }

    private val keys: List<Triple<KeyPair, KeyAlgorithm, String>> by lazy {
        listOf(
            Triple(TestKeyFiles.ed25519(), KeyAlgorithm.ED25519, "Ed25519"),
            Triple(TestKeyFiles.ec(), KeyAlgorithm.ECDSA_P256, "SHA256withECDSA"),
            Triple(TestKeyFiles.ec("secp384r1"), KeyAlgorithm.ECDSA_P384, "SHA384withECDSA"),
            Triple(TestKeyFiles.ec("secp521r1"), KeyAlgorithm.ECDSA_P521, "SHA512withECDSA"),
            Triple(TestKeyFiles.rsa(), KeyAlgorithm.RSA, "SHA512withRSA"),
        )
    }

    private fun sshAlgorithm(algorithm: KeyAlgorithm) =
        if (algorithm ==
            KeyAlgorithm.RSA
        ) {
            "rsa-sha2-512"
        } else {
            algorithm.sshName
        }

    @Test
    fun `imports unencrypted and encrypted openssh keys of every supported type`() {
        for ((pair, algorithm, jca) in keys) {
            for (passphrase in listOf(null, "correct horse")) {
                val text = TestKeyFiles.openSsh(pair, passphrase)
                vault.inspect(text.toCharArray()) shouldBe KeyFileInfo(KeyFileFormat.OPENSSH, passphrase != null)
                val imported = import(text, passphrase)
                imported.identity.algorithm shouldBe algorithm
                imported.identity.source shouldBe KeySource.IMPORTED
                imported.identity.alias shouldBe "laptop key"
                imported.identity.keystoreAlias.shouldBeNull()
                imported.identity.createdAtEpochMs shouldBe 7L
                imported.identity.publicKeyOpenSsh shouldEndWith " me@laptop"
                assertSigns(imported, pair, sshAlgorithm(algorithm), jca)
            }
        }
    }

    @Test
    fun `imports traditional and pkcs8 pem keys, plain and encrypted`() {
        for ((pair, algorithm, jca) in keys.filter { it.second != KeyAlgorithm.ED25519 }) {
            for (passphrase in listOf(null, "pem pass")) {
                for (text in listOf(
                    TestKeyFiles.traditionalPem(pair, passphrase),
                    TestKeyFiles.pkcs8Pem(pair, passphrase),
                )) {
                    vault.inspect(text.toCharArray()) shouldBe KeyFileInfo(KeyFileFormat.PEM, passphrase != null)
                    val imported = import(text, passphrase)
                    imported.identity.algorithm shouldBe algorithm
                    assertSigns(imported, pair, sshAlgorithm(algorithm), jca)
                }
            }
        }
    }

    @Test
    fun `stored ciphertext contains no plaintext key material`() {
        val pair = TestKeyFiles.rsa()
        val imported = import(TestKeyFiles.pkcs8Pem(pair))
        val encoded = pair.private.encoded
        val blob = imported.encryptedKey
        blob[0] shouldBe ImportedKeyVault.FORMAT_VERSION
        // No 16-byte window of the PKCS#8 encoding appears in the blob.
        val windows = (0..encoded.size - 16 step 16).map { encoded.copyOfRange(it, it + 16).toList() }
        val blobList = blob.toList()
        windows.none { w -> blobList.windowed(16).any { it == w } } shouldBe true
        imported.toString() shouldNotContain "BEGIN"
    }

    @Test
    fun `passphrase is wiped on success and failure`() {
        val pair = TestKeyFiles.ed25519()
        val text = TestKeyFiles.openSsh(pair, "secret").toCharArray()
        val good = "secret".toCharArray()
        vault.import(text, good, "k")
        good.all { it == '\u0000' } shouldBe true
        val bad = "wrong".toCharArray()
        shouldThrow<KeyImportException.WrongPassphrase> { vault.import(text, bad, "k") }
        bad.all { it == '\u0000' } shouldBe true
        // The key text belongs to the caller and is left alone.
        String(text) shouldBe TestKeyFiles.openSsh(pair, "secret")
    }

    @Test
    fun `missing or wrong passphrases are typed errors`() {
        val pair = TestKeyFiles.ec()
        for (text in listOf(
            TestKeyFiles.openSsh(pair, "right"),
            TestKeyFiles.traditionalPem(pair, "right"),
            TestKeyFiles.pkcs8Pem(pair, "right"),
        )) {
            shouldThrow<KeyImportException.PassphraseRequired> { import(text, null) }
            shouldThrow<KeyImportException.PassphraseRequired> { import(text, "") }
            val wrong = shouldThrow<KeyImportException.WrongPassphrase> { import(text, "wrong") }
            wrong.cause.shouldBeNull()
            wrong.message!! shouldNotContain "wrong"
        }
    }

    @Test
    fun `passphrase for an unencrypted key is ignored`() {
        val pair = TestKeyFiles.ed25519()
        import(TestKeyFiles.openSsh(pair), "unused").identity.algorithm shouldBe KeyAlgorithm.ED25519
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val pair = TestKeyFiles.ec()
        val text = "\n  \n" + TestKeyFiles.pkcs8Pem(pair).replace("\n", "\r\n") + "\n\n"
        import(text).identity.algorithm shouldBe KeyAlgorithm.ECDSA_P256
    }

    @Test
    fun `dsa and unknown key types are rejected`() {
        val dsa = TestKeyFiles.dsa()
        shouldThrow<KeyImportException.UnsupportedKeyType> { import(TestKeyFiles.openSsh(dsa)) }.keyType shouldBe
            "ssh-dss"
        shouldThrow<KeyImportException.UnsupportedKeyType> { import(TestKeyFiles.traditionalPem(dsa)) }.keyType shouldBe
            "ssh-dss"
        shouldThrow<KeyImportException.UnsupportedKeyType> { import(TestKeyFiles.pkcs8Pem(dsa)) }.keyType shouldBe
            "ssh-dss"
        val sk =
            TestKeyFiles.openSsh(
                TestKeyFiles.ed25519(),
                publicBlob = TestKeyFiles.fakePublicBlob("sk-ssh-ed25519@openssh.com"),
            )
        shouldThrow<KeyImportException.UnsupportedKeyType> { import(sk) }.keyType shouldBe "sk-ssh-ed25519@openssh.com"
        val weird = TestKeyFiles.openSsh(TestKeyFiles.ed25519(), publicBlob = TestKeyFiles.fakePublicBlob("bad type\n"))
        shouldThrow<KeyImportException.UnsupportedKeyType> { import(weird) }.keyType shouldBe "unknown"
        // PKCS#8 Ed25519 (openssl genpkey) isn't readable by sshj.
        shouldThrow<KeyImportException.UnsupportedKeyType> { import(TestKeyFiles.pkcs8Pem(TestKeyFiles.ed25519())) }
        // Encrypted, the JDK's PKCS#8 decoder already rejects it while decrypting, which can't be told
        // apart from a wrong passphrase.
        shouldThrow<KeyImportException.WrongPassphrase> {
            import(TestKeyFiles.pkcs8Pem(TestKeyFiles.ed25519(), "pw"), "pw")
        }
        // Encrypted PKCS#8 that the JDK can decrypt: the unknown algorithm is still recognised.
        shouldThrow<KeyImportException.UnsupportedKeyType> {
            import(TestKeyFiles.pkcs8Pem(TestKeyFiles.ed25519Jdk(), "pw"), "pw")
        }
        // Traditional PEM encryption can't tell an unknown curve from a wrong passphrase.
        shouldThrow<KeyImportException.WrongPassphrase> {
            import(TestKeyFiles.traditionalPem(TestKeyFiles.secp256k1(), "pw"), "pw")
        }
        // A curve sshj doesn't support.
        shouldThrow<KeyImportException.UnsupportedKeyType> {
            import(
                TestKeyFiles.traditionalPem(TestKeyFiles.secp256k1()),
            )
        }
    }

    @Test
    fun `other formats and garbage are rejected`() {
        shouldThrow<KeyImportException.UnsupportedFormat> { import(TestKeyFiles.PUTTY) }
        shouldThrow<KeyImportException.UnsupportedFormat> { import(TestKeyFiles.SSH2) }
        shouldThrow<KeyImportException.UnsupportedFormat> { import(TestKeyFiles.customPem("FOO", byteArrayOf(1, 2))) }
        shouldThrow<KeyImportException.Malformed> { import("") }
        shouldThrow<KeyImportException.Malformed> { import("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI me@host") }
        shouldThrow<KeyImportException.Malformed> { import(TestKeyFiles.customPem("RSA", "garbage".toByteArray())) }
        shouldThrow<KeyImportException.Malformed> {
            import(TestKeyFiles.customPem("EC", byteArrayOf(1)).replace("AQ==", "!!!notbase64"))
        }
        shouldThrow<KeyImportException.Malformed> {
            import(
                TestKeyFiles
                    .openSsh(TestKeyFiles.ed25519())
                    .lines()
                    .dropLast(2)
                    .joinToString("\n"),
            )
        }
    }

    @Test
    fun `damaged openssh files are malformed`() {
        val pair = TestKeyFiles.ed25519()
        shouldThrow<KeyImportException.Malformed> {
            import(
                TestKeyFiles.openSshRaw("not-openssh-v1-magic".toByteArray()),
            )
        }
        shouldThrow<KeyImportException.Malformed> { import(TestKeyFiles.openSshRaw(byteArrayOf(1, 2))) }
        shouldThrow<KeyImportException.UnsupportedFormat> { import(TestKeyFiles.openSsh(pair, keyCount = 2)) }
        shouldThrow<KeyImportException.Malformed> { import(TestKeyFiles.openSsh(pair, mismatchCheckInts = true)) }
        // Public half from another key: the consistency check catches it.
        val other = TestKeyFiles.ec()
        shouldThrow<KeyImportException.Malformed> {
            import(TestKeyFiles.openSsh(other, publicBlob = TestKeyFiles.publicBlob(TestKeyFiles.ec())))
        }
        // Invalid EC point in the private section; RSA key too small to sign with.
        shouldThrow<KeyImportException.Malformed> { import(TestKeyFiles.openSsh(other, corruptPoint = true)) }
        shouldThrow<KeyImportException.Malformed> { import(TestKeyFiles.openSsh(TestKeyFiles.tinyRsa())) }
        // Truncated private section.
        val full = TestKeyFiles.openSsh(pair)
        val body =
            java.util.Base64
                .getMimeDecoder()
                .decode(
                    full
                        .lines()
                        .drop(1)
                        .dropLast(2)
                        .joinToString(""),
                )
        shouldThrow<KeyImportException.Malformed> { import(TestKeyFiles.openSshRaw(body.copyOf(body.size - 40))) }
    }

    @Test
    fun `user auth settings are recorded and enforced by the signer`() {
        val pair = TestKeyFiles.ed25519()
        val text = TestKeyFiles.openSsh(pair)
        val timed = import(text, userAuth = UserAuthPolicy.TimeBound(60))
        timed.identity.requireUserAuth shouldBe true
        timed.identity.authValiditySeconds shouldBe 60
        val perUse = import(text, userAuth = UserAuthPolicy.PerUse)
        perUse.identity.authValiditySeconds shouldBe 0
        import(text).identity.requireUserAuth shouldBe false

        shouldThrow<IllegalArgumentException> { vault.signer(timed.identity, timed.encryptedKey) }
        val gate = FakeGate()
        val signer = vault.signer(timed.identity, timed.encryptedKey, gate, "use laptop key")
        signer.shouldBeInstanceOf<GatedSigner>()
        signer.sign("ssh-ed25519", byteArrayOf(1))
        signer.sign("ssh-ed25519", byteArrayOf(2))
        gate.reasons.shouldHaveSize(1)
        val everyTime = vault.signer(perUse.identity, perUse.encryptedKey, gate, "r")
        everyTime.sign("ssh-ed25519", byteArrayOf(1))
        everyTime.sign("ssh-ed25519", byteArrayOf(1))
        gate.reasons.shouldHaveSize(3)
    }

    @Test
    fun `ciphertexts are bound to their identity`() {
        val a = import(TestKeyFiles.openSsh(TestKeyFiles.ed25519()))
        val b = import(TestKeyFiles.openSsh(TestKeyFiles.ed25519()))
        val swapped = vault.signer(a.identity, b.encryptedKey)
        shouldThrow<StoredKeyException> { swapped.sign("ssh-ed25519", byteArrayOf(1)) }
        val tampered = a.encryptedKey.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        shouldThrow<StoredKeyException> { vault.signer(a.identity, tampered).sign("ssh-ed25519", byteArrayOf(1)) }
        val wrongVersion = a.encryptedKey.also { it[0] = 9 }
        shouldThrow<StoredKeyException> { vault.signer(a.identity, wrongVersion).sign("ssh-ed25519", byteArrayOf(1)) }
        shouldThrow<StoredKeyException> { vault.signer(a.identity, ByteArray(0)).sign("ssh-ed25519", byteArrayOf(1)) }
        // A different vault (other master keyset) can't decrypt it either.
        shouldThrow<StoredKeyException> {
            ImportedKeyVault(
                MemoryAeadProvider(),
            ).signer(a.identity, a.encryptedKey).sign("ssh-ed25519", byteArrayOf(1))
        }
    }

    @Test
    fun `damaged plaintext inside a valid ciphertext is reported`() {
        val imported = import(TestKeyFiles.openSsh(TestKeyFiles.ed25519()))
        val publicKey = OpenSshKeys.parseAuthorizedKeyLine(imported.identity.publicKeyOpenSsh)
        val garbage = aead.aead().encrypt(byteArrayOf(1, 2, 3), ImportedKeyVault.associatedData(publicKey))
        val blob = byteArrayOf(ImportedKeyVault.FORMAT_VERSION) + garbage
        shouldThrow<StoredKeyException> { vault.signer(imported.identity, blob).sign("ssh-ed25519", byteArrayOf(1)) }
    }

    @Test
    fun `an unavailable vault is reported as such`() {
        val imported = import(TestKeyFiles.openSsh(TestKeyFiles.ed25519()))
        val broken = ImportedKeyVault({ throw KeyVaultUnavailableException("no keystore") })
        shouldThrow<KeyVaultUnavailableException> {
            broken.signer(imported.identity, imported.encryptedKey).sign("ssh-ed25519", byteArrayOf(1))
        }
        val passphrase = "pw".toCharArray()
        shouldThrow<KeyVaultUnavailableException> {
            broken.import(TestKeyFiles.openSsh(TestKeyFiles.ed25519(), "pw").toCharArray(), passphrase, "k")
        }
        passphrase.all { it == '\u0000' } shouldBe true
    }

    @Test
    fun `signing failures still clear the decrypted key`() {
        val imported = import(TestKeyFiles.openSsh(TestKeyFiles.ed25519()))
        shouldThrow<IllegalArgumentException> {
            vault.signer(imported.identity, imported.encryptedKey).sign("ssh-dss", byteArrayOf(1))
        }
    }

    @Test
    fun `encrypted key accessor returns copies`() {
        val imported = import(TestKeyFiles.openSsh(TestKeyFiles.ed25519()))
        val copy = imported.encryptedKey
        copy.fill(0)
        imported.encryptedKey shouldNotBe copy
        imported.toString() shouldBe "ImportedKey(ED25519, ${copy.size} encrypted bytes)"
    }

    @Test
    fun `keystore identities are rejected`() {
        val identity =
            Identity(
                alias = "hw",
                source = KeySource.KEYSTORE,
                algorithm = KeyAlgorithm.ECDSA_P256,
                publicKeyOpenSsh = "x",
                keystoreAlias = "bridle-key-1",
            )
        shouldThrow<IllegalArgumentException> { vault.signer(identity, byteArrayOf(1)) }
    }

    @Test
    fun `key factory names and best-effort destroy`() {
        ImportedKeyVault.jcaKeyAlgorithm(KeyAlgorithm.ED25519) shouldBe "Ed25519"
        ImportedKeyVault.jcaKeyAlgorithm(KeyAlgorithm.RSA) shouldBe "RSA"
        ImportedKeyVault.jcaKeyAlgorithm(KeyAlgorithm.ECDSA_P384) shouldBe "EC"
        val destroyable = DestroyableKey()
        ImportedKeyVault.destroyQuietly(destroyable)
        ImportedKeyVault.destroyQuietly(destroyable)
        destroyable.destroyCalls shouldBe 1
        ImportedKeyVault.destroyQuietly(TestKeyFiles.ec().private)
    }

    @Test
    fun `exceptions carry no causes and fixed messages`() {
        KeyImportException.Malformed().cause.shouldBeNull()
        KeyImportException.UnsupportedFormat().message shouldBe "Unsupported key file format; use OpenSSH or PEM"
        KeyVaultUnavailableException("x").cause.shouldBeNull()
    }

    private class DestroyableKey : PrivateKey {
        var destroyCalls = 0

        override fun getAlgorithm() = "EC"

        override fun getFormat() = "PKCS#8"

        override fun getEncoded() = ByteArray(0)

        override fun isDestroyed() = destroyCalls > 0

        override fun destroy() {
            destroyCalls++
            if (destroyCalls > 1) throw DestroyFailedException()
        }
    }
}
