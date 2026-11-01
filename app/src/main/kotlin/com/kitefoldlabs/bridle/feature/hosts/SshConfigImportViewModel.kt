package com.kitefoldlabs.bridle.feature.hosts

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.ssh.SshConfigImport
import com.kitefoldlabs.bridle.core.ssh.SshConfigParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Why the import screen can't go on. */
enum class SshImportError { TOO_LARGE, READ_FAILED, NOTHING_FOUND }

data class SshImportUiState(
    val text: String = "",
    val defaultUsername: String = "",
    /** Set once the text has been parsed. */
    val plan: ImportPlan? = null,
    /** The parser's notes on lines it skipped (`Include`, `Match`, wildcards, …). */
    val warnings: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    val error: SshImportError? = null,
    /** How many hosts were created, once the import has run. */
    val imported: Int? = null,
)

/** What the import screen can ask its view model to do. */
interface SshImportActions {
    fun setText(text: String)

    fun setDefaultUsername(username: String)

    fun loadFile(uri: Uri)

    fun parse()

    fun toggle(key: String)

    fun import()

    /** Back to editing the text. */
    fun edit()
}

/** Imports hosts from pasted or picked `~/.ssh/config` text. */
@HiltViewModel
class SshConfigImportViewModel
    @Inject
    constructor(
        private val hosts: HostRepository,
        private val documents: DocumentReader,
    ) : ViewModel(),
        SshImportActions {
        private val mutableState = MutableStateFlow(SshImportUiState())
        val state: StateFlow<SshImportUiState> = mutableState.asStateFlow()

        private var parsed: SshConfigImport? = null

        override fun setText(text: String) {
            mutableState.update { it.copy(text = text, error = null) }
        }

        override fun setDefaultUsername(username: String) {
            mutableState.update { it.copy(defaultUsername = username) }
            parsed?.let { replan(it, keepSelection = true) }
        }

        override fun loadFile(uri: Uri) {
            mutableState.update { it.copy(busy = true, error = null) }
            viewModelScope.launch {
                val text =
                    try {
                        documents.readText(uri)
                    } catch (ignored: DocumentTooLargeException) {
                        mutableState.update { it.copy(busy = false, error = SshImportError.TOO_LARGE) }
                        return@launch
                    } catch (ignored: IOException) {
                        mutableState.update { it.copy(busy = false, error = SshImportError.READ_FAILED) }
                        return@launch
                    }
                mutableState.update { it.copy(busy = false, text = text) }
                parse()
            }
        }

        override fun parse() {
            val result =
                try {
                    SshConfigParser.parse(mutableState.value.text)
                } catch (ignored: IllegalArgumentException) {
                    mutableState.update { it.copy(error = SshImportError.TOO_LARGE) }
                    return
                }
            parsed = result
            if (result.entries.isEmpty()) {
                mutableState.update { it.copy(warnings = result.warnings, error = SshImportError.NOTHING_FOUND) }
                parsed = null
                return
            }
            replan(result, keepSelection = false)
        }

        private fun replan(
            import: SshConfigImport,
            keepSelection: Boolean,
        ) {
            mutableState.update { it.copy(busy = true) }
            viewModelScope.launch {
                val existing = hosts.observeAll().first()
                mutableState.update { current ->
                    val plan = ImportPlan.of(import, existing, current.defaultUsername)
                    val keys = plan.selectable.map { it.key }.toSet()
                    current.copy(
                        busy = false,
                        plan = plan,
                        warnings = import.warnings,
                        selected = if (keepSelection) current.selected intersect keys else keys,
                        error = null,
                    )
                }
            }
        }

        override fun toggle(key: String) {
            mutableState.update {
                it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key)
            }
        }

        override fun import() {
            val current = mutableState.value
            val plan = current.plan ?: return
            if (current.busy || current.selected.isEmpty()) return
            mutableState.update { it.copy(busy = true) }
            viewModelScope.launch {
                val count = plan.execute(current.selected, hosts)
                mutableState.update { it.copy(busy = false, imported = count) }
            }
        }

        override fun edit() {
            parsed = null
            mutableState.update { it.copy(plan = null, warnings = emptyList(), selected = emptySet(), error = null) }
        }
    }
