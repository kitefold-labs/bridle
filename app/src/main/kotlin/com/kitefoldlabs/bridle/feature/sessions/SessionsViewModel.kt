package com.kitefoldlabs.bridle.feature.sessions

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.data.LaunchProfileRepository
import com.kitefoldlabs.bridle.core.data.settings.Settings
import com.kitefoldlabs.bridle.core.data.settings.SettingsRepository
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.RestartMode
import com.kitefoldlabs.bridle.core.tmux.SessionSummary
import com.kitefoldlabs.bridle.core.tmux.TmuxController
import com.kitefoldlabs.bridle.core.tmux.TmuxNotInstalledException
import com.kitefoldlabs.bridle.feature.terminal.TerminalLauncher
import com.kitefoldlabs.bridle.feature.terminal.TerminalNavigator
import com.kitefoldlabs.bridle.service.SessionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A short message for the snackbar: a string resource with an optional argument. */
data class UserMessage(
    @param:StringRes val text: Int,
    val arg: String? = null,
)

/** A confirmation the sessions screen is asking for. */
sealed interface SessionsDialog {
    data class ConfirmStop(
        val session: String,
    ) : SessionsDialog

    data class ConfirmDeleteProfile(
        val profile: LaunchProfile,
    ) : SessionsDialog

    /** Consent to install bridle-helper, showing its checksum; optionally with Claude Code hooks. */
    data class InstallHelper(
        val installHooks: Boolean = true,
    ) : SessionsDialog

    data object ConfirmUninstallHelper : SessionsDialog

    /** Consent to upload the shell integration and edit the rc files, showing checksums. */
    data object InstallShellIntegration : SessionsDialog

    data object ConfirmUninstallShellIntegration : SessionsDialog
}

/** Everything the sessions screen shows. */
data class SessionsUiState(
    val hosts: List<Host> = emptyList(),
    val selectedHostId: EntityId? = null,
    val status: HostStatus = HostStatus(),
    val profiles: List<LaunchProfile> = emptyList(),
    val settings: Settings = Settings(),
    val openTabs: Int = 0,
    val busy: Boolean = false,
    val dialog: SessionsDialog? = null,
    val editor: ProfileForm? = null,
    val message: UserMessage? = null,
)

/** What the sessions screen can ask for. Implemented by [SessionsViewModel]. */
@Suppress("TooManyFunctions") // One per user action; each is a one-liner.
interface SessionsActions {
    fun selectHost(hostId: EntityId)

    fun refresh()

    /** Reconnects the selected host now (after a failure). */
    fun reconnect()

    fun showTerminals()

    fun attach(session: String)

    fun restartSession(
        summary: SessionSummary,
        fresh: Boolean,
    )

    fun requestStop(session: String)

    fun quickReply(
        session: String,
        reply: QuickReply,
    )

    fun startProfile(profile: LaunchProfile)

    fun restartProfile(
        profile: LaunchProfile,
        fresh: Boolean,
    )

    fun newProfile()

    fun editProfile(profile: LaunchProfile)

    fun updateEditor(form: ProfileForm)

    fun saveEditor()

    fun cancelEditor()

    fun requestDeleteProfile(profile: LaunchProfile)

    fun requestInstallHelper()

    fun setInstallHooks(enabled: Boolean)

    fun requestUninstallHelper()

    fun requestInstallShellIntegration()

    fun requestUninstallShellIntegration()

    fun confirmDialog()

    fun dismissDialog()

    fun messageShown()
}

