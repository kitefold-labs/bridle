package com.kitefoldlabs.bridle.core.security

import android.os.Looper
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.Signature
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricPromptGateTest {
    @Test
    fun `prompt info allows device credentials only where keys accept them`() {
        val timed = promptInfo("Unlock key", "Sign in to host", "Cancel", perUse = false, sdkInt = 34)
        timed.title shouldBe "Unlock key"
        timed.subtitle shouldBe "Sign in to host"
        timed.allowedAuthenticators shouldBe (Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)

        val perUse = promptInfo("t", "r", "Cancel", perUse = true, sdkInt = 34)
        perUse.allowedAuthenticators shouldBe Authenticators.BIOMETRIC_STRONG
        perUse.negativeButtonText shouldBe "Cancel"

        val old = promptInfo("t", "r", "Cancel", perUse = false, sdkInt = 29)
        old.allowedAuthenticators shouldBe Authenticators.BIOMETRIC_STRONG
        old.negativeButtonText shouldBe "Cancel"
    }

    @Test
    fun `callback resumes once with the outcome`() =
        runTest {
            val success =
                async {
                    suspendCancellableCoroutine { continuation ->
                        val callback = PromptCallback(continuation)
                        callback.onAuthenticationFailed()
                        callback.onAuthenticationSucceeded(mockk<BiometricPrompt.AuthenticationResult>())
                        callback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "late")
                    }
                }
            success.await() shouldBe true
            val error =
                async {
                    suspendCancellableCoroutine { continuation ->
                        val callback = PromptCallback(continuation)
                        callback.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "cancelled")
                        callback.onAuthenticationSucceeded(mockk<BiometricPrompt.AuthenticationResult>())
                    }
                }
            error.await() shouldBe false
        }

    @Test
    fun `prompts are shown on the main thread and dismissed on cancellation`() =
        runTest {
            // Robolectric's framework BiometricPrompt never answers, so only showing and
            // cancelling can be exercised here; outcomes are covered through PromptCallback.
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val gate = BiometricPromptGate(activity, "Unlock", "Cancel")
            val looper = shadowOf(Looper.getMainLooper())
            val plain = async { gate.authenticate("r") }
            val crypto = async { gate.authenticate("r", Signature.getInstance("SHA256withECDSA")) }
            val cipher = async { gate.authenticate("r", Cipher.getInstance("AES/GCM/NoPadding")) }
            yield()
            looper.idle()
            plain.isActive shouldBe true
            crypto.isActive shouldBe true
            cipher.isActive shouldBe true
            // Cancelled before the main thread got to it: no prompt is shown at all.
            val early = async { gate.authenticate("r") }
            yield()
            early.cancel()
            plain.cancel()
            crypto.cancel()
            cipher.cancel()
            looper.idle()
            listOf(plain, crypto, cipher, early).all { it.isCancelled } shouldBe true
        }
}
