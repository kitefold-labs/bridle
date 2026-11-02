package com.kitefoldlabs.bridle.feature.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.model.Validators
import com.kitefoldlabs.bridle.core.security.KeyGenerationException
import com.kitefoldlabs.bridle.core.security.UserAuthPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How a key asks for the user before signing. */
enum class AuthChoice { NONE, TIMED, EVERY_USE }

/** The biometric requirement picked for a new key. */
data class AuthSetting(
    val choice: AuthChoice = AuthChoice.NONE,
    val minutes: Int = DEFAULT_MINUTES,
) {
    fun policy(): UserAuthPolicy =
        when (choice) {
            AuthChoice.NONE -> UserAuthPolicy.None
            AuthChoice.TIMED -> UserAuthPolicy.TimeBound(minutes * SECONDS_PER_MINUTE)
            AuthChoice.EVERY_USE -> UserAuthPolicy.PerUse
        }

    companion object {
        const val DEFAULT_MINUTES: Int = 5
        private const val SECONDS_PER_MINUTE = 60

        /** Unlock windows offered for [AuthChoice.TIMED]. */
        val MINUTE_CHOICES: List<Int> = listOf(1, 5, 15, 60)
    }
}

data class KeyGenerateUiState(
    val label: String = "",
    val labelError: Validation.Reason? = null,
    val algorithms: List<KeyAlgorithm> = emptyList(),
    val algorithm: KeyAlgorithm = KeyAlgorithm.ECDSA_P256,
    val auth: AuthSetting = AuthSetting(),
    val busy: Boolean = false,
    val failed: Boolean = false,
    /** Set when the key has been created. */
    val createdId: EntityId? = null,
)

/** What the generate-key screen can ask its view model to do. */
interface KeyGenerateActions {
    fun setLabel(label: String)

    fun setAlgorithm(algorithm: KeyAlgorithm)

    fun setAuth(auth: AuthSetting)

    fun generate()
}

/** Generates a new hardware-backed key in the Android Keystore. */
@HiltViewModel
class KeyGenerateViewModel
    @Inject
    constructor(
        private val library: KeyLibrary,
    ) : ViewModel(),
        KeyGenerateActions {
        private val mutableState =
            MutableStateFlow(
                library.availableAlgorithms().let { KeyGenerateUiState(algorithms = it, algorithm = it.first()) },
            )
        val state: StateFlow<KeyGenerateUiState> = mutableState.asStateFlow()

        override fun setLabel(label: String) {
            mutableState.update { it.copy(label = label, labelError = null) }
        }

        override fun setAlgorithm(algorithm: KeyAlgorithm) {
            mutableState.update { it.copy(algorithm = algorithm) }
        }

        override fun setAuth(auth: AuthSetting) {
            mutableState.update { it.copy(auth = auth) }
        }

        override fun generate() {
            val current = mutableState.value
            if (current.busy) return
            val label = current.label.trim()
            val validation = Validators.label(label)
            if (validation is Validation.Invalid) {
                mutableState.update { it.copy(labelError = validation.reason) }
                return
            }
            mutableState.update { it.copy(busy = true, failed = false) }
            viewModelScope.launch {
                val id =
                    try {
                        library.generate(label, current.algorithm, current.auth.policy())
                    } catch (ignored: KeyGenerationException) {
                        null
                    }
                mutableState.update { it.copy(busy = false, failed = id == null, createdId = id) }
            }
        }
    }