/**
 * The sessions screen: per host, its tmux sessions with Claude status (from [ClaudeStatusHub]),
 * its Launch Profiles, and what the host offers. Actions run over the host's shared connection.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModel
    @Inject
    constructor(
        hostRepository: HostRepository,
        private val profileRepository: LaunchProfileRepository,
        settingsRepository: SettingsRepository,
        private val hub: ClaudeStatusHub,
        private val coordinator: SessionCoordinator,
        private val launcher: TerminalLauncher,
        private val navigator: TerminalNavigator,
        private val savedState: SavedStateHandle,
    ) : ViewModel(),
        SessionsActions {
        private data class Local(
            val busy: Boolean = false,
            val dialog: SessionsDialog? = null,
            val editor: ProfileForm? = null,
            val message: UserMessage? = null,
        )

        private val local = MutableStateFlow(Local())

        private val hosts: StateFlow<List<Host>> =
            hostRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** The host picked by the user, else the first one (most recently used). */
        private val selectedHost: StateFlow<EntityId?> =
            combine(hosts, savedState.getStateFlow(KEY_HOST, NO_HOST)) { list, id ->
                (list.firstOrNull { it.id == id } ?: list.firstOrNull())?.id
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val profiles: StateFlow<List<LaunchProfile>> =
            selectedHost
                .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else profileRepository.observeForHost(id) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        // Watching a host keeps it connected and polled, so only while the screen collects this.
        private val status =
            selectedHost.flatMapLatest { id -> if (id == null) flowOf(HostStatus()) else hub.observe(id) }

        private val settings: StateFlow<Settings> =
            settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

        val uiState: StateFlow<SessionsUiState> =
            combine(
                combine(hosts, selectedHost, status, profiles, ::HostPart),
                settings,
                coordinator.tabs.map { it.size },
                local,
            ) { host, current, tabs, ui ->
                SessionsUiState(
                    hosts = host.hosts,
                    selectedHostId = host.selected,
                    status = host.status,
                    profiles = host.profiles,
                    settings = current,
                    openTabs = tabs,
                    busy = ui.busy,
                    dialog = ui.dialog,
                    editor = ui.editor,
                    message = ui.message,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SessionsUiState())

        private data class HostPart(
            val hosts: List<Host>,
            val selected: EntityId?,
            val status: HostStatus,
            val profiles: List<LaunchProfile>,
        )

        override fun selectHost(hostId: EntityId) {
            savedState[KEY_HOST] = hostId
        }

        override fun refresh() {
            selectedHost.value?.let(hub::refresh)
        }

        override fun reconnect() {
            val hostId = selectedHost.value ?: return
            viewModelScope.launch { coordinator.connections.reconnect(hostId) }
        }

        override fun showTerminals() {
            navigator.showTerminals()
        }

        override fun attach(session: String) {
            act { hostId -> launcher.attachTmux(hostId, session) }
        }

        override fun restartSession(
            summary: SessionSummary,
            fresh: Boolean,
        ) {
            val hostId = selectedHost.value ?: return
            val name = summary.session.name
            val profile =
                profiles.value.firstOrNull { it.sessionName == name }
                    ?: LaunchProfile.claude(hostId, summary.activePane?.currentPath?.ifBlank { null } ?: "~", name)
            restartProfile(profile, fresh)
        }

        override fun requestStop(session: String) {
            showDialog(SessionsDialog.ConfirmStop(session))
        }

        override fun quickReply(
            session: String,
            reply: QuickReply,
        ) {
            withTmux(success = null) { reply.send(it, session) }
        }

        override fun startProfile(profile: LaunchProfile) {
            act { hostId ->
                coordinator.withTmux(hostId) { it.launch(profile) }
                launcher.attachTmux(hostId, profile.sessionName, profile.workingDir)
            }
        }

        override fun restartProfile(
            profile: LaunchProfile,
            fresh: Boolean,
        ) {
            val target =
                if (fresh && profile.restartMode == RestartMode.RESPAWN_CONTINUE) {
                    profile.copy(restartMode = RestartMode.RESPAWN_FRESH)
                } else {
                    profile
                }
            withTmux(UserMessage(R.string.sessions_restarted, target.sessionName)) { it.restart(target) }
        }

        override fun newProfile() {
            val hostId = selectedHost.value ?: return
            val taken = profiles.value.map { it.sessionName }.toSet()
            local.update { it.copy(editor = ProfileForm.claudePreset(hostId, taken)) }
        }

        override fun editProfile(profile: LaunchProfile) {
            local.update { it.copy(editor = ProfileForm.from(profile)) }
        }

        override fun updateEditor(form: ProfileForm) {
            local.update { it.copy(editor = form) }
        }

        override fun saveEditor() {
            val form = local.value.editor?.takeIf { it.isValid } ?: return
            act {
                profileRepository.upsert(form.toProfile())
                local.update {
                    it.copy(
                        editor = null,
                        message = UserMessage(R.string.sessions_profile_saved, form.name),
                    )
                }
            }
        }

        override fun cancelEditor() {
            local.update { it.copy(editor = null) }
        }

        override fun requestDeleteProfile(profile: LaunchProfile) {
            showDialog(SessionsDialog.ConfirmDeleteProfile(profile))
        }

        override fun requestInstallHelper() {
            showDialog(SessionsDialog.InstallHelper())
        }

        override fun setInstallHooks(enabled: Boolean) {
            local.update { state ->
                val dialog = state.dialog as? SessionsDialog.InstallHelper ?: return@update state
                state.copy(dialog = dialog.copy(installHooks = enabled))
            }
        }

        override fun requestUninstallHelper() {
            showDialog(SessionsDialog.ConfirmUninstallHelper)
        }

        override fun requestInstallShellIntegration() {
            showDialog(SessionsDialog.InstallShellIntegration)
        }

        override fun requestUninstallShellIntegration() {
            showDialog(SessionsDialog.ConfirmUninstallShellIntegration)
        }

        override fun confirmDialog() {
            val dialog = local.value.dialog ?: return
            local.update { it.copy(dialog = null) }
            when (dialog) {
                is SessionsDialog.ConfirmStop ->
                    withTmux(UserMessage(R.string.sessions_stopped, dialog.session)) { it.kill(dialog.session) }
                is SessionsDialog.ConfirmDeleteProfile ->
                    act {
                        profileRepository.delete(dialog.profile.id)
                        showMessage(UserMessage(R.string.sessions_profile_deleted, dialog.profile.name))
                    }
                is SessionsDialog.InstallHelper ->
                    withTmux(UserMessage(R.string.sessions_helper_installed)) { it.installHelper(dialog.installHooks) }
                SessionsDialog.ConfirmUninstallHelper ->
                    withTmux(UserMessage(R.string.sessions_helper_removed)) { it.uninstallHelper() }
                SessionsDialog.InstallShellIntegration ->
                    withTmux(UserMessage(R.string.sessions_shell_installed)) { it.installShellIntegration() }
                SessionsDialog.ConfirmUninstallShellIntegration ->
                    withTmux(UserMessage(R.string.sessions_shell_removed)) { it.uninstallShellIntegration() }
            }
        }

        override fun dismissDialog() {
            local.update { it.copy(dialog = null) }
        }

        override fun messageShown() {
            local.update { it.copy(message = null) }
        }

        private fun showDialog(dialog: SessionsDialog) {
            local.update { it.copy(dialog = dialog) }
        }

        private fun showMessage(message: UserMessage) {
            local.update { it.copy(message = message) }
        }

        private fun withTmux(
            success: UserMessage?,
            block: suspend (TmuxController) -> Unit,
        ) {
            act { hostId ->
                coordinator.withTmux(hostId, block)
                success?.let(::showMessage)
            }
        }

        /** Runs [block] for the selected host, showing progress and turning failures into a message. */
        private fun act(block: suspend (EntityId) -> Unit) {
            val hostId = selectedHost.value ?: return
            viewModelScope.launch {
                local.update { it.copy(busy = true) }
                try {
                    block(hostId)
                    hub.refresh(hostId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: TmuxNotInstalledException) {
                    showMessage(UserMessage(R.string.sessions_error_no_tmux))
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    showMessage(UserMessage(R.string.sessions_error, e.describe()))
                } finally {
                    local.update { it.copy(busy = false) }
                }
            }
        }

        private companion object {
            const val KEY_HOST = "host"
            const val NO_HOST = -1L
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
