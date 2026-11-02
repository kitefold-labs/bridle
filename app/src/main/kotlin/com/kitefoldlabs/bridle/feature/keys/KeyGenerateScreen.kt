package com.kitefoldlabs.bridle.feature.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.feature.hosts.BackButton
import com.kitefoldlabs.bridle.feature.hosts.Choice
import com.kitefoldlabs.bridle.feature.hosts.ChoiceField
import com.kitefoldlabs.bridle.feature.hosts.message

@Composable
internal fun KeyGenerateRoute(
    viewModel: KeyGenerateViewModel,
    onBack: () -> Unit,
    onCreated: (EntityId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    KeyGenerateScreen(state, viewModel, onBack, onCreated)
}

/** Generates a key inside the Keystore; [onCreated] receives the new identity's id. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyGenerateScreen(
    state: KeyGenerateUiState,
    actions: KeyGenerateActions,
    onBack: () -> Unit,
    onCreated: (EntityId) -> Unit,
) {
    LaunchedEffect(state.createdId, block = @DontMemoize { state.createdId?.let(onCreated) })
    Scaffold(
        topBar = {
            TopAppBar(navigationIcon = { BackButton(onBack) }, title = { Text(stringResource(R.string.keys_generate)) })
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.key_generate_intro))
            LabelField(state.label, state.labelError, actions::setLabel)
            ChoiceField(
                label = stringResource(R.string.key_generate_algorithm),
                selected = state.algorithm,
                choices = state.algorithms.map { Choice(it, stringResource(it.label())) },
                onSelect = actions::setAlgorithm,
            )
            AuthSettingField(state.auth, actions::setAuth)
            if (state.failed) {
                Text(
                    stringResource(R.string.key_generate_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = actions::generate,
                enabled = !state.busy,
            ) { Text(stringResource(R.string.key_generate_button)) }
        }
    }
}

/** The key's name, with its validation error. */
@Composable
internal fun LabelField(
    label: String,
    error: Validation.Reason?,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = label,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.key_label)) },
        placeholder = { Text(stringResource(R.string.key_label_hint)) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.message())) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Picks whether (and how often) the key asks for a fingerprint, face or screen lock. */
@Composable
internal fun AuthSettingField(
    auth: AuthSetting,
    onChange: (AuthSetting) -> Unit,
) {
    ChoiceField(
        label = stringResource(R.string.key_auth),
        selected = auth.choice,
        choices =
            listOf(
                Choice(AuthChoice.NONE, stringResource(R.string.key_auth_none)),
                Choice(AuthChoice.TIMED, stringResource(R.string.key_auth_timed)),
                Choice(AuthChoice.EVERY_USE, stringResource(R.string.key_auth_every_use)),
            ),
        onSelect = @DontMemoize { onChange(auth.copy(choice = it)) },
    )
    if (auth.choice == AuthChoice.TIMED) {
        ChoiceField(
            label = stringResource(R.string.key_auth_window),
            selected = auth.minutes,
            choices =
                AuthSetting.MINUTE_CHOICES.map { Choice(it, pluralStringResource(R.plurals.key_auth_minutes, it, it)) },
            onSelect = @DontMemoize { onChange(auth.copy(minutes = it)) },
        )
    }
}
