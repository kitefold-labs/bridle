package com.kitefoldlabs.bridle.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.BuildConfig
import com.kitefoldlabs.bridle.core.backup.Argon2Params
import com.kitefoldlabs.bridle.core.backup.BackupCrypto
import com.kitefoldlabs.bridle.core.backup.BackupMerge
import com.kitefoldlabs.bridle.core.backup.BackupPayload
import com.kitefoldlabs.bridle.core.backup.ImportIssue
import com.kitefoldlabs.bridle.core.backup.ImportPlan
import com.kitefoldlabs.bridle.core.backup.MalformedBackupException
import com.kitefoldlabs.bridle.core.backup.UnsupportedBackupVersionException
import com.kitefoldlabs.bridle.core.backup.WrongPassphraseOrCorruptException
import com.kitefoldlabs.bridle.core.data.BackupRepository
import com.kitefoldlabs.bridle.feature.sftp.DocumentAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class PassphraseProblem { TOO_SHORT, MISMATCH }

enum class ImportProblem { WRONG_PASSPHRASE, NEWER_VERSION, NOT_A_BACKUP, TOO_LARGE, UNREADABLE }

/** What an import will do, for the user to confirm. */
data class ImportReview(
    val payload: BackupPayload,
    val plan: ImportPlan,
) {
    val duplicates: Int get() = plan.issues.count { it is ImportIssue.Duplicate }
    val conflicts: List<ImportIssue.KnownHostKeyConflict> get() =
        plan.issues
            .filterIsInstance<ImportIssue.KnownHostKeyConflict>()
    val missingKeys: List<ImportIssue.MissingIdentity> get() =
        plan.issues
            .filterIsInstance<ImportIssue.MissingIdentity>()

    /** Entries left out (invalid, orphaned) or imported with a reference cleared. */
    val skipped: List<ImportIssue>
        get() =
            plan.issues.filter {
                it is ImportIssue.Invalid || it is ImportIssue.Orphaned || it is ImportIssue.ReferenceCleared
            }
}

sealed interface BackupResult {
    data object Exported : BackupResult

    data class Imported(
        val hosts: Int,
        val total: Int,
    ) : BackupResult

    data class Failed(
        val detail: String,
    ) : BackupResult
}

data class BackupUiState(
    /** The export passphrase dialog is open. */
    val exportDialog: Boolean = false,
    val passphraseProblem: PassphraseProblem? = null,
    /** One-shot: the UI should open the "save as" picker with [suggestedName]. */
    val pickExportFile: Boolean = false,
    val suggestedName: String = "",
    /** A backup file was read; the import passphrase dialog is open. */
    val importDialog: Boolean = false,
    val importProblem: ImportProblem? = null,
    val review: ImportReview? = null,
    val working: Boolean = false,
    val result: BackupResult? = null,
)

/**
 * Encrypted export and import of the app's configuration. Passphrases are handled as [CharArray]s
 * and wiped as soon as they have been used.
 */
