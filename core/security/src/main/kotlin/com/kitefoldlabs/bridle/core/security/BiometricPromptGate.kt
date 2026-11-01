package com.kitefoldlabs.bridle.core.security

import android.os.Build
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.Signature
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * [BiometricGate] backed by AndroidX [BiometricPrompt] on [activity]. Safe to call from any
 * thread: the prompt is always shown on the main thread. Cancelling the calling coroutine
 * dismisses the prompt.
 *
 * Prompts for a [Signature] or [Cipher] pass it as a `CryptoObject`, so success unlocks exactly
 * that Keystore operation. The plain prompt carries no `CryptoObject` by necessity: it renews a
 * time-bound key's window, which the Keystore checks when the key is used (see [BiometricGate]).
 *
 * @param title prompt title (localised by the caller).
 * @param negativeButtonText cancel-button label, used when device credentials can't be offered.
 */
public class BiometricPromptGate(
    private val activity: FragmentActivity,
    private val title: String,
    private val negativeButtonText: String,
) : BiometricGate {
    override suspend fun authenticate(reason: String): Boolean = prompt(reason, crypto = null)

    override suspend fun authenticate(
        reason: String,
        signature: Signature,
    ): Boolean = prompt(reason, BiometricPrompt.CryptoObject(signature))

    override suspend fun authenticate(
        reason: String,
        cipher: Cipher,
    ): Boolean = prompt(reason, BiometricPrompt.CryptoObject(cipher))

    private suspend fun prompt(
        reason: String,
        crypto: BiometricPrompt.CryptoObject?,
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            val executor = activity.mainExecutor
            executor.execute {
                if (!continuation.isActive) return@execute
                val prompt = BiometricPrompt(activity, executor, PromptCallback(continuation))
                val info = promptInfo(title, reason, negativeButtonText, crypto != null, Build.VERSION.SDK_INT)
                if (crypto == null) prompt.authenticate(info) else prompt.authenticate(info, crypto)
                continuation.invokeOnCancellation { executor.execute { prompt.cancelAuthentication() } }
            }
        }
}

/** Resumes [continuation] once: true on success, false on error or cancellation. */
internal class PromptCallback(
    private val continuation: CancellableContinuation<Boolean>,
) : BiometricPrompt.AuthenticationCallback() {
    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        if (continuation.isActive) continuation.resume(true)
    }

    override fun onAuthenticationError(
        errorCode: Int,
        errString: CharSequence,
    ) {
        if (continuation.isActive) continuation.resume(false)
    }

    // onAuthenticationFailed() is a rejected attempt; the prompt stays up, so keep waiting.
}

/**
 * Prompt configuration. Per-use (CryptoObject) prompts and prompts before Android 11 accept
 * strong biometrics only; otherwise the device PIN/pattern/password is offered too, matching the
 * authenticators [AndroidKeyStoreBackend] allows for time-bound keys.
 */
internal fun promptInfo(
    title: String,
    reason: String,
    negativeButtonText: String,
    perUse: Boolean,
    sdkInt: Int,
): BiometricPrompt.PromptInfo {
    val builder =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle(title)
            .setSubtitle(reason)
    if (!perUse && sdkInt >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
    } else {
        builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG).setNegativeButtonText(negativeButtonText)
    }
    return builder.build()
}
