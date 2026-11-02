package com.kitefoldlabs.bridle.feature.sessions

import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.LaunchMode
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.RestartMode
import com.kitefoldlabs.bridle.core.model.Validation
import io.kotest.matchers.shouldBe
import org.junit.Test

class ProfileFormTest {
    private val preset = ProfileForm.claudePreset(7, emptySet())

    @Test
    fun `the claude preset continues the last conversation on restart`() {
        preset.command shouldBe "claude"
        preset.restartCommand shouldBe "claude --continue"
        preset.restartMode shouldBe RestartMode.RESPAWN_CONTINUE
        preset.workingDir shouldBe "~"
        preset.sessionName shouldBe "claude"
        preset.isNew shouldBe true
        preset.isValid shouldBe true
        ProfileForm.claudePreset(7, setOf("claude")).sessionName shouldBe "claude-2"
        ProfileForm.claudePreset(7, setOf("claude", "claude-2")).sessionName shouldBe "claude-3"
    }

    @Test
    fun `every field reports its own problem`() {
        preset.copy(name = " ").errors shouldBe mapOf(ProfileField.NAME to Validation.Reason.BLANK)
        preset.copy(name = "x".repeat(65)).errors shouldBe mapOf(ProfileField.NAME to Validation.Reason.TOO_LONG)
        preset.copy(name = "a\u0007").errors shouldBe mapOf(ProfileField.NAME to Validation.Reason.ILLEGAL_CHARACTERS)
        val bad = preset.copy(sessionName = "a b", workingDir = " ", command = "")
        bad.errors shouldBe
            mapOf(
                ProfileField.SESSION_NAME to Validation.Reason.ILLEGAL_CHARACTERS,
                ProfileField.WORKING_DIR to Validation.Reason.BLANK,
                ProfileField.COMMAND to Validation.Reason.BLANK,
            )
        bad.isValid shouldBe false
    }

    @Test
    fun `environment variables must be named, valid and unique`() {
        val form = preset.copy(env = listOf(EnvVar("A", "1"), EnvVar(""), EnvVar("1X"), EnvVar("A", "2"), EnvVar("B")))
        form.envErrors shouldBe
            listOf(EnvError.DUPLICATE, EnvError.BLANK, EnvError.ILLEGAL_CHARACTERS, EnvError.DUPLICATE, null)
        form.isValid shouldBe false
        preset.copy(env = listOf(EnvVar("NODE_ENV", "dev"))).isValid shouldBe true
    }

    @Test
    fun `converts to and from a profile`() {
        val profile =
            preset
                .copy(
                    name = " API ",
                    sessionName = "api ",
                    workingDir = " ~/src/api",
                    command = " claude ",
                    restartCommand = "  ",
                    restartMode = RestartMode.SEND_KEYS,
                    mode = LaunchMode.DIRECT,
                    env = listOf(EnvVar("A", "1")),
                    pinned = true,
                ).toProfile()
        profile shouldBe
            LaunchProfile(
                hostId = 7,
                name = "API",
                sessionName = "api",
                workingDir = "~/src/api",
                command = "claude",
                restartMode = RestartMode.SEND_KEYS,
                restartCommand = null,
                env = mapOf("A" to "1"),
                mode = LaunchMode.DIRECT,
                pinned = true,
            )
        val back = ProfileForm.from(profile.copy(id = 3))
        back.isNew shouldBe false
        back.restartCommand shouldBe ""
        back.env shouldBe listOf(EnvVar("A", "1"))
        back.toProfile() shouldBe profile.copy(id = 3)
    }

    @Test
    fun `fields are read and edited one at a time`() {
        for (field in ProfileField.entries) {
            val edited = preset.withText(field, "x-${field.name}")
            edited.text(field) shouldBe "x-${field.name}"
            ProfileField.entries.filter { it != field }.forEach { other ->
                edited.text(other) shouldBe
                    preset.text(other)
            }
        }
        preset.withRestartMode(RestartMode.SEND_KEYS).restartMode shouldBe RestartMode.SEND_KEYS
        preset.withMode(LaunchMode.DIRECT).mode shouldBe LaunchMode.DIRECT
        preset.withPinned(true).pinned shouldBe true
        val env = preset.withNewEnv().withNewEnv().withEnv(1, EnvVar("A", "1"))
        env.env shouldBe listOf(EnvVar(), EnvVar("A", "1"))
        env.withoutEnv(0).env shouldBe listOf(EnvVar("A", "1"))
    }

    @Test
    fun `labels exist for every error and mode`() {
        fieldError(ProfileField.NAME, Validation.Reason.BLANK) shouldBe R.string.profile_error_required
        fieldError(ProfileField.NAME, Validation.Reason.TOO_LONG) shouldBe R.string.profile_error_too_long
        fieldError(ProfileField.NAME, Validation.Reason.ILLEGAL_CHARACTERS) shouldBe
            R.string.profile_error_control_chars
        fieldError(ProfileField.SESSION_NAME, Validation.Reason.ILLEGAL_CHARACTERS) shouldBe
            R.string.profile_error_session_chars
        envError(EnvError.BLANK) shouldBe R.string.profile_error_required
        envError(EnvError.ILLEGAL_CHARACTERS) shouldBe R.string.profile_error_env_chars
        envError(EnvError.DUPLICATE) shouldBe R.string.profile_error_env_duplicate
        RestartMode.entries
            .map(::restartModeLabel)
            .toSet()
            .size shouldBe RestartMode.entries.size
        LaunchMode.entries
            .map(::launchModeLabel)
            .toSet()
            .size shouldBe LaunchMode.entries.size
    }
}