@HiltViewModel
class BackupViewModel(
    private val backups: BackupRepository,
    private val documents: DocumentAccess,
    private val params: Argon2Params,
    private val crypto: CoroutineDispatcher,
    private val clock: () -> Long,
) : ViewModel() {
    @Inject
    constructor(backups: BackupRepository, documents: DocumentAccess) :
        this(backups, documents, Argon2Params.DEFAULT, Dispatchers.Default, System::currentTimeMillis)

    private val mutable = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutable.asStateFlow()

    private var exportPassphrase: CharArray? = null
    private var importBytes: ByteArray? = null

    fun startExport() = mutable.update { it.copy(exportDialog = true, passphraseProblem = null, result = null) }

    fun cancelExport() {
        wipeExport()
        mutable.update { it.copy(exportDialog = false, passphraseProblem = null) }
    }

    /** Checks the passphrase, then asks the UI for a file to save to. Both arrays are wiped. */
    fun submitExportPassphrase(
        passphrase: CharArray,
        confirm: CharArray,
    ) {
        val problem =
            when {
                Character.codePointCount(passphrase, 0, passphrase.size) < BackupCrypto.MIN_PASSPHRASE_LENGTH ->
                    PassphraseProblem.TOO_SHORT
                !passphrase.contentEquals(confirm) -> PassphraseProblem.MISMATCH
                else -> null
            }
        confirm.fill(0.toChar())
        if (problem != null) {
            passphrase.fill(0.toChar())
            mutable.update { it.copy(passphraseProblem = problem) }
            return
        }
        wipeExport()
        exportPassphrase = passphrase
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(clock()))
        mutable.update {
            it.copy(
                exportDialog = false,
                passphraseProblem = null,
                pickExportFile = true,
                suggestedName = "bridle-$date.bridle",
            )
        }
    }

    fun exportPickerShown() = mutable.update { it.copy(pickExportFile = false) }

    /** Encrypts and writes the backup to [uri], or forgets the passphrase if the picker was cancelled. */
    fun exportTo(uri: Uri?) {
        val passphrase = exportPassphrase ?: return
        exportPassphrase = null
        if (uri == null) {
            passphrase.fill(0.toChar())
            return
        }
        mutable.update { it.copy(working = true) }
        viewModelScope.launch {
            val result =
                try {
                    val payload = backups.export(clock(), BuildConfig.VERSION_NAME)
                    withContext(crypto) {
                        val bytes = BackupCrypto.encrypt(payload, passphrase, params)
                        documents.openOutput(uri).use { it.write(bytes) }
                    }
                    BackupResult.Exported
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    BackupResult.Failed(e.message ?: e.javaClass.simpleName)
                } finally {
                    passphrase.fill(0.toChar())
                }
            mutable.update { it.copy(working = false, result = result) }
        }
    }

    /** Reads the backup file the user picked (null if they cancelled) and asks for its passphrase. */
    fun importFrom(uri: Uri?) {
        if (uri == null) return
        mutable.update { it.copy(working = true, result = null) }
        viewModelScope.launch {
            val bytes =
                try {
                    withContext(crypto) { documents.openInput(uri).use(::readCapped) }
                } catch (ignored: IOException) {
                    null
                }
            importBytes = bytes
            mutable.update {
                when {
                    bytes == null -> it.copy(working = false, importProblem = ImportProblem.UNREADABLE)
                    bytes.size > BackupCrypto.MAX_BACKUP_BYTES ->
                        it.copy(working = false, importProblem = ImportProblem.TOO_LARGE)
                    else -> it.copy(working = false, importDialog = true, importProblem = null)
                }
            }
        }
    }

    /** Decrypts the picked file and plans the import; [passphrase] is wiped. */
    fun submitImportPassphrase(passphrase: CharArray) {
        val bytes = importBytes ?: return
        mutable.update { it.copy(working = true, importProblem = null) }
        viewModelScope.launch {
            try {
                val payload = withContext(crypto) { BackupCrypto.decrypt(bytes, passphrase) }
                val plan = BackupMerge.plan(backups.existingData(), payload)
                importBytes = null
                mutable.update { it.copy(working = false, importDialog = false, review = ImportReview(payload, plan)) }
            } catch (ignored: WrongPassphraseOrCorruptException) {
                problem(ImportProblem.WRONG_PASSPHRASE)
            } catch (ignored: UnsupportedBackupVersionException) {
                problem(ImportProblem.NEWER_VERSION)
            } catch (ignored: MalformedBackupException) {
                problem(ImportProblem.NOT_A_BACKUP)
            } finally {
                passphrase.fill(0.toChar())
            }
        }
    }

    fun cancelImport() {
        importBytes = null
        mutable.update { it.copy(importDialog = false, importProblem = null, review = null) }
    }

    /** Imports the reviewed backup (re-planned in the same transaction, so nothing is duplicated). */
    fun applyImport() {
        val review = state.value.review ?: return
        mutable.update { it.copy(working = true) }
        viewModelScope.launch {
            val applied = backups.import(review.payload)
            val total =
                applied.groups.size + applied.hosts.size + applied.launchProfiles.size + applied.snippets.size +
                    applied.portForwards.size + applied.knownHosts.size
            mutable.update {
                it.copy(working = false, review = null, result = BackupResult.Imported(applied.hosts.size, total))
            }
        }
    }

    fun dismissResult() = mutable.update { it.copy(result = null, importProblem = null) }

    override fun onCleared() {
        wipeExport()
        importBytes = null
    }

    private fun problem(problem: ImportProblem) = mutable.update { it.copy(working = false, importProblem = problem) }

    private fun wipeExport() {
        exportPassphrase?.fill(0.toChar())
        exportPassphrase = null
    }

    private companion object {
        /** Reads at most one byte more than a backup may have, so oversized files are detected cheaply. */
        fun readCapped(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (out.size() <= BackupCrypto.MAX_BACKUP_BYTES) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        const val BUFFER_SIZE = 64 * 1024
    }
}
