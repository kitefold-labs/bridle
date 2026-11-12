package com.kitefoldlabs.bridle.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.BuildConfig
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.data.settings.NotificationEvent
import com.kitefoldlabs.bridle.core.data.settings.Settings
import com.kitefoldlabs.bridle.core.data.settings.ThemeMode
import com.kitefoldlabs.bridle.core.terminal.BackspaceMode
import kotlin.math.roundToInt

/** Sub-pages of the settings screen. */
enum class SettingsPage { MAIN, LICENCES, PRIVACY }

/** Single-choice settings that open a dialog. */
internal enum class SettingsChoice { THEME, FONT, KEY_BAR, BACKSPACE }

/** The Settings tab. */
@Composable
fun SettingsRoute() {
    SettingsScreen(hiltViewModel(), hiltViewModel())
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    backup: BackupViewModel,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val editor by viewModel.keyBarEditor.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    val current = settings
    val keyBar = editor
    when {
        keyBar != null -> KeyBarEditorPage(keyBar, viewModel)
        page == SettingsPage.LICENCES -> LicencesPage(onBack = { page = SettingsPage.MAIN })
        page == SettingsPage.PRIVACY -> PrivacyPage(onBack = { page = SettingsPage.MAIN })
        current == null ->
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        else -> SettingsList(current, viewModel, backup, onPage = { page = it })
    }
    BackHandler(enabled = page != SettingsPage.MAIN && keyBar == null) @DontMemoize { page = SettingsPage.MAIN }
}

@Composable
private fun SettingsList(
    settings: Settings,
    viewModel: SettingsViewModel,
    backup: BackupViewModel,
    onPage: (SettingsPage) -> Unit,
) {
    var choice by rememberSaveable { mutableStateOf<SettingsChoice?>(null) }
    Scaffold(topBar = { PageBar(R.string.settings_title, onBack = null) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            appearance(settings, viewModel, onChoice = { choice = it })
            keyboard(settings, onChoice = { choice = it }, onEditJson = viewModel::openKeyBarEditor)
            security(settings, viewModel)
            notifications(settings, viewModel)
            item {
                SectionHeader(R.string.settings_claude)
                SwitchRow(
                    title = stringResource(R.string.settings_use_helper),
                    summary = stringResource(R.string.settings_use_helper_summary),
                    checked = settings.useHelperByDefault,
                    onChange = viewModel::setUseHelper,
                )
                SectionHeader(R.string.settings_privacy)
                SwitchRow(
                    title = stringResource(R.string.settings_crash_reports),
                    summary = stringResource(R.string.settings_crash_reports_summary),
                    checked = settings.crashReportingOptIn,
                    onChange = viewModel::setCrashReporting,
                )
            }
            item { BackupSection(backup) }
            about(onPage)
        }
    }
    choice?.let { SettingsChoiceDialog(it, settings, viewModel, onDismiss = { choice = null }) }
}

private fun LazyListScope.appearance(
    settings: Settings,
    viewModel: SettingsViewModel,
    onChoice: (SettingsChoice) -> Unit,
) {
    item {
        SectionHeader(R.string.settings_appearance)
        ClickRow(stringResource(R.string.settings_theme), {
            onChoice(SettingsChoice.THEME)
        }, stringResource(themeLabel(settings.theme)))
        ClickRow(
            stringResource(R.string.settings_font),
            { onChoice(SettingsChoice.FONT) },
            stringResource(fontLabel(TerminalFont.of(settings.fontFamilyId))),
        )
        // The slider moves locally and the size is stored once, when the drag ends: storing every
        // step meant a disk write per frame. Rounded, as a step's float may be 13.999...
        var position by remember(settings.terminalFontSizeSp) {
            mutableFloatStateOf(settings.terminalFontSizeSp.toFloat())
        }
        val sizeLabel = stringResource(R.string.settings_font_size, position.roundToInt())
        ListItem(
            headlineContent = { Text(sizeLabel) },
            supportingContent = {
                Slider(
                    value = position,
                    onValueChange = { position = it },
                    onValueChangeFinished = { viewModel.setFontSize(position.roundToInt()) },
                    valueRange = Settings.MIN_FONT_SIZE_SP.toFloat()..Settings.MAX_FONT_SIZE_SP.toFloat(),
                    steps = Settings.MAX_FONT_SIZE_SP - Settings.MIN_FONT_SIZE_SP - 1,
                    modifier = Modifier.semantics { contentDescription = sizeLabel },
                )
            },
        )
        SwitchRow(stringResource(R.string.settings_keep_screen_on), settings.keepScreenOn, viewModel::setKeepScreenOn)
        SwitchRow(
            title = stringResource(R.string.settings_compose_enter_sends),
            summary = stringResource(R.string.settings_compose_enter_sends_summary),
            checked = settings.composeEnterSends,
            onChange = viewModel::setComposeEnterSends,
        )
        SwitchRow(
            title = stringResource(R.string.settings_compose_history),
            summary = stringResource(R.string.settings_compose_history_summary),
            checked = settings.rememberComposeHistory,
            onChange = viewModel::setRememberComposeHistory,
        )
    }
}

