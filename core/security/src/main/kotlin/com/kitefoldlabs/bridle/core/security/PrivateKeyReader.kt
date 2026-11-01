package com.kitefoldlabs.bridle.core.security

import com.hierynomus.sshj.common.KeyDecryptionFailedException
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.ssh.JcaSshKeySigner
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.CharArrayReader
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Base64

/** Container format of a private key file. */
public enum class KeyFileFormat {
    /** `openssh-key-v1`, what `ssh-keygen` writes by default. */
    OPENSSH,

    /** PEM: PKCS#1 (RSA), SEC1 (EC) or PKCS#8, optionally encrypted. */
    PEM,
}

/** What [ImportedKeyVault.inspect] learned about a key file without decrypting it. */
public data class KeyFileInfo(
    val format: KeyFileFormat,
    val encrypted: Boolean,
)

/**
 * Parses OpenSSH and PEM private keys with sshj's key-file readers and turns every failure into a
 * [KeyImportException]. sshj's own exceptions are never propagated because their messages and
 * causes aren't guaranteed to be free of key material.
 */
internal object PrivateKeyReader {
    // Armour labels are assembled at runtime so this file doesn't look like a key to secret scanners.
    private const val DASHES = "-----"
    private const val BEGIN = "$DASHES" + "BEGIN "
    private const val END = "$DASHES" + "END "
    private const val PRIVATE_KEY = "PRIVATE" + " KEY"
    private const val OPENSSH_LABEL = "OPENSSH $PRIVATE_KEY"
    private const val DSA_LABEL = "DSA $PRIVATE_KEY"
    private const val ENCRYPTED_PKCS8_LABEL = "ENCRYPTED $PRIVATE_KEY"
    private val PEM_LABELS = setOf("RSA $PRIVATE_KEY", "EC $PRIVATE_KEY", PRIVATE_KEY, ENCRYPTED_PKCS8_LABEL)
    private const val SSH2_PREFIX = "---- BEGIN SSH2"
    private const val PROC_TYPE_ENCRYPTED = "Proc-Type:4,ENCRYPTED"
    private const val NO_CIPHER = "none"
    private val OPENSSH_MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val SAFE_TYPE_NAME = Regex("[A-Za-z0-9@._-]{1,64}")
    private const val CHALLENGE_SIZE = 32

    private val SUPPORTED: Map<KeyType, KeyAlgorithm> =
        mapOf(
            KeyType.ED25519 to KeyAlgorithm.ED25519,
            KeyType.ECDSA256 to KeyAlgorithm.ECDSA_P256,
            KeyType.ECDSA384 to KeyAlgorithm.ECDSA_P384,
            KeyType.ECDSA521 to KeyAlgorithm.ECDSA_P521,
            KeyType.RSA to KeyAlgorithm.RSA,
        )

    fun inspect(text: CharArray): KeyFileInfo = parse(text).info

    /** Decrypts (if needed) and validates the key. Does not modify [text] or [passphrase]. */
    fun read(
        text: CharArray,
        passphrase: CharArray?,
    ): Pair<KeyPair, KeyAlgorithm> {
        val parsed = parse(text)
        if (parsed.info.encrypted && (passphrase == null || passphrase.isEmpty())) {
            throw KeyImportException.PassphraseRequired()
        }
        val provider: FileKeyProvider =
            if (parsed.info.format == KeyFileFormat.OPENSSH) OpenSSHKeyV1KeyFile() else PKCS8KeyFile()
        provider.init(CharArrayReader(text), passphrase?.takeIf { parsed.info.encrypted }?.let(::OneShotPassword))
        val pair = load(provider, parsed)
        val algorithm = algorithmOf(pair)
        if (!isConsistent(pair, algorithm)) throw KeyImportException.Malformed()
        return pair to algorithm
    }

