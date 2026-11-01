package com.kitefoldlabs.bridle.core.security.testdata

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PKCS8Generator
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.DSAPrivateKey
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Throwaway private-key files generated at test time, so no key material is ever committed.
 * Lives under `testdata/` because it necessarily contains PEM armour that secret scanners flag.
 */
object TestKeyFiles {
    fun ed25519(): KeyPair = KeyPairGenerator.getInstance("Ed25519", BC).generateKeyPair()

    fun ec(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    fun rsa(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    /** A curve sshj doesn't support (needs BouncyCastle: the JDK dropped it). */
    fun secp256k1(): KeyPair =
        KeyPairGenerator
            .getInstance("EC", BC)
            .apply { initialize(ECGenParameterSpec("secp256k1")) }
            .generateKeyPair()

    /** Header of a PuTTY key (format detection only). */
    const val PUTTY: String = "PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: none\n"

    /** Header of an ssh.com / SSH2 key (format detection only). */
    const val SSH2: String = "---- BEGIN SSH2 ENCRYPTED PRIVATE KEY ----\nabc\n"

    /** A 256-bit RSA key: parses fine but is too small for an RSA/SHA-256 signature. */
    fun tinyRsa(): KeyPair = KeyPairGenerator.getInstance("RSA", BC).apply { initialize(256) }.generateKeyPair()

    /** Ed25519 from the JDK provider, whose PKCS#8 encoding (v1) the JDK can decrypt and re-parse. */
    fun ed25519Jdk(): KeyPair = KeyPairGenerator.getInstance("Ed25519", "SunEC").generateKeyPair()

    fun dsa(): KeyPair = KeyPairGenerator.getInstance("DSA").apply { initialize(2048) }.generateKeyPair()

    // ---------------------------------------------------------------- openssh-key-v1

    /** `ssh-keygen`-style `openssh-key-v1` file; encrypted with bcrypt + aes256-ctr when [passphrase] is set. */
    fun openSsh(
        pair: KeyPair,
        passphrase: String? = null,
        comment: String = "test@bridle",
        publicBlob: ByteArray = publicBlob(pair),
        keyCount: Int = 1,
        mismatchCheckInts: Boolean = false,
        corruptPoint: Boolean = false,
    ): String {
        val section = Buffer.PlainBuffer()
        section.putUInt32(0x1234_5678L)
        section.putUInt32(if (mismatchCheckInts) 0x8765_4321L else 0x1234_5678L)
        putPrivate(section, pair, corruptPoint)
        section.putString(comment)
        val blockSize = if (passphrase == null) 8 else 16
        var pad = 1
        while (section.available() % blockSize != 0) section.putByte((pad++).toByte())
        val plain = section.compactData

        val out = Buffer.PlainBuffer()
        out.putRawBytes(MAGIC)
        if (passphrase == null) {
            out.putString("none").putString("none").putString(ByteArray(0))
            out.putUInt32(keyCount.toLong()).putString(publicBlob).putString(plain)
        } else {
            val salt = ByteArray(16) { it.toByte() }
            val rounds = 2
            val kdf =
                Buffer
                    .PlainBuffer()
                    .putString(salt)
                    .putUInt32(rounds.toLong())
                    .compactData
            val keyIv = ByteArray(48)
            BCrypt().pbkdf(passphrase.toByteArray(), salt, rounds, keyIv)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyIv.copyOfRange(0, 32), "AES"),
                IvParameterSpec(keyIv.copyOfRange(32, 48)),
            )
            out.putString("aes256-ctr").putString("bcrypt").putString(kdf)
            out.putUInt32(keyCount.toLong()).putString(publicBlob).putString(cipher.doFinal(plain))
        }
        return armor("OPENSSH", out.compactData)
    }

    /** An `openssh-key-v1` file with arbitrary [body] bytes. */
    fun openSshRaw(body: ByteArray): String = armor("OPENSSH", body)

    fun publicBlob(pair: KeyPair): ByteArray = Buffer.PlainBuffer().putPublicKey(pair.public).compactData

    /** A public blob that claims [type] (for unsupported-type tests). */
    fun fakePublicBlob(type: String): ByteArray =
        Buffer
            .PlainBuffer()
            .putString(type)
            .putString(ByteArray(32))
            .compactData

