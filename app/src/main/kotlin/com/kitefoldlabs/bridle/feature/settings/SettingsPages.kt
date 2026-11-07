package com.kitefoldlabs.bridle.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.ui.theme.MonoFamily

/** Edits the key-bar layout as JSON; invalid layouts show the parser's message and are not saved. */
@Composable
internal fun KeyBarEditorPage(
    editor: KeyBarEditorState,
    viewModel: SettingsViewModel,
) {
    BackHandler(onBack = @DontMemoize { viewModel.closeKeyBarEditor() })
    Scaffold(
        topBar = {
            PageBar(R.string.settings_key_bar_edit, onBack = viewModel::closeKeyBarEditor) {
                TextButton(onClick = viewModel::applyKeyBarJson) { Text(stringResource(R.string.action_save)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.key_bar_editor_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = editor.json,
                onValueChange = viewModel::editKeyBarJson,
                isError = editor.error != null,
                supportingText = editor.error?.let { { Text(stringResource(R.string.key_bar_invalid, it)) } },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                label = { Text(stringResource(R.string.key_bar_json)) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/** Bundled third-party components and their licences. */
@Composable
internal fun LicencesPage(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Scaffold(topBar = { PageBar(R.string.settings_licences, onBack = onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.licences_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(Licences.all) { licence ->
                ListItem(
                    headlineContent = { Text(licence.component) },
                    supportingContent = { Text(licence.licence) },
                    modifier = Modifier.clickable(role = Role.Button) { uriHandler.tryOpen(licence.url) },
                )
            }
        }
    }
}

/** The privacy statement (the same as PRIVACY.md in the repository). */
@Composable
internal fun PrivacyPage(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Scaffold(topBar = { PageBar(R.string.settings_privacy_statement, onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.privacy_statement), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { uriHandler.tryOpen(Licences.PRIVACY_URL) }) {
                Text(stringResource(R.string.privacy_full_policy))
            }
        }
    }
}
