package com.kitefoldlabs.bridle.feature.keys

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.connect.SignerSource
import com.kitefoldlabs.bridle.core.data.IdentityRepository
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.security.BiometricGate
import com.kitefoldlabs.bridle.core.security.BiometricPromptGate
import com.kitefoldlabs.bridle.core.security.ImportedKeyVault
import com.kitefoldlabs.bridle.core.security.KeystoreKeyManager
import com.kitefoldlabs.bridle.core.ssh.SshKeySigner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.security.GeneralSecurityException
import java.security.Signature
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which activity is in the foreground, so a biometric prompt requested from an SSH I/O
 * thread has somewhere to appear. Holds the activity weakly and forgets it when it pauses.
 */
@Singleton
class ResumedActivityTracker
    @Inject
    constructor() {
        @Volatile
        private var resumed: WeakReference<FragmentActivity>? = null

        /** The resumed activity, or null while the app is in the background. */
        fun current(): FragmentActivity? = resumed?.get()

        /** Starts tracking [activity]; call from its `onCreate`. */
        fun attach(activity: FragmentActivity) {
            activity.lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onResume(owner: LifecycleOwner) {
                        resumed = WeakReference(activity)
                    }

                    override fun onPause(owner: LifecycleOwner) {
                        if (current() === activity) resumed = null
                    }
                },
            )
        }
    }

/**
 * [BiometricGate] that shows the system prompt over whichever activity is resumed when the prompt
 * is needed (looked up for every prompt, never captured). With the app
 * in the background there's no one to ask, so authentication fails and the SSH layer moves on to
 * the next credential (a password prompt, which waits for the user to come back).
 */
@Singleton
class ActivityBiometricGate internal constructor(
    private val tracker: ResumedActivityTracker,
    internal val promptFor: (FragmentActivity) -> BiometricGate,
) : BiometricGate {
    @Inject
    constructor(
        tracker: ResumedActivityTracker,
        @ApplicationContext context: Context,
    ) : this(tracker, { activity ->
        BiometricPromptGate(
            activity,
            context.getString(R.string.biometric_title),
            context.getString(R.string.action_cancel),
        )
    })

    override suspend fun authenticate(reason: String): Boolean =
        tracker.current()?.let { promptFor(it).authenticate(reason) } ?: false

    override suspend fun authenticate(
        reason: String,
        signature: Signature,
    ): Boolean = tracker.current()?.let { promptFor(it).authenticate(reason, signature) } ?: false

    override suspend fun authenticate(
        reason: String,
        cipher: Cipher,
    ): Boolean = tracker.current()?.let { promptFor(it).authenticate(reason, cipher) } ?: false
}

/**
 * The real [SignerSource]: hardware keys sign inside the Keystore; imported keys are decrypted
 * from the vault for each signature. Keys that require user authentication prompt through
 * [gate]; signing blocks the SSH I/O thread (never the main thread) while the prompt is up.
 */
@Singleton
class KeyStoreSignerSource
    @Inject
    constructor(
        private val keystore: KeystoreKeyManager,
        private val vault: ImportedKeyVault,
        private val identities: IdentityRepository,
        private val gate: BiometricGate,
        @param:ApplicationContext private val context: Context,
    ) : SignerSource {
        /** Null when the key is gone (Keystore entry deleted, e.g. after a lock-screen reset) or unreadable. */
        override suspend fun signer(identity: Identity): SshKeySigner? =
            withContext(Dispatchers.IO) {
                val reason = context.getString(R.string.biometric_reason, identity.alias)
                try {
                    when (identity.source) {
                        KeySource.KEYSTORE ->
                            if (keystore.exists(identity)) keystore.signer(identity, gate, reason) else null
                        KeySource.IMPORTED ->
                            identities
                                .encryptedPrivateKey(
                                    identity.id,
                                )?.let { vault.signer(identity, it, gate, reason) }
                    }
                } catch (ignored: GeneralSecurityException) {
                    null
                } catch (ignored: IllegalArgumentException) {
                    // A stored public key that no longer parses.
                    null
                }
            }
    }
