package com.kitefoldlabs.bridle.feature.keys

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.model.Validators
import com.kitefoldlabs.bridle.core.security.KeyImportException
import com.kitefoldlabs.bridle.core.security.KeyVaultUnavailableException
import com.kitefoldlabs.bridle.feature.hosts.DocumentReader
import com.kitefoldlabs.bridle.feature.hosts.DocumentTooLargeException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Why a key couldn't be imported, in terms the user can act on. */
enum class KeyImportError {
    EMPTY,
    UNSUPPORTED_TYPE,
    UNSUPPORTED_FORMAT,
    MALFORMED,
    VAULT_UNAVAILABLE,
    TOO_LARGE,
    READ_FAILED,
}

data class KeyImportUiState(
    val label: String = "",
    val labelError: Validation.Reason? = null,
    val keyText: String = "",
    val auth: AuthSetting = AuthSetting(),
    val busy: Boolean = false,
    val error: KeyImportError? = null,
    /** The unsupported key type, for [KeyImportError.UNSUPPORTED_TYPE]. */
    val unsupportedType: String = "",
    /** The key is encrypted: ask for its passphrase. */
    val askPassphrase: Boolean = false,
    /** The last passphrase didn't decrypt the key. */
    val wrongPassphrase: Boolean = false,
    val createdId: EntityId? = null,
)

/** What the import-key screen can ask its view model to do. */
interface KeyImportActions {
    fun setLabel(label: String)

    fun setKeyText(text: String)

    fun setAuth(auth: AuthSetting)

    fun loadFile(uri: Uri)

    fun submit()

    /** Retries the import with [passphrase], which is zeroed afterwards. */
    fun submitPassphrase(passphrase: CharArray)

    fun cancelPassphrase()
}

/**
 * Imports an existing OpenSSH or PEM private key. The key is encrypted with a Keystore-wrapped
 * key before it is stored; the passphrase is used once and zeroed.
 */
@HiltViewModel
class KeyImportViewModel
    @Inject
    constructor(
        private val library: KeyLibrary,
        private val documents: DocumentReader,
    ) : ViewModel(),
        KeyImportActions {
        private val mutableState = MutableStateFlow(KeyImportUiState())
        val state: StateFlow<KeyImportUiState> = mutableState.asStateFlow()

        override fun setLabel(label: String) {
            mutableState.update { it.copy(label = label, labelError = null) }
        }

        override fun setKeyText(text: String) {
            mutableState.update { it.copy(keyText = text, error = null) }
        }

        override fun setAuth(auth: AuthSetting) {
            mutableState.update { it.copy(auth = auth) }
        }

        override fun loadFile(uri: Uri) {
            mutableState.update { it.copy(busy = true, error = null) }
            viewModelScope.launch {
                val error =
                    try {
                        val text = documents.readText(uri, MAX_KEY_CHARS)
                        mutableState.update { it.copy(keyText = text) }
                        null
                    } catch (ignored: DocumentTooLargeException) {
                        KeyImportError.TOO_LARGE
                    } catch (ignored: IOException) {
                        KeyImportError.READ_FAILED
                    }
                mutableState.update { it.copy(busy = false, error = error) }
            }
        }

        override fun submit() {
            val current = mutableState.value
            if (current.busy) return
            val validation = Validators.label(current.label.trim())
            when {
                validation is Validation.Invalid -> mutableState.update { it.copy(labelError = validation.reason) }
                current.keyText.isBlank() -> mutableState.update { it.copy(error = KeyImportError.EMPTY) }
                else -> run(passphrase = null)
            }
        }

        override fun submitPassphrase(passphrase: CharArray) {
            if (mutableState.value.busy) {
                passphrase.fill('\u0000')
                return
            }
            run(passphrase)
        }

        override fun cancelPassphrase() {
            mutableState.update { it.copy(askPassphrase = false, wrongPassphrase = false) }
        }

        private fun run(passphrase: CharArray?) {
            val current = mutableState.value
            mutableState.update { it.copy(busy = true, error = null) }
            viewModelScope.launch {
                val next = attempt(current, passphrase)
                mutableState.update(next)
            }
        }

        /**
         * Inspects or imports the key and returns how the state changes. The key text's copy and
         * [passphrase] are zeroed before the result is published.
         */
        private suspend fun attempt(
            current: KeyImportUiState,
            passphrase: CharArray?,
        ): (KeyImportUiState) -> KeyImportUiState {
            val text = current.keyText.toCharArray()
            return try {
                if (passphrase == null && library.inspect(text).encrypted) {
                    { it.copy(busy = false, askPassphrase = true, wrongPassphrase = false) }
                } else {
                    val id = library.import(text, passphrase, current.label.trim(), current.auth.policy())
                    // Don't keep the private key's text around once it's in the vault.
                    ({ it.copy(busy = false, askPassphrase = false, keyText = "", createdId = id) })
                }
            } catch (e: KeyImportException) {
                { failed(it, e) }
            } catch (ignored: KeyVaultUnavailableException) {
                { it.copy(busy = false, askPassphrase = false, error = KeyImportError.VAULT_UNAVAILABLE) }
            } finally {
                text.fill('\u0000')
                passphrase?.fill('\u0000')
            }
        }

        private fun failed(
            state: KeyImportUiState,
            e: KeyImportException,
        ): KeyImportUiState {
            val idle = state.copy(busy = false)
            return when (e) {
                is KeyImportException.PassphraseRequired -> idle.copy(askPassphrase = true, wrongPassphrase = false)
                is KeyImportException.WrongPassphrase -> idle.copy(askPassphrase = true, wrongPassphrase = true)
                is KeyImportException.UnsupportedKeyType ->
                    idle.copy(
                        askPassphrase = false,
                        error = KeyImportError.UNSUPPORTED_TYPE,
                        unsupportedType = e.keyType,
                    )
                is KeyImportException.UnsupportedFormat ->
                    idle.copy(askPassphrase = false, error = KeyImportError.UNSUPPORTED_FORMAT)
                is KeyImportException.Malformed -> idle.copy(askPassphrase = false, error = KeyImportError.MALFORMED)
            }
        }

        companion object {
            /** A 16384-bit RSA key in PEM is about 13 KB; anything far bigger isn't a key. */
            const val MAX_KEY_CHARS: Int = 64 * 1024
        }
    }
