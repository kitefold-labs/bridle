package com.kitefoldlabs.bridle.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.security.BiometricPromptGate

/** Asks the user to prove it's them; true if they did. */
fun interface Unlocker {
    suspend fun unlock(): Boolean
}

/** A biometric / screen-lock prompt on the hosting [FragmentActivity]. */
@Composable
fun rememberBiometricUnlocker(): Unlocker {
    val context = LocalContext.current
    val title = stringResource(R.string.lock_prompt_title)
    val reason = stringResource(R.string.lock_prompt_reason)
    val cancel = stringResource(R.string.action_cancel)
    return remember(context) {
        val activity = context.findActivity() as? FragmentActivity
        if (activity == null) {
            Unlocker { false }
        } else {
            val gate = BiometricPromptGate(activity, title, cancel)
            Unlocker { gate.authenticate(reason) }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
