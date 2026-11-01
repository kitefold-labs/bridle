package com.kitefoldlabs.bridle.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Argon2id cost parameters. [DEFAULT] suits mid-range phones (well under a second). The same
 * bounds are enforced when *reading* a backup, so a crafted file cannot make the app allocate
 * gigabytes or spin for minutes before the passphrase is even checked.
 */
public data class Argon2Params(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    init {
        require(isSupported(memoryKiB, iterations, parallelism)) {
            "Argon2 parameters out of range: memory $MIN_MEMORY_KIB..$MAX_MEMORY_KIB KiB, " +
                "iterations $MIN_ITERATIONS..$MAX_ITERATIONS, parallelism $MIN_PARALLELISM..$MAX_PARALLELISM"
        }
    }

    public companion object {
        public const val MIN_MEMORY_KIB: Int = 8 * 1024
        public const val MAX_MEMORY_KIB: Int = 128 * 1024
        public const val MIN_ITERATIONS: Int = 1
        public const val MAX_ITERATIONS: Int = 10
        public const val MIN_PARALLELISM: Int = 1
        public const val MAX_PARALLELISM: Int = 4

        /** 64 MiB, 3 passes, 1 lane. */
        public val DEFAULT: Argon2Params = Argon2Params(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)

        /** Whether these values are within the range this build accepts. */
        public fun isSupported(
            memoryKiB: Int,
            iterations: Int,
            parallelism: Int,
        ): Boolean =
            memoryKiB in MIN_MEMORY_KIB..MAX_MEMORY_KIB &&
                iterations in MIN_ITERATIONS..MAX_ITERATIONS &&
                parallelism in MIN_PARALLELISM..MAX_PARALLELISM
    }
}

/**
 * Passphrase-encrypted backup files.
 *
 * File layout (all integers big-endian):
 *
 * | Offset | Size | Field                                              |
 * |-------:|-----:|----------------------------------------------------|
 * |      0 |    8 | magic `BRIDLEBK` (ASCII)                           |
 * |      8 |    1 | container version, currently `1`                   |
 * |      9 |    1 | KDF id, `1` = Argon2id v1.3                        |
 * |     10 |    4 | Argon2 memory, KiB                                 |
 * |     14 |    4 | Argon2 iterations                                  |
 * |     18 |    1 | Argon2 parallelism                                 |
 * |     19 |   16 | random salt                                        |
 * |     35 |   12 | random AES-GCM nonce                               |
 * |     47 |    n | AES-256-GCM ciphertext of the UTF-8 JSON payload, followed by the 16-byte tag |
 *
 * The 47-byte header is the GCM additional authenticated data, so changing any parameter, the
 * salt or the nonce makes decryption fail just like a wrong passphrase. The 256-bit key is
 * `Argon2id(passphrase as UTF-8, salt, params)`; it is wiped as soon as the cipher is done, and
 * the passphrase is only ever handled as a [CharArray] (the caller should wipe it afterwards).
 */
public object BackupCrypto {
    /** Minimum passphrase length, in Unicode code points, enforced by [encrypt]. */
    public const val MIN_PASSPHRASE_LENGTH: Int = 10

    /** Larger files are rejected before any work is done. Real backups are a few hundred KiB. */
    public const val MAX_BACKUP_BYTES: Int = 32 * 1024 * 1024

    internal val MAGIC: ByteArray = "BRIDLEBK".toByteArray(StandardCharsets.US_ASCII)
    internal const val CONTAINER_VERSION: Int = 1
    internal const val KDF_ARGON2ID: Int = 1
    internal const val SALT_BYTES: Int = 16
    internal const val NONCE_BYTES: Int = 12
    internal const val TAG_BYTES: Int = 16
    internal const val HEADER_BYTES: Int = 47
    private const val KEY_BYTES = 32
    private const val VERSION_OFFSET = 8
    private const val KDF_OFFSET = 9
    private const val MEMORY_OFFSET = 10
    private const val ITERATIONS_OFFSET = 14
    private const val PARALLELISM_OFFSET = 18
    private const val SALT_OFFSET = 19
    private const val NONCE_OFFSET = 35
    private const val BYTE_MASK = 0xFF

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val random by lazy { SecureRandom() }

