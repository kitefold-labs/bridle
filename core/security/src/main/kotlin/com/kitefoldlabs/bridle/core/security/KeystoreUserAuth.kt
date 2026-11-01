package com.kitefoldlabs.bridle.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * Applies a [UserAuthPolicy] to a Keystore [KeyGenParameterSpec.Builder], the same way for
 * signing keys ([AndroidKeyStoreBackend]) and for imported keys' wrapping keys
 * ([AndroidKeyStoreWrappingStore]).
 *
 * Per-use keys need a `CryptoObject`, which only strong biometrics can unlock; time-bound keys
 * also accept the device PIN, pattern or password.
 */
internal class KeystoreUserAuth(
    private val sdkInt: Int,
) {
    fun apply(
        builder: KeyGenParameterSpec.Builder,
        policy: UserAuthPolicy,
    ) {
        val seconds =
            when (policy) {
                UserAuthPolicy.None -> return
                UserAuthPolicy.PerUse -> 0
                is UserAuthPolicy.TimeBound -> policy.seconds
            }
        builder.setUserAuthenticationRequired(true)
        if (isAtLeastR()) {
            setAuthParameters(builder, seconds)
        } else {
            // Before Android 11 a validity of -1 means "every use" (biometric + CryptoObject).
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(if (seconds == 0) -1 else seconds)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun setAuthParameters(
        builder: KeyGenParameterSpec.Builder,
        seconds: Int,
    ) {
        val types =
            if (seconds == 0) {
                KeyProperties.AUTH_BIOMETRIC_STRONG
            } else {
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            }
        builder.setUserAuthenticationParameters(seconds, types)
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    private fun isAtLeastR(): Boolean = sdkInt >= Build.VERSION_CODES.R
}
