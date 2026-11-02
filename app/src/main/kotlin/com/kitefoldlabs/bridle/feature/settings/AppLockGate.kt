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
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import kotlinx.coroutines.launch

/**
 * Shows [content] only while the app is unlocked (or app lock is off), and a lock screen instead.
 *
 * While locked the content is not composed at all. Covering it is not enough: dialogs, menus and
 * the keyboard are separate windows that would stay above the lock screen, and a focused text
 * field or terminal would still receive key presses. Its saveable state (the navigation back
 * stack, open dialogs, scroll positions) is kept by a [rememberSaveableStateHolder] and restored
 * on unlock; ViewModels are unaffected.
 */
@Composable
fun AppLockGate(
    viewModel: AppLockViewModel,
    unlocker: Unlocker,
    content: @Composable () -> Unit,
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) @DontMemoize { viewModel.onForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) @DontMemoize { viewModel.onBackground() }
    val saved = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
        when (screen) {
            LockScreen.PENDING -> Surface(Modifier.fillMaxSize()) {}
            LockScreen.LOCKED -> LockScreenContent(unlocker, @DontMemoize { viewModel.unlock() })
            LockScreen.UNLOCKED -> saved.SaveableStateProvider(CONTENT_KEY, content)
        }
    }
}

private const val CONTENT_KEY = "app"

@Composable
private fun LockScreenContent(
    unlocker: Unlocker,
    onUnlocked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val attempt: () -> Unit = @DontMemoize { scope.launch { if (unlocker.unlock()) onUnlocked() } }
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