    // sshj's causes are deliberately not chained (see the class comment), and it surfaces
    // malformed input as arbitrary runtime exceptions.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun load(
        provider: FileKeyProvider,
        parsed: Parsed,
    ): KeyPair =
        try {
            KeyPair(provider.public, provider.private)
        } catch (ignored: KeyDecryptionFailedException) {
            throw failure(parsed, unknownAlgorithm = false)
        } catch (e: IOException) {
            // PKCS#8 / SEC1 keys with an algorithm or curve sshj doesn't know fail with an
            // IllegalArgumentException cause.
            throw failure(parsed, unknownAlgorithm = e.cause is IllegalArgumentException)
        } catch (ignored: RuntimeException) {
            throw failure(parsed, unknownAlgorithm = false)
        }

    /**
     * Maps a reader failure to the error the user sees. With traditional (`Proc-Type`) PEM
     * encryption a wrong passphrase yields garbage that can fail like an unknown algorithm, so
     * only unencrypted and PKCS#8-encrypted keys can be "unsupported".
     */
    private fun failure(
        parsed: Parsed,
        unknownAlgorithm: Boolean,
    ): KeyImportException =
        when {
            unknownAlgorithm && (!parsed.info.encrypted || parsed.label == ENCRYPTED_PKCS8_LABEL) ->
                KeyImportException.UnsupportedKeyType("unknown")
            parsed.info.encrypted -> KeyImportException.WrongPassphrase()
            else -> KeyImportException.Malformed()
        }

    private fun algorithmOf(pair: KeyPair): KeyAlgorithm {
        val type = KeyType.fromKey(pair.public)
        return SUPPORTED[type] ?: throw KeyImportException.UnsupportedKeyType(safeTypeName(type.toString()))
    }

    /**
     * Signs and verifies a random challenge with sshj's provider (which created both halves), so a
     * key file whose halves don't match is rejected.
     */
    private fun isConsistent(
        pair: KeyPair,
        algorithm: KeyAlgorithm,
    ): Boolean {
        val sshAlgorithm = if (algorithm == KeyAlgorithm.RSA) "rsa-sha2-256" else algorithm.sshName
        val jca = JcaSshKeySigner.jcaAlgorithm(sshAlgorithm)
        val challenge = ByteArray(CHALLENGE_SIZE).also(SecureRandom()::nextBytes)
        return try {
            val signature =
                SecurityUtils.getSignature(jca).run {
                    initSign(pair.private)
                    update(challenge)
                    sign()
                }
            SecurityUtils.getSignature(jca).run {
                initVerify(pair.public)
                update(challenge)
                verify(signature)
            }
        } catch (ignored: GeneralSecurityException) {
            false
        }
    }

    private class Parsed(
        val info: KeyFileInfo,
        val label: String,
    )

    private fun parse(text: CharArray): Parsed {
        val string = String(text)
        checkFormat(string)
        val armor = Armor.of(string)
        return when (armor.label) {
            OPENSSH_LABEL -> Parsed(KeyFileInfo(KeyFileFormat.OPENSSH, openSshEncrypted(armor.body)), armor.label)
            in PEM_LABELS -> {
                val encrypted =
                    armor.label == ENCRYPTED_PKCS8_LABEL ||
                        armor.headers.any { it.replace(" ", "") == PROC_TYPE_ENCRYPTED }
                Parsed(KeyFileInfo(KeyFileFormat.PEM, encrypted), armor.label)
            }
            else ->
                throw if (armor.label == DSA_LABEL) {
                    KeyImportException.UnsupportedKeyType(KeyType.DSA.toString())
                } else {
                    KeyImportException.UnsupportedFormat()
                }
        }
    }

    /** Only OpenSSH and PEM files are read; sshj's detection looks at the first non-blank line. */
    private fun checkFormat(string: String) {
        val format =
            try {
                KeyProviderUtil.detectKeyFileFormat(string, false)
            } catch (ignored: IOException) {
                KeyFormat.Unknown // empty input
            }
        val error =
            when {
                format == KeyFormat.OpenSSHv1 || format == KeyFormat.PKCS8 -> null
                format == KeyFormat.PuTTY || string.trimStart().startsWith(SSH2_PREFIX) ->
                    KeyImportException.UnsupportedFormat()
                else -> KeyImportException.Malformed()
            }
        if (error != null) throw error
    }

    /** Whether an `openssh-key-v1` blob is encrypted; also rejects unsupported key types early. */
    private fun openSshEncrypted(body: ByteArray): Boolean {
        val (cipher, keyType) =
            try {
                openSshHeader(Buffer.PlainBuffer(body))
            } catch (ignored: Buffer.BufferException) {
                throw KeyImportException.Malformed()
            }
        if (SUPPORTED.keys.none { it.toString() == keyType }) {
            throw KeyImportException.UnsupportedKeyType(safeTypeName(keyType))
        }
        return cipher != NO_CIPHER
    }

    /** Reads the cleartext header of an `openssh-key-v1` blob: cipher name and public key type. */
    private fun openSshHeader(buffer: Buffer.PlainBuffer): Pair<String, String> {
        val magic = ByteArray(OPENSSH_MAGIC.size).also(buffer::readRawBytes)
        if (!magic.contentEquals(OPENSSH_MAGIC)) throw KeyImportException.Malformed()
        val cipher = buffer.readString()
        buffer.readString() // kdf name
        buffer.readBytes() // kdf options
        if (buffer.readUInt32AsInt() != 1) throw KeyImportException.UnsupportedFormat()
        return cipher to Buffer.PlainBuffer(buffer.readBytes()).readString()
    }

    private fun safeTypeName(name: String): String = if (SAFE_TYPE_NAME.matches(name)) name else "unknown"

    /** One PEM-style armoured block: label, RFC 1421 headers and the base64-decoded body. */
    private class Armor(
        val label: String,
        val headers: List<String>,
        val body: ByteArray,
    ) {
        companion object {
            fun of(text: String): Armor {
                val lines = text.lines().map(String::trim)
                // KeyProviderUtil already checked that the first non-blank line is the BEGIN line.
                val start = lines.indexOfFirst(String::isNotEmpty)
                val afterStart = lines.drop(start + 1).indexOfFirst { it.startsWith(END) }
                if (afterStart < 0) throw KeyImportException.Malformed()
                val end = start + 1 + afterStart
                val content = lines.subList(start + 1, end).filter(String::isNotEmpty)
                val (headers, body) = content.partition { ':' in it }
                val decoded =
                    try {
                        Base64.getDecoder().decode(body.joinToString(""))
                    } catch (ignored: IllegalArgumentException) {
                        throw KeyImportException.Malformed()
                    }
                return Armor(lines[start].removePrefix(BEGIN).removeSuffix(DASHES), headers, decoded)
            }
        }
    }

    /** Hands sshj a fresh copy of the passphrase each time; sshj zeroes the copies it receives. */
    private class OneShotPassword(
        private val passphrase: CharArray,
    ) : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = passphrase.copyOf()

        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }
}
