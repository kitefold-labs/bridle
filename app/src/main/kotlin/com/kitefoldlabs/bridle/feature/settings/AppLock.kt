package com.kitefoldlabs.bridle.feature.settings

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the device can authenticate the user (enrolled biometrics, or a screen lock on Android 11+). */
fun interface LockAvailability {
    fun isAvailable(): Boolean
}

/**
 * Checks the same authenticators `BiometricPromptGate` asks for, so app lock is only offered when
 * the prompt can succeed. Without this, enabling app lock on a phone without a screen lock would
 * lock the user out.
 */
class BiometricLockAvailability
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : LockAvailability {
        override fun isAvailable(): Boolean =
            BiometricManager.from(context).canAuthenticate(authenticators(Build.VERSION.SDK_INT)) ==
                BiometricManager.BIOMETRIC_SUCCESS

        companion object {
            fun authenticators(sdkInt: Int): Int =
                if (sdkInt >= Build.VERSION_CODES.R) {
                    Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
                } else {
                    Authenticators.BIOMETRIC_STRONG
                }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
interface AppLockModule {
    @Binds
    fun availability(impl: BiometricLockAvailability): LockAvailability
}

/**
 * Process-wide lock state. The app starts locked; it locks again when it returns to the
 * foreground after more than [timeoutMillis] in the background. Whether the lock is shown at all
 * depends on the app-lock setting (see [AppLockViewModel]).
 *
 * Time in the background is measured on [clock], by default the monotonic
 * [SystemClock.elapsedRealtime]: with the wall clock, setting the phone's time back would keep
 * the app unlocked indefinitely.
 */
@Singleton
class AppLock(
    private val timeoutMillis: Long,
    private val clock: () -> Long,
) {
    @Inject
    constructor() : this(DEFAULT_TIMEOUT_MILLIS, SystemClock::elapsedRealtime)

    private val mutable = MutableStateFlow(true)
    private var backgroundedAt: Long? = null

    val locked: StateFlow<Boolean> = mutable.asStateFlow()

    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val away = clock() - since
        // A clock that went backwards proves nothing about how long the app was away.
        if (away < 0 || away >= timeoutMillis) mutable.value = true
    }

    fun onBackground() {
        backgroundedAt = clock()
    }

    fun unlock() {
        mutable.value = false
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000
    }
}

/** What the lock gate shows over the app. */
enum class LockScreen {
    /** Settings not read yet: cover the app so nothing flashes before a lock appears. */
    PENDING,
    LOCKED,
    UNLOCKED,
}

@HiltViewModel
class AppLockViewModel
    @Inject
    constructor(
        settings: SettingsRepository,
        private val lock: AppLock,
        private val availability: LockAvailability,
    ) : ViewModel() {
        val screen: StateFlow<LockScreen> =
            combine(settings.settings, lock.locked) { current, locked ->
                // A lock that can't be opened (the screen lock was removed) is not enforced.
                val enforced = current.appLockEnabled && availability.isAvailable()
                // Turning app lock on later must not lock the user out of the screen they're on.
                if (!enforced) lock.unlock()
                if (enforced && locked) LockScreen.LOCKED else LockScreen.UNLOCKED
            }.stateIn(viewModelScope, SharingStarted.Eagerly, LockScreen.PENDING)

        fun onForeground() = lock.onForeground()

        fun onBackground() = lock.onBackground()

        fun unlock() = lock.unlock()
    }
