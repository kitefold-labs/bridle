package com.kitefoldlabs.bridle.core.backup

import com.kitefoldlabs.bridle.core.backup.Fixtures.FAST
import com.kitefoldlabs.bridle.core.backup.Fixtures.PASSPHRASE
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupCryptoTest {
    private fun pass(value: String = PASSPHRASE) = value.toCharArray()

    private fun seal(text: String) = BackupCrypto.seal(text.toByteArray(), pass(), FAST)

    private fun encrypted(payload: BackupPayload = Fixtures.samplePayload()) =
        BackupCrypto.encrypt(payload, pass(), FAST)

    @Test
    fun `round trips a payload`() {
        val payload = Fixtures.samplePayload()
        BackupCrypto.decrypt(encrypted(payload), pass()) shouldBe payload
    }

    @Test
    fun `round trips with the default phone parameters`() {
        val payload = BackupPayload(createdAtEpochMs = 5, appVersion = "0.1")
        BackupCrypto.decrypt(BackupCrypto.encrypt(payload, pass()), pass()) shouldBe payload
    }

    @Test
    fun `header layout matches the documented format`() {
        val bytes = encrypted()
        bytes.copyOfRange(0, 8).toString(Charsets.US_ASCII) shouldBe "BRIDLEBK"
        bytes[8] shouldBe 1.toByte()
        bytes[9] shouldBe 1.toByte()
        val buffer = ByteBuffer.wrap(bytes)
        buffer.getInt(10) shouldBe FAST.memoryKiB
        buffer.getInt(14) shouldBe FAST.iterations
        bytes[18] shouldBe FAST.parallelism.toByte()
        BackupCrypto.HEADER_BYTES shouldBe 47
    }

    @Test
    fun `salt and nonce are random, and plaintext does not leak`() {
        val a = encrypted()
        val b = encrypted()
        a.copyOfRange(19, 35).toList() shouldNotBe b.copyOfRange(19, 35).toList()
        a.copyOfRange(35, 47).toList() shouldNotBe b.copyOfRange(35, 47).toList()
        String(a, Charsets.ISO_8859_1) shouldNotContain "devbox"
    }

    @Test
    fun `payload never contains private material`() {
        val descriptor = IdentityMetadata.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map(descriptor::getElementName)
        names shouldBe listOf("id", "alias", "algorithm", "publicKeyOpenSsh", "source")
        names shouldNotContain "keystoreAlias"
    }

    @Test
    fun `wrong passphrase is reported without detail`() {
        val e =
            shouldThrow<WrongPassphraseOrCorruptException> {
                BackupCrypto.decrypt(encrypted(), pass("wrong passphrase!"))
            }
        e.message shouldBe "Wrong passphrase, or the backup file is damaged"
        shouldThrow<WrongPassphraseOrCorruptException> { BackupCrypto.decrypt(encrypted(), CharArray(0)) }
    }

    @Test
    fun `passphrase length is counted in code points`() {
        val payload = Fixtures.samplePayload()
        val e = shouldThrow<PassphraseTooShortException> { BackupCrypto.encrypt(payload, pass("123456789"), FAST) }
        e.minLength shouldBe 10
        e.message shouldContain "at least 10 characters"
        val keys = "🔑".repeat(9)
        shouldThrow<PassphraseTooShortException> { BackupCrypto.encrypt(payload, keys.toCharArray(), FAST) }
        val ok = (keys + "é").toCharArray()
        BackupCrypto.decrypt(BackupCrypto.encrypt(payload, ok, FAST), ok) shouldBe payload
    }

    @Test
    fun `passphrases are encoded exactly like String UTF-8`() =
        runBlocking<Unit> {
            val samples = listOf("", "ascii only", "日本語のパスフレーズ".repeat(20), "🔑".repeat(30), "a\uD800b", "\uDC00")
            samples.forEach { BackupCrypto.utf8(it.toCharArray()).toList() shouldBe it.toByteArray().toList() }
            // Any UTF-16 units, including unpaired surrogates and supplementary characters.
            checkAll(200, Arb.list(Arb.int(0..0xFFFF), 0..64)) { units ->
                val chars = CharArray(units.size) { units[it].toChar() }
                BackupCrypto.utf8(chars).toList() shouldBe String(chars).toByteArray(Charsets.UTF_8).toList()
            }
        }

    @Test
    fun `rejects files that are not backups`() {
        shouldThrow<MalformedBackupException> { BackupCrypto.decrypt(ByteArray(0), pass()) }
        shouldThrow<MalformedBackupException> { BackupCrypto.decrypt("BRIDLE".toByteArray(), pass()) }
        shouldThrow<MalformedBackupException> { BackupCrypto.decrypt("NOTABACKUP".repeat(10).toByteArray(), pass()) }
            .message shouldBe "Not a valid Bridle backup: missing BRIDLEBK signature"
        shouldThrow<MalformedBackupException> { BackupCrypto.decrypt("BRIDLEBK".toByteArray(), pass()) }
            .message shouldContain "truncated"
        shouldThrow<MalformedBackupException> { BackupCrypto.decrypt(encrypted().copyOf(62), pass()) }
            .message shouldContain "truncated"
        val huge = ByteArray(BackupCrypto.MAX_BACKUP_BYTES + 1)
        shouldThrow<MalformedBackupException> { BackupCrypto.decrypt(huge, pass()) }
            .message shouldContain "too large"
    }

    @Test
    fun `rejects unknown versions and kdfs`() {
        val bytes = encrypted()
        val v2 = bytes.copyOf().also { it[8] = 2 }
        shouldThrow<UnsupportedBackupVersionException> { BackupCrypto.decrypt(v2, pass()) }.version shouldBe 2
        val v255 = bytes.copyOf().also { it[8] = -1 }
        shouldThrow<UnsupportedBackupVersionException> { BackupCrypto.decrypt(v255, pass()) }.version shouldBe 255
        val short = bytes.copyOf(9).also { it[8] = 3 }
        shouldThrow<UnsupportedBackupVersionException> { BackupCrypto.decrypt(short, pass()) }
        val kdf = bytes.copyOf().also { it[9] = 2 }
        shouldThrow<MalformedBackupException> { BackupCrypto.decrypt(kdf, pass()) }
            .message shouldContain "key-derivation"
    }

    @Test
    fun `rejects out-of-range kdf parameters before deriving`() {
        val bytes = encrypted()

        fun withInt(
            offset: Int,
            value: Int,
        ) = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }
        listOf(
            withInt(10, Int.MAX_VALUE),
            withInt(10, -1),
            withInt(10, Argon2Params.MIN_MEMORY_KIB - 1),
            withInt(14, 0),
            withInt(14, Argon2Params.MAX_ITERATIONS + 1),
            bytes.copyOf().also { it[18] = 0 },
            bytes.copyOf().also { it[18] = (Argon2Params.MAX_PARALLELISM + 1).toByte() },
        ).forEach { tampered ->
            shouldThrow<MalformedBackupException> { BackupCrypto.decrypt(tampered, pass()) }
                .message shouldContain "out of range"
            shouldThrow<MalformedBackupException> { BackupCrypto.kdfParams(tampered) }
        }
    }

    @Test
    fun `kdf parameters can be read without deriving a key`() {
        BackupCrypto.kdfParams(encrypted()) shouldBe FAST
        val other = Argon2Params(memoryKiB = 16 * 1024, iterations = 2, parallelism = 2)
        BackupCrypto.kdfParams(BackupCrypto.seal(ByteArray(0), pass(), other)) shouldBe other
        shouldThrow<MalformedBackupException> { BackupCrypto.kdfParams(ByteArray(3)) }
    }

    @Test
    fun `argon2 params validate their range`() {
        shouldThrow<IllegalArgumentException> { Argon2Params(Argon2Params.MAX_MEMORY_KIB + 1, 1, 1) }
        shouldThrow<IllegalArgumentException> { Argon2Params(Argon2Params.MIN_MEMORY_KIB, 11, 1) }
        shouldThrow<IllegalArgumentException> { Argon2Params(Argon2Params.MIN_MEMORY_KIB, 1, 5) }
        Argon2Params.isSupported(Argon2Params.MAX_MEMORY_KIB, 10, 4) shouldBe true
        Argon2Params.DEFAULT shouldBe Argon2Params(65_536, 3, 1)
    }

    @Test
    fun `tampering with any header byte is detected`() {
        val bytes = encrypted()
        for (i in 0 until BackupCrypto.HEADER_BYTES) {
            // Low bit flip keeps most parameters in range. Note that it also changes the derived
            // key or nonce, so this alone does not prove the AAD binding; see the next test.
            val tampered = bytes.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            shouldThrow<BackupException> { BackupCrypto.decrypt(tampered, pass()) }
        }
    }

    @Test
    fun `the whole header is bound as additional authenticated data`() {
        val bytes = encrypted()
        val header = bytes.copyOfRange(0, BackupCrypto.HEADER_BYTES)
        val key = BackupCrypto.deriveKey(pass(), bytes.copyOfRange(19, 35), FAST)

        // Decrypts with the right key and nonce, so only the AAD can make it fail.
        fun open(aad: ByteArray?): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(BackupCrypto.TAG_BYTES * 8, bytes.copyOfRange(35, 47))
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            aad?.let { cipher.updateAAD(it) }
            return cipher.doFinal(bytes, BackupCrypto.HEADER_BYTES, bytes.size - BackupCrypto.HEADER_BYTES)
        }
        String(open(header), Charsets.UTF_8) shouldContain "\"formatVersion\":1"
        shouldThrow<AEADBadTagException> { open(null) }
        shouldThrow<AEADBadTagException> { open(header.copyOf(BackupCrypto.HEADER_BYTES - 1)) }
        for (i in header.indices) {
            shouldThrow<AEADBadTagException> { open(header.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }) }
        }
    }

    @Test
    fun `tampering with any ciphertext byte is detected`() =
        runBlocking<Unit> {
            val bytes = encrypted()
            checkAll(
                60,
                Arb.int(BackupCrypto.HEADER_BYTES until bytes.size),
                Arb.int(1..255),
            ) { index, mask ->
                val tampered = bytes.copyOf().also { it[index] = (it[index].toInt() xor mask).toByte() }
                shouldThrow<WrongPassphraseOrCorruptException> { BackupCrypto.decrypt(tampered, pass()) }
            }
        }

    @Test
    fun `round trip holds for arbitrary hosts and passphrases`() =
        runBlocking<Unit> {
            checkAll(
                20,
                Arb.list(Arb.string(1..20), 0..5),
                Arb.string(10..40),
            ) { labels, passphrase ->
                val payload =
                    BackupPayload(
                        createdAtEpochMs = 1,
                        appVersion = "t",
                        hosts =
                            labels.mapIndexed { i, l ->
                                Host(id = i.toLong(), label = l, hostname = "h$i", username = "u")
                            },
                    )
                val chars = passphrase.toCharArray()
                if (Character.codePointCount(chars, 0, chars.size) >= BackupCrypto.MIN_PASSPHRASE_LENGTH) {
                    BackupCrypto.decrypt(BackupCrypto.encrypt(payload, chars, FAST), chars) shouldBe payload
                }
            }
        }

    @Test
    fun `authenticated but unreadable contents are malformed`() {
        listOf(
            "not json",
            "[1,2]",
            "{}",
            """{"formatVersion":"one"}""",
            """{"formatVersion":{"v":1}}""",
            """{"formatVersion":1}""",
            """{"formatVersion":1,"createdAtEpochMs":1,"appVersion":"x","hosts":[{"id":1}]}""",
        ).forEach { text ->
            shouldThrow<MalformedBackupException> { BackupCrypto.decrypt(seal(text), pass()) }
                .message shouldNotContain "formatVersion"
        }
    }

    @Test
    fun `payload versions outside the supported range are rejected`() {
        shouldThrow<UnsupportedBackupVersionException> { BackupCrypto.decrypt(seal("""{"formatVersion":2}"""), pass()) }
            .version shouldBe 2
        shouldThrow<UnsupportedBackupVersionException> { BackupCrypto.decrypt(seal("""{"formatVersion":0}"""), pass()) }
            .version shouldBe 0
    }

    @Test
    fun `unknown payload fields from newer minor versions are ignored`() {
        val text = """{"formatVersion":1,"createdAtEpochMs":3,"appVersion":"9.9","themes":[1]}"""
        val payload = BackupCrypto.decrypt(seal(text), pass())
        payload shouldBe BackupPayload(createdAtEpochMs = 3, appVersion = "9.9")
        payload.shouldBeInstanceOf<BackupPayload>()
    }

    @Test
    fun `identity metadata drops the keystore handle`() {
        val identity =
            Identity(
                id = 3,
                alias = "Pixel",
                source = KeySource.KEYSTORE,
                algorithm = KeyAlgorithm.ECDSA_P256,
                publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAAtest phone",
                keystoreAlias = "bridle-key-3",
            )
        IdentityMetadata.from(identity) shouldBe
            IdentityMetadata(
                3,
                "Pixel",
                KeyAlgorithm.ECDSA_P256,
                "ecdsa-sha2-nistp256 AAAAtest phone",
                KeySource.KEYSTORE,
            )
    }
}