    /**
     * Encrypts [payload] under [passphrase].
     * @throws PassphraseTooShortException if [passphrase] has fewer than [MIN_PASSPHRASE_LENGTH] code points.
     */
    public fun encrypt(
        payload: BackupPayload,
        passphrase: CharArray,
        params: Argon2Params = Argon2Params.DEFAULT,
    ): ByteArray {
        if (Character.codePointCount(passphrase, 0, passphrase.size) < MIN_PASSPHRASE_LENGTH) {
            throw PassphraseTooShortException(MIN_PASSPHRASE_LENGTH)
        }
        val plaintext = json.encodeToString(BackupPayload.serializer(), payload).toByteArray(StandardCharsets.UTF_8)
        try {
            return seal(plaintext, passphrase, params)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Encrypts raw [plaintext] into a container. Exposed to tests to forge authenticated contents. */
    internal fun seal(
        plaintext: ByteArray,
        passphrase: CharArray,
        params: Argon2Params,
    ): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = encodeHeader(params, salt, nonce)
        val key = deriveKey(passphrase, salt, params)
        try {
            val cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce)
            cipher.updateAAD(header)
            return header + cipher.doFinal(plaintext)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Decrypts a file produced by [encrypt].
     * @throws MalformedBackupException if [bytes] is not a well-formed backup.
     * @throws UnsupportedBackupVersionException if it was written by a newer format.
     * @throws WrongPassphraseOrCorruptException if authentication fails.
     */
    public fun decrypt(
        bytes: ByteArray,
        passphrase: CharArray,
    ): BackupPayload {
        val params = parseHeader(bytes)
        val salt = bytes.copyOfRange(SALT_OFFSET, SALT_OFFSET + SALT_BYTES)
        val nonce = bytes.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + NONCE_BYTES)
        val key = deriveKey(passphrase, salt, params)
        val plaintext =
            try {
                val cipher = cipher(Cipher.DECRYPT_MODE, key, nonce)
                cipher.updateAAD(bytes, 0, HEADER_BYTES)
                cipher.doFinal(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES)
            } catch (ignored: AEADBadTagException) {
                throw WrongPassphraseOrCorruptException()
            } finally {
                key.fill(0)
            }
        try {
            return decodePayload(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encodeHeader(
        params: Argon2Params,
        salt: ByteArray,
        nonce: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_BYTES)
            .put(MAGIC)
            .put(CONTAINER_VERSION.toByte())
            .put(KDF_ARGON2ID.toByte())
            .putInt(params.memoryKiB)
            .putInt(params.iterations)
            .put(params.parallelism.toByte())
            .put(salt)
            .put(nonce)
            .array()

    /** Validates the container header (cheap checks first) and returns its KDF parameters. */
    private fun parseHeader(bytes: ByteArray): Argon2Params {
        headerProblem(bytes)?.let { throw it }
        val buffer = ByteBuffer.wrap(bytes)
        return Argon2Params(buffer.getInt(MEMORY_OFFSET), buffer.getInt(ITERATIONS_OFFSET), parallelism(bytes))
    }

    private fun headerProblem(bytes: ByteArray): BackupException? {
        val version = bytes.getOrNull(VERSION_OFFSET)?.let { it.toInt() and BYTE_MASK }
        return when {
            bytes.size > MAX_BACKUP_BYTES -> MalformedBackupException("file is too large")
            bytes.size < MAGIC.size || !MAGIC.indices.all { bytes[it] == MAGIC[it] } ->
                MalformedBackupException("missing BRIDLEBK signature")
            version == null -> MalformedBackupException("file is truncated")
            version != CONTAINER_VERSION -> UnsupportedBackupVersionException(version)
            bytes.size < HEADER_BYTES + TAG_BYTES -> MalformedBackupException("file is truncated")
            bytes[KDF_OFFSET].toInt() and BYTE_MASK != KDF_ARGON2ID ->
                MalformedBackupException("unknown key-derivation function")
            !ByteBuffer.wrap(bytes).let {
                Argon2Params.isSupported(it.getInt(MEMORY_OFFSET), it.getInt(ITERATIONS_OFFSET), parallelism(bytes))
            } -> MalformedBackupException("key-derivation parameters are out of range")
            else -> null
        }
    }

    private fun parallelism(bytes: ByteArray) = bytes[PARALLELISM_OFFSET].toInt() and BYTE_MASK

    private fun decodePayload(plaintext: ByteArray): BackupPayload {
        val element = readable { json.parseToJsonElement(String(plaintext, StandardCharsets.UTF_8)) }
        val version = ((element as? JsonObject)?.get("formatVersion") as? JsonPrimitive)?.intOrNull
        when {
            version == null -> throw MalformedBackupException("missing payload version")
            version !in 1..BackupPayload.CURRENT_FORMAT_VERSION -> throw UnsupportedBackupVersionException(version)
        }
        return readable { json.decodeFromJsonElement(BackupPayload.serializer(), element) }
    }

    /**
     * Maps kotlinx.serialization failures (all [IllegalArgumentException]s) to [MalformedBackupException].
     * The cause is dropped on purpose: its message can quote the decrypted JSON.
     */
    private inline fun <T> readable(block: () -> T): T =
        try {
            block()
        } catch (ignored: IllegalArgumentException) {
            throw MalformedBackupException("unreadable contents")
        }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        params: Argon2Params,
    ): ByteArray {
        val password = utf8(passphrase)
        val argon =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKiB)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .build()
        val key = ByteArray(KEY_BYTES)
        try {
            Argon2BytesGenerator().apply { init(argon) }.generateBytes(password, key)
        } finally {
            password.fill(0)
            argon.clear()
        }
        return key
    }

    /** UTF-8 encodes [chars] without creating a String, wiping the intermediate buffer. */
    private fun utf8(chars: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        buffer.array().fill(0)
        return bytes
    }

    private fun cipher(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, nonce))
        }
}