private fun LazyListScope.keyboard(
    settings: Settings,
    onChoice: (SettingsChoice) -> Unit,
    onEditJson: () -> Unit,
) {
    item {
        SectionHeader(R.string.settings_keyboard)
        ClickRow(
            stringResource(R.string.settings_key_bar),
            { onChoice(SettingsChoice.KEY_BAR) },
            stringResource(presetLabel(KeyBarPreset.of(settings.keyBarLayout))),
        )
        ClickRow(
            stringResource(R.string.settings_key_bar_edit),
            onEditJson,
            stringResource(R.string.settings_key_bar_edit_summary),
        )
        ClickRow(
            stringResource(R.string.settings_backspace),
            { onChoice(SettingsChoice.BACKSPACE) },
            stringResource(backspaceLabel(settings.backspaceMode)),
        )
    }
}

private fun LazyListScope.security(
    settings: Settings,
    viewModel: SettingsViewModel,
) {
    item {
        SectionHeader(R.string.settings_security)
        val available = viewModel.lockAvailable
        SwitchRow(
            title = stringResource(R.string.settings_app_lock),
            summary =
                stringResource(
                    if (available) R.string.settings_app_lock_summary else R.string.settings_app_lock_unavailable,
                ),
            checked = settings.appLockEnabled && available,
            enabled = available,
            onChange = viewModel::setAppLock,
        )
        SwitchRow(
            title = stringResource(R.string.settings_secure_screen),
            summary = stringResource(R.string.settings_secure_screen_summary),
            checked = settings.secureScreenEnabled,
            onChange = viewModel::setSecureScreen,
        )
    }
}

private fun LazyListScope.notifications(
    settings: Settings,
    viewModel: SettingsViewModel,
) {
    item {
        SectionHeader(R.string.settings_notifications)
        SwitchRow(
            stringResource(R.string.settings_notifications_all),
            settings.notificationsEnabled,
            viewModel::setNotifications,
        )
        NotificationEvent.entries.forEach { event ->
            SwitchRow(
                title = stringResource(eventLabel(event)),
                checked = settings.notificationsEnabled && event in settings.notificationEvents,
                enabled = settings.notificationsEnabled,
                onChange = { viewModel.setNotificationEvent(event, it) },
            )
        }
    }
}

private fun LazyListScope.about(onPage: (SettingsPage) -> Unit) {
    item {
        val uriHandler = LocalUriHandler.current
        SectionHeader(R.string.settings_about)
        ListItem(
            headlineContent = { Text(stringResource(R.string.app_name)) },
            supportingContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
        )
        ClickRow(stringResource(R.string.settings_licences), { onPage(SettingsPage.LICENCES) })
        ClickRow(stringResource(R.string.settings_privacy_statement), { onPage(SettingsPage.PRIVACY) })
        ClickRow(
            stringResource(R.string.settings_source),
            { uriHandler.tryOpen(Licences.SOURCE_URL) },
            Licences.SOURCE_URL,
        )
    }
}

@Composable
private fun SettingsChoiceDialog(
    choice: SettingsChoice,
    settings: Settings,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    when (choice) {
        SettingsChoice.THEME ->
            ChoiceDialog(R.string.settings_theme, ThemeMode.entries, settings.theme, {
                stringResource(themeLabel(it))
            }, @DontMemoize { viewModel.setTheme(it) }, onDismiss)
        SettingsChoice.FONT ->
            ChoiceDialog(
                R.string.settings_font,
                TerminalFont.entries,
                TerminalFont.of(settings.fontFamilyId),
                { stringResource(fontLabel(it)) },
                @DontMemoize { viewModel.setFont(it) },
                onDismiss,
            )
        SettingsChoice.KEY_BAR ->
            ChoiceDialog(
                R.string.settings_key_bar,
                KeyBarPreset.entries,
                KeyBarPreset.of(settings.keyBarLayout),
                { stringResource(presetLabel(it)) },
                @DontMemoize { viewModel.setKeyBarPreset(it) },
                onDismiss,
            )
        SettingsChoice.BACKSPACE ->
            ChoiceDialog(
                R.string.settings_backspace,
                BackspaceMode.entries,
                settings.backspaceMode,
                { stringResource(backspaceLabel(it)) },
                @DontMemoize { viewModel.setBackspaceMode(it) },
                onDismiss,
            )
    }
}

internal fun themeLabel(theme: ThemeMode): Int =
    when (theme) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.DYNAMIC -> R.string.theme_dynamic
    }

internal fun fontLabel(font: TerminalFont): Int =
    when (font) {
        TerminalFont.MONOSPACE -> R.string.font_monospace
        TerminalFont.SERIF_MONOSPACE -> R.string.font_serif_monospace
    }

internal fun presetLabel(preset: KeyBarPreset): Int =
    when (preset) {
        KeyBarPreset.DEFAULT -> R.string.key_bar_default
        KeyBarPreset.CLAUDE -> R.string.key_bar_claude
        KeyBarPreset.CUSTOM -> R.string.key_bar_custom
    }

internal fun backspaceLabel(mode: BackspaceMode): Int =
    when (mode) {
        BackspaceMode.DEL -> R.string.backspace_del
        BackspaceMode.CTRL_H -> R.string.backspace_ctrl_h
    }

internal fun eventLabel(event: NotificationEvent): Int =
    when (event) {
        NotificationEvent.PERMISSION_REQUEST -> R.string.event_permission
        NotificationEvent.WAITING_FOR_INPUT -> R.string.event_waiting
        NotificationEvent.SESSION_EXITED -> R.string.event_exited
        NotificationEvent.CONNECTION_LOST -> R.string.event_connection_lost
    }