    private fun putPrivate(
        buffer: Buffer.PlainBuffer,
        pair: KeyPair,
        corruptPoint: Boolean,
    ) {
        when (val private = pair.private) {
            is RSAPrivateCrtKey ->
                buffer
                    .putString("ssh-rsa")
                    .putMPInt(private.modulus)
                    .putMPInt(private.publicExponent)
                    .putMPInt(private.privateExponent)
                    .putMPInt(private.crtCoefficient)
                    .putMPInt(private.primeP)
                    .putMPInt(private.primeQ)
            is ECPrivateKey -> {
                val public = pair.public as ECPublicKey
                val size = (private.params.curve.field.fieldSize + 7) / 8
                val name = "nistp" + private.params.curve.field.fieldSize
                val q =
                    if (corruptPoint) {
                        byteArrayOf(4) + ByteArray(2 * size) { 1 }
                    } else {
                        byteArrayOf(4) + fixed(public.w.affineX, size) + fixed(public.w.affineY, size)
                    }
                buffer
                    .putString("ecdsa-sha2-$name")
                    .putString(name)
                    .putString(q)
                    .putMPInt(private.s)
            }
            is DSAPrivateKey -> {
                val public = pair.public as DSAPublicKey
                buffer
                    .putString("ssh-dss")
                    .putMPInt(private.params.p)
                    .putMPInt(private.params.q)
                    .putMPInt(private.params.g)
                    .putMPInt(public.y)
                    .putMPInt(private.x)
            }
            else -> {
                // Ed25519: raw public key and 32-byte seed.
                val pub = SubjectPublicKeyInfo.getInstance(pair.public.encoded).publicKeyData.octets
                val seed =
                    ASN1OctetString
                        .getInstance(
                            PrivateKeyInfo.getInstance(private.encoded).parsePrivateKey(),
                        ).octets
                buffer.putString("ssh-ed25519").putString(pub).putString(seed + pub)
            }
        }
    }

    private fun fixed(
        value: BigInteger,
        size: Int,
    ): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(size - bytes.size) + bytes
    }

    // ---------------------------------------------------------------- PEM (BouncyCastle writers)

    /** Traditional OpenSSL PEM (PKCS#1 for RSA, SEC1 for EC, DSA); `Proc-Type` encrypted with [passphrase]. */
    fun traditionalPem(
        pair: KeyPair,
        passphrase: String? = null,
    ): String =
        pem { writer ->
            val obj: Any = if (pair.private is ECPrivateKey) sec1(pair) else pair
            if (passphrase == null) {
                writer.writeObject(obj)
            } else {
                writer.writeObject(obj, JcePEMEncryptorBuilder("AES-128-CBC").build(passphrase.toCharArray()))
            }
        }

    /** SEC1 `ECPrivateKey` with curve parameters and public key, exactly as `openssl ec` writes it. */
    private fun sec1(pair: KeyPair): PrivateKeyInfo {
        val private = pair.private as ECPrivateKey
        val info = PrivateKeyInfo.getInstance(private.encoded)
        val publicBits = SubjectPublicKeyInfo.getInstance(pair.public.encoded).publicKeyData
        val curve = info.privateKeyAlgorithm.parameters
        val key =
            org.bouncycastle.asn1.sec
                .ECPrivateKey(private.params.order.bitLength(), private.s, publicBits, curve)
        return PrivateKeyInfo(info.privateKeyAlgorithm, key)
    }

    /** PKCS#8 PEM; PBES2/AES-256-CBC encrypted with [passphrase]. */
    fun pkcs8Pem(
        pair: KeyPair,
        passphrase: String? = null,
    ): String =
        pem { writer ->
            val encryptor =
                passphrase?.let {
                    JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC).setPassword(it.toCharArray()).build()
                }
            writer.writeObject(JcaPKCS8Generator(pair.private, encryptor))
        }

    /** A PEM block with the given label words (e.g. "FOO" gives `FOO PRIVATE KEY`) and body. */
    fun customPem(
        label: String,
        body: ByteArray,
        headers: List<String> = emptyList(),
    ): String = armor(label, body, headers)

    private fun pem(write: (JcaPEMWriter) -> Unit): String {
        val out = StringWriter()
        JcaPEMWriter(out).use(write)
        return out.toString()
    }

    private fun armor(
        label: String,
        body: ByteArray,
        headers: List<String> = emptyList(),
    ): String {
        val name = "$label PRIVATE KEY"
        val text = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(body)
        val head = if (headers.isEmpty()) "" else headers.joinToString("\n", postfix = "\n\n")
        return "-----BEGIN $name-----\n$head$text\n-----END $name-----\n"
    }

    private val BC = BouncyCastleProvider()

    private val MAGIC = "openssh-key-v1\u0000".toByteArray()
}
