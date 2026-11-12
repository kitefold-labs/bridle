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
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

enum class PassphraseProblem { TOO_SHORT, MISMATCH }

enum class ImportProblem { WRONG_PASSPHRASE, NEWER_VERSION, NOT_A_BACKUP, TOO_LARGE, UNREADABLE, NOT_ENOUGH_MEMORY }

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

    /**
     * A file was picked but the passphrase is gone: Android stopped Bridle while the picker was
     * open (passphrases are never saved). The empty file is deleted; the user exports again.
     */
    data object ExportInterrupted : BackupResult

    /** The phone did not have the memory the key derivation needs. */
    data object NotEnoughMemory : BackupResult

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
 *
 * A backup file names its own Argon2 memory cost (up to [Argon2Params.MAX_MEMORY_KIB]), so before
 * decrypting, the cost is checked against [availableMemory] (the heap this app may still use):
 * a file made on a large phone, or crafted, must not crash a small one with an OutOfMemoryError.
 */
@HiltViewModel
class BackupViewModel(
    private val backups: BackupRepository,
    private val documents: DocumentAccess,
    private val params: Argon2Params,
    private val crypto: CoroutineDispatcher,
    private val availableMemory: () -> Long = ::availableHeap,
    private val clock: Clock,
) : ViewModel() {
    @Inject
    constructor(backups: BackupRepository, documents: DocumentAccess) :
        this(backups, documents, Argon2Params.DEFAULT, Dispatchers.Default, ::availableHeap, Clock.systemDefaultZone())

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
        val date = LocalDate.now(clock) // yyyy-MM-dd in the clock's zone: the phone's, outside tests
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
        val passphrase = exportPassphrase
        exportPassphrase = null
        when {
            uri == null -> passphrase?.fill(0.toChar())
            passphrase == null -> {
                // The picker outlived this ViewModel (Android stopped the app while it was open) and
                // the passphrase is deliberately not saved: say so, and remove the empty file.
                mutable.update { it.copy(result = BackupResult.ExportInterrupted) }
                viewModelScope.launch { withContext(crypto) { documents.delete(uri) } }
            }
            else -> write(uri, passphrase)
        }
    }

    private fun write(
        uri: Uri,
        passphrase: CharArray,
    ) {
        mutable.update { it.copy(working = true) }
        viewModelScope.launch {
            val result =
                try {
                    val payload = backups.export(clock.millis(), BuildConfig.VERSION_NAME)
                    if (affordable(params, fileBytes = 0)) {
                        withContext(crypto) {
                            val bytes = BackupCrypto.encrypt(payload, passphrase, params)
                            documents.openOutput(uri).use { it.write(bytes) }
                        }
                        BackupResult.Exported
                    } else {
                        BackupResult.NotEnoughMemory
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    BackupResult.Failed(e.message ?: e.javaClass.simpleName)
                } catch (ignored: OutOfMemoryError) {
                    BackupResult.NotEnoughMemory
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
                // The file sets its own Argon2 memory cost: check it fits before deriving the key.
                if (affordable(BackupCrypto.kdfParams(bytes), bytes.size)) {
                    val payload = withContext(crypto) { BackupCrypto.decrypt(bytes, passphrase) }
                    val plan = BackupMerge.plan(backups.existingData(), payload)
                    importBytes = null
                    mutable.update {
                        it.copy(working = false, importDialog = false, review = ImportReview(payload, plan))
                    }
                } else {
                    problem(ImportProblem.NOT_ENOUGH_MEMORY)
                }
            } catch (ignored: WrongPassphraseOrCorruptException) {
                problem(ImportProblem.WRONG_PASSPHRASE)
            } catch (ignored: UnsupportedBackupVersionException) {
                problem(ImportProblem.NEWER_VERSION)
            } catch (ignored: MalformedBackupException) {
                problem(ImportProblem.NOT_A_BACKUP)
            } catch (ignored: OutOfMemoryError) {
                problem(ImportProblem.NOT_ENOUGH_MEMORY)
            } finally {
                passphrase.fill(0.toChar())
            }
        }
    }

    /**
     * Whether Argon2 with [kdf] (plus decoding a file of [fileBytes]) fits in the heap left.
     * BouncyCastle's Argon2 holds `memoryKiB` 1 KiB blocks, each a `long[128]` with object
     * overhead; decrypting and parsing need a few copies of the file on top.
     */
    private fun affordable(
        kdf: Argon2Params,
        fileBytes: Int,
    ): Boolean {
        val argon = kdf.memoryKiB * KIB * ARGON2_OVERHEAD_PERCENT / PERCENT
        return argon + fileBytes.toLong() * FILE_COPIES + HEADROOM_BYTES <= availableMemory()
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
            val result =
                try {
                    val applied = backups.import(review.payload)
                    val total =
                        applied.groups.size + applied.hosts.size + applied.launchProfiles.size +
                            applied.snippets.size + applied.portForwards.size + applied.knownHosts.size
                    BackupResult.Imported(applied.hosts.size, total)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // The import runs in one transaction, so nothing was changed.
                    BackupResult.Failed(e.message ?: e.javaClass.simpleName)
                }
            mutable.update { it.copy(working = false, review = null, result = result) }
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
        const val KIB = 1024L
        const val PERCENT = 100
        const val ARGON2_OVERHEAD_PERCENT = 110
        const val FILE_COPIES = 4
        const val HEADROOM_BYTES = 16L * 1024 * 1024
    }
}

/** Heap this process may still allocate: its limit (`memoryClass`, or `largeMemoryClass`) less what is in use. */
internal fun availableHeap(): Long = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }
