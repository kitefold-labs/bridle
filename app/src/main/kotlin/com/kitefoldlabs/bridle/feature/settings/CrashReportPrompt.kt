package com.kitefoldlabs.bridle.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.backup.CrashReport
import com.kitefoldlabs.bridle.core.data.settings.SettingsRepository
import com.kitefoldlabs.bridle.crash.CrashReporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The crash prompt shown on launch after a crash. */
sealed interface CrashPrompt {
    val report: CrashReport

    /** Crash reporting is off: ask once whether to look at this report. */
    data class AskConsent(
        override val report: CrashReport,
    ) : CrashPrompt

    /** Show the full scrubbed report and let the user file it. */
    data class Review(
        override val report: CrashReport,
    ) : CrashPrompt
}

/**
 * Offers the report saved by the last crash. Nothing is sent: "Open GitHub issue" opens a
 * pre-filled issue in the browser that the user reviews and submits themselves.
 */
@HiltViewModel
class CrashReportViewModel
    @Inject
    constructor(
        private val reporter: CrashReporter,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val mutable = MutableStateFlow<CrashPrompt?>(null)
        val prompt: StateFlow<CrashPrompt?> = mutable.asStateFlow()

        init {
            reporter.pending()?.let { report ->
                viewModelScope.launch {
                    val optedIn = settings.settings.first().crashReportingOptIn
                    mutable.value = if (optedIn) CrashPrompt.Review(report) else CrashPrompt.AskConsent(report)
                }
            }
        }

        /** The user wants to see the report: remember that they opt in to crash reporting. */
        fun accept() {
            val current = mutable.value ?: return
            viewModelScope.launch { settings.setCrashReportingOptIn(true) }
            mutable.value = CrashPrompt.Review(current.report)
        }

        /** The issue URL for the report with the user's [notes]; the report is then discarded. */
        fun file(notes: String): String? {
            val current = mutable.value ?: return null
            discard()
            return reporter.issueUrl(current.report, notes.trim())
        }

        /** Deletes the report without filing it. */
        fun discard() {
            reporter.discard()
            mutable.value = null
        }
    }

@Composable
fun CrashReportPrompt(viewModel: CrashReportViewModel = hiltViewModel()) {
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    when (val current = prompt) {
        null -> Unit
        is CrashPrompt.AskConsent ->
            AlertDialog(
                onDismissRequest = viewModel::discard,
                title = { Text(stringResource(R.string.crash_title)) },
                text = { Text(stringResource(R.string.crash_ask_message)) },
                confirmButton = {
                    TextButton(
                        onClick = viewModel::accept,
                    ) { Text(stringResource(R.string.crash_review)) }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::discard) { Text(stringResource(R.string.crash_no_thanks)) }
                },
            )
        is CrashPrompt.Review -> ReviewDialog(current.report, viewModel)
    }
}

@Composable
private fun ReviewDialog(
    report: CrashReport,
    viewModel: CrashReportViewModel,
) {
    var notes by rememberSaveable { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.crash_review_message), style = MaterialTheme.typography.bodyMedium)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        report.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                    )
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.crash_notes)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.file(notes)?.let(uriHandler::openUri) }) {
                Text(stringResource(R.string.crash_open_issue))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::discard) { Text(stringResource(R.string.crash_discard)) } },
    )
}
