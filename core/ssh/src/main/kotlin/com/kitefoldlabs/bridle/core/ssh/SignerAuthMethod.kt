package com.kitefoldlabs.bridle.core.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthPublickey
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.ProviderException
import java.security.PublicKey

/**
 * `publickey` authentication where signing is delegated to an [SshKeySigner] instead of sshj
 * holding the private key. This is what lets non-exportable Android Keystore keys authenticate.
 * Algorithm negotiation (including `rsa-sha2-256/512` via `server-sig-algs`) is inherited.
 */
internal class SignerAuthMethod(
    private val signer: SshKeySigner,
) : AuthPublickey(PublicOnlyKeyProvider(signer.publicKey)) {
    override fun putSig(reqBuf: SSHPacket): SSHPacket {
        val keyType = KeyType.fromKey(signer.publicKey)
        val algorithm =
            getPublicKeyAlgorithm(keyType)?.keyAlgorithm
                ?: throw UserAuthException("No signature algorithm available for $keyType")
        val data =
            Buffer
                .PlainBuffer()
                .putString(params.transport.sessionID)
                .putBuffer(reqBuf)
                .compactData
        val signature =
            try {
                signer.sign(algorithm, data)
            } catch (e: GeneralSecurityException) {
                throw UserAuthException("Signing with $algorithm failed", e)
            } catch (e: ProviderException) {
                // Android Keystore reports hardware/keystore failures this way. As an auth failure the
                // remaining credentials are still tried; uncaught, it would tear down the transport.
                throw UserAuthException("Signing with $algorithm failed", e)
            }
        reqBuf.putSignature(algorithm, signature)
        return reqBuf
    }

    override fun toString(): String = "SignerAuthMethod(${OpenSshKeys.typeName(signer.publicKey)})"
}

/** Exposes only the public key; sshj never sees (or needs) the private key. */
internal class PublicOnlyKeyProvider(
    private val publicKey: PublicKey,
) : KeyProvider {
    override fun getPrivate(): PrivateKey = throw UnsupportedOperationException("Private key is held by the signer")

    override fun getPublic(): PublicKey = publicKey

    override fun getType(): KeyType = KeyType.fromKey(publicKey)
}
