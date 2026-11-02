package com.kitefoldlabs.bridle.feature.sessions

import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.LaunchMode
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.RestartMode
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.core.model.Validators

/** One environment variable row in the profile editor. */
data class EnvVar(
    val name: String = "",
    val value: String = "",
)

/** The profile editor's text fields. All but [RESTART_COMMAND] are validated. */
enum class ProfileField { NAME, SESSION_NAME, WORKING_DIR, COMMAND, RESTART_COMMAND }

/** Why an environment variable row is invalid. */
enum class EnvError { BLANK, ILLEGAL_CHARACTERS, DUPLICATE }

/**
 * The Launch Profile editor's contents. Validation follows [LaunchProfile.validate] and
 * [Validators], but reports every field at once so the form can mark each one.
 */
data class ProfileForm(
    val id: EntityId = 0,
    val hostId: EntityId,
    val name: String,
    val sessionName: String,
    val workingDir: String,
    val command: String,
    val restartCommand: String,
    val restartMode: RestartMode = RestartMode.RESPAWN_CONTINUE,
    val mode: LaunchMode = LaunchMode.SHELL_HOSTED,
    val env: List<EnvVar> = emptyList(),
    val pinned: Boolean = false,
) {
    val isNew: Boolean get() = id == 0L

    /** Problems by field; empty when the fields are valid. */
    val errors: Map<ProfileField, Validation.Reason>
        get() =
            buildMap {
                reason(Validators.label(name))?.let { put(ProfileField.NAME, it) }
                reason(Validators.sessionName(sessionName))?.let { put(ProfileField.SESSION_NAME, it) }
                // The same rule as attaching: a directory Start can't attach in is refused here.
                reason(Validators.workingDirectory(workingDir.trim()))?.let { put(ProfileField.WORKING_DIR, it) }
                if (command.isBlank()) put(ProfileField.COMMAND, Validation.Reason.BLANK)
            }

    /** One entry per [env] row: its problem, or null. */
    val envErrors: List<EnvError?>
        get() {
            val counts = env.groupingBy { it.name }.eachCount()
            return env.map { row ->
                when (reason(Validators.envName(row.name))) {
                    null -> if (counts.getValue(row.name) > 1) EnvError.DUPLICATE else null
                    Validation.Reason.BLANK -> EnvError.BLANK
                    else -> EnvError.ILLEGAL_CHARACTERS
                }
            }
        }

    val isValid: Boolean get() = errors.isEmpty() && envErrors.all { it == null }

    /** The text of [field]. */
    fun text(field: ProfileField): String =
        when (field) {
            ProfileField.NAME -> name
            ProfileField.SESSION_NAME -> sessionName
            ProfileField.WORKING_DIR -> workingDir
            ProfileField.COMMAND -> command
            ProfileField.RESTART_COMMAND -> restartCommand
        }

    /** This form with [field] set to [value]. */
    fun withText(
        field: ProfileField,
        value: String,
    ): ProfileForm =
        when (field) {
            ProfileField.NAME -> copy(name = value)
            ProfileField.SESSION_NAME -> copy(sessionName = value)
            ProfileField.WORKING_DIR -> copy(workingDir = value)
            ProfileField.COMMAND -> copy(command = value)
            ProfileField.RESTART_COMMAND -> copy(restartCommand = value)
        }

    fun withRestartMode(value: RestartMode): ProfileForm = copy(restartMode = value)

    fun withMode(value: LaunchMode): ProfileForm = copy(mode = value)

    fun withPinned(value: Boolean): ProfileForm = copy(pinned = value)

    /** This form with environment row [index] replaced by [row]. */
    fun withEnv(
        index: Int,
        row: EnvVar,
    ): ProfileForm = copy(env = env.toMutableList().also { it[index] = row })

    fun withoutEnv(index: Int): ProfileForm = copy(env = env.filterIndexed { i, _ -> i != index })

    fun withNewEnv(): ProfileForm = copy(env = env + EnvVar())

    fun toProfile(): LaunchProfile =
        LaunchProfile(
            id = id,
            hostId = hostId,
            name = name.trim(),
            sessionName = sessionName.trim(),
            workingDir = workingDir.trim(),
            command = command.trim(),
            restartMode = restartMode,
            restartCommand = restartCommand.trim().ifEmpty { null },
            env = env.associate { it.name to it.value },
            mode = mode,
            pinned = pinned,
        )

    companion object {
        /** A form showing [profile] for editing. */
        fun from(profile: LaunchProfile): ProfileForm =
            ProfileForm(
                id = profile.id,
                hostId = profile.hostId,
                name = profile.name,
                sessionName = profile.sessionName,
                workingDir = profile.workingDir,
                command = profile.command,
                restartCommand = profile.restartCommand.orEmpty(),
                restartMode = profile.restartMode,
                mode = profile.mode,
                env = profile.env.map { (name, value) -> EnvVar(name, value) },
                pinned = profile.pinned,
            )

        /**
         * A new profile from the Claude Code preset (`claude`, restarting with `claude --continue`)
         * in the home directory, with a session name not in [takenSessionNames].
         */
        fun claudePreset(
            hostId: EntityId,
            takenSessionNames: Set<String>,
        ): ProfileForm {
            val session =
                generateSequence(1) { it + 1 }
                    .map { if (it == 1) CLAUDE_SESSION else "$CLAUDE_SESSION-$it" }
                    .first { it !in takenSessionNames }
            return from(LaunchProfile.claude(hostId, workingDir = "~", sessionName = session))
        }

        private const val CLAUDE_SESSION = "claude"

        private fun reason(validation: Validation): Validation.Reason? = (validation as? Validation.Invalid)?.reason
    }
}
