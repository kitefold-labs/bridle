package com.kitefoldlabs.bridle.core.security

import android.security.keystore.UserNotAuthenticatedException
import com.kitefoldlabs.bridle.core.ssh.JcaSshKeySigner
import com.kitefoldlabs.bridle.core.ssh.SshKeySigner
import com.kitefoldlabs.bridle.core.ssh.SshSignatures
import kotlinx.coroutines.runBlocking
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * Asks the user to prove presence (biometric or device credential) before a key is used.
 * [BiometricPromptGate] is the production implementation.
 */
public interface BiometricGate {
    /** Shows a prompt explaining [reason]; true if the user authenticated, false otherwise. */
    public suspend fun authenticate(reason: String): Boolean

    /**
     * Authenticates exactly one operation on [signature], which is already initialised for
     * signing. Per-use Keystore keys need this (`BiometricPrompt.CryptoObject`); the default
     * falls back to [authenticate].
     */
    public suspend fun authenticate(
        reason: String,
        signature: Signature,
    ): Boolean = authenticate(reason)
}

/**
 * The user declined or failed authentication. A [GeneralSecurityException], so the SSH layer
 * treats it as a failed credential and moves on to the next one.
 */
public class UserAuthenticationException(
    message: String = "User authentication was cancelled or failed",
) : GeneralSecurityException(message)

/**
 * Wraps a signer whose key needs user authentication.
 *
 * - Keystore keys with a validity window throw [UserNotAuthenticatedException] once the window
 *   has expired; the user is then prompted and the signature retried once.
 * - With [appEnforcedValidityMillis] set (imported keys, whose protection is enforced by Bridle
 *   rather than by the Keystore) the user is prompted before signing unless they authenticated
 *   through this signer within that many milliseconds; 0 prompts for every signature.
 *
 * [sign] blocks while the prompt is shown, so never call it on the main thread (the SSH layer
 * signs on its I/O thread).
 */
public class GatedSigner(
    private val delegate: SshKeySigner,
    private val gate: BiometricGate,
    private val reason: String,
    private val appEnforcedValidityMillis: Long? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : SshKeySigner {
    init {
        require(appEnforcedValidityMillis == null || appEnforcedValidityMillis >= 0) {
            "appEnforcedValidityMillis must be >= 0"
        }
    }

    @Volatile
    private var authenticatedAt: Long? = null

    override val publicKey: PublicKey get() = delegate.publicKey

    override fun sign(
        algorithm: String,
        data: ByteArray,
    ): ByteArray {
        if (appEnforcedValidityMillis != null && !authenticatedWithin(appEnforcedValidityMillis)) {
            authenticateOrThrow()
        }
        return try {
            delegate.sign(algorithm, data)
        } catch (e: GeneralSecurityException) {
            if (!e.isUserNotAuthenticated()) throw e
            authenticateOrThrow()
            delegate.sign(algorithm, data)
        }
    }

    private fun authenticatedWithin(windowMillis: Long): Boolean {
        val at = authenticatedAt ?: return false
        return clock() - at < windowMillis
    }

    private fun authenticateOrThrow() {
        if (!runBlocking { gate.authenticate(reason) }) throw UserAuthenticationException()
        authenticatedAt = clock()
    }

    private fun Throwable.isUserNotAuthenticated(): Boolean =
        generateSequence(this) { it.cause }.take(MAX_CAUSES).any { it is UserNotAuthenticatedException }

    private companion object {
        const val MAX_CAUSES = 8
    }
}

/**
 * Signer for Keystore keys that require authentication for every use: the [Signature] is
 * initialised first, then authorised by a biometric prompt bound to it, then used once.
 * Like [GatedSigner.sign], [sign] blocks while the prompt is shown.
 */
public class PerUseKeystoreSigner(
    override val publicKey: PublicKey,
    private val privateKey: PrivateKey,
    private val gate: BiometricGate,
    private val reason: String,
) : SshKeySigner {
    override fun sign(
        algorithm: String,
        data: ByteArray,
    ): ByteArray {
        val signature = Signature.getInstance(JcaSshKeySigner.jcaAlgorithm(algorithm))
        signature.initSign(privateKey)
        if (!runBlocking { gate.authenticate(reason, signature) }) throw UserAuthenticationException()
        signature.update(data)
        val raw = signature.sign()
        return if (algorithm.startsWith("ecdsa-")) SshSignatures.ecdsaDerToSsh(raw) else raw
    }
}
