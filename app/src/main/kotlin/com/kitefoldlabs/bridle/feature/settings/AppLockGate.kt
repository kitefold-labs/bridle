package com.kitefoldlabs.bridle.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import kotlinx.coroutines.launch

/**
 * Shows [content], covered by a lock screen while app lock is on and the app is locked. The
 * content stays composed underneath (so navigation state survives) but is hidden from touch and
 * accessibility services until the user unlocks.
 */
@Composable
fun AppLockGate(
    viewModel: AppLockViewModel = hiltViewModel(),
    unlocker: Unlocker = rememberBiometricUnlocker(),
    content: @Composable () -> Unit,
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    val covered = screen != LockScreen.UNLOCKED
    Box(Modifier.fillMaxSize()) {
        Box(if (covered) Modifier.clearAndSetSemantics {} else Modifier) { content() }
        when (screen) {
            LockScreen.PENDING -> Surface(Modifier.fillMaxSize()) {}
            LockScreen.LOCKED -> LockScreenContent(unlocker, viewModel::unlock)
            LockScreen.UNLOCKED -> Unit
        }
    }
}

@Composable
private fun LockScreenContent(
    unlocker: Unlocker,
    onUnlocked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val attempt: () -> Unit = { scope.launch { if (unlocker.unlock()) onUnlocked() } }
    LaunchedEffect(Unit) { attempt() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.lock_message), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = attempt) { Text(stringResource(R.string.lock_unlock)) }
        }
    }
}
