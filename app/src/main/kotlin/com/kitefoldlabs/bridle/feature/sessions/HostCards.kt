package com.kitefoldlabs.bridle.feature.sessions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.tmux.HostHelper
import com.kitefoldlabs.bridle.core.tmux.HostProbe

/**
 * What the host offers: tmux (with install instructions when it is missing or too old), Claude
 * Code, and bridle-helper (install or remove).
 */
@Composable
internal fun HostCapabilityCard(
    probe: HostProbe,
    helperEnabled: Boolean,
    actions: SessionsActions,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.host_card_title), style = MaterialTheme.typography.titleSmall)
            val version = probe.tmuxVersion
            when {
                version == null -> TmuxProblem(stringResource(R.string.host_tmux_missing))
                !probe.tmuxSupported -> TmuxProblem(stringResource(R.string.host_tmux_too_old, version.toString()))
                else -> Text(stringResource(R.string.host_tmux_version, version.toString()))
            }
            Text(
                probe.claudePath?.let { stringResource(R.string.host_claude_found, it) }
                    ?: stringResource(R.string.host_claude_missing),
            )
            val helper = probe.helperVersion
            if (helper == null) {
                Text(stringResource(R.string.host_helper_missing))
                Button(onClick = actions::requestInstallHelper) { Text(stringResource(R.string.host_helper_install)) }
            } else {
                Text(stringResource(R.string.host_helper_installed, helper))
                if (!helperEnabled) Text(stringResource(R.string.host_helper_disabled))
                TextButton(onClick = actions::requestUninstallHelper) {
                    Text(stringResource(R.string.host_helper_remove))
                }
            }
        }
    }
}

@Composable
private fun TmuxProblem(problem: String) {
    Text(problem, color = MaterialTheme.colorScheme.error)
    SelectionContainer {
        Text(
            stringResource(R.string.host_tmux_install),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The confirmation [dialog] asks for. */
@Composable
internal fun SessionsDialogContent(
    dialog: SessionsDialog,
    actions: SessionsActions,
) {
    val (title, confirm) =
        when (dialog) {
            is SessionsDialog.ConfirmStop ->
                stringResource(R.string.stop_title, dialog.session) to
                    R.string.stop_confirm
            is SessionsDialog.ConfirmDeleteProfile ->
                stringResource(R.string.delete_profile_title, dialog.profile.name) to R.string.delete_confirm
            is SessionsDialog.InstallHelper -> stringResource(R.string.install_title) to R.string.install_confirm
            SessionsDialog.ConfirmUninstallHelper ->
                stringResource(R.string.uninstall_title) to
                    R.string.uninstall_confirm
        }
    AlertDialog(
        onDismissRequest = actions::dismissDialog,
        title = { Text(title) },
        text = {
            when (dialog) {
                is SessionsDialog.ConfirmStop -> Text(stringResource(R.string.stop_body))
                is SessionsDialog.ConfirmDeleteProfile -> Text(stringResource(R.string.delete_profile_body))
                is SessionsDialog.InstallHelper -> InstallHelperBody(dialog.installHooks, actions::setInstallHooks)
                SessionsDialog.ConfirmUninstallHelper -> Text(stringResource(R.string.uninstall_body))
            }
        },
        confirmButton = { TextButton(onClick = actions::confirmDialog) { Text(stringResource(confirm)) } },
        dismissButton = {
            TextButton(
                onClick = actions::dismissDialog,
            ) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** Consent for installing the helper: what it does, its checksum, and whether to add hooks. */
@Composable
private fun InstallHelperBody(
    installHooks: Boolean,
    onInstallHooksChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.install_body))
        Text(stringResource(R.string.install_checksum), style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
            Text(HostHelper.sha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.toggleable(value = installHooks, role = Role.Checkbox, onValueChange = onInstallHooksChange),
        ) {
            Checkbox(checked = installHooks, onCheckedChange = null)
            Text(stringResource(R.string.install_hooks), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * Asks for POST_NOTIFICATIONS on Android 13+, explaining why first, while notifications are on in
 * the settings and the permission is not granted. "Not now" (or a denial) hides it until the
 * screen is next opened.
 */
@Composable
internal fun NotificationPermissionCard(
    notificationsEnabled: Boolean,
    sdk: Int = Build.VERSION.SDK_INT,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasNotificationPermission(context, sdk)) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            granted = allowed
            dismissed = !allowed
        }
    if (!notificationsEnabled || granted || dismissed) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.notify_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.notify_body))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.notify_not_now)) }
                Button(onClick = { request.launch(NOTIFICATION_PERMISSION) }) {
                    Text(stringResource(R.string.notify_allow))
                }
            }
        }
    }
}

/** Before Android 13 notifications need no runtime permission. */
internal fun hasNotificationPermission(
    context: Context,
    sdk: Int,
): Boolean =
    sdk < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, NOTIFICATION_PERMISSION) == PackageManager.PERMISSION_GRANTED

/** Only requested behind the Android 13 check above; the constant is inlined at compile time. */
@SuppressLint("InlinedApi")
private const val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS
