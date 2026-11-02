package com.kitefoldlabs.bridle.ui

import android.app.Application
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kitefoldlabs.bridle.core.data.settings.ThemeMode
import com.kitefoldlabs.bridle.ui.nav.BridleNavHost
import com.kitefoldlabs.bridle.ui.nav.TopLevel
import com.kitefoldlabs.bridle.ui.theme.BridleTheme
import com.kitefoldlabs.bridle.ui.theme.DarkColors
import com.kitefoldlabs.bridle.ui.theme.LightColors
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `bottom bar switches destinations`() {
        rule.setContent { BridleTheme { BridleNavHost(mapOf(TopLevel.KEYS to { Text("keys screen") })) } }
        rule.onAllNodesWithText("Hosts").fetchSemanticsNodes().size shouldBe 2 // tab label + placeholder
        rule.onNodeWithText("Keys").performClick()
        rule.onNodeWithText("keys screen").assertExists()
        rule.onNodeWithText("Settings").performClick()
        rule.onAllNodesWithText("Settings").fetchSemanticsNodes().size shouldBe 2
    }

    @Test
    fun `the terminal destination is full screen and goes back`() {
        val requests = Channel<Unit>(Channel.CONFLATED)
        rule.setContent {
            BridleTheme {
                BridleNavHost(
                    terminal = { onBack -> Button(onClick = onBack) { Text("terminal screen") } },
                    showTerminal = requests.receiveAsFlow(),
                )
            }
        }
        rule.onNodeWithText("terminal screen").assertDoesNotExist()
        requests.trySend(Unit)
        rule.onNodeWithText("terminal screen").assertExists()
        rule.onNodeWithText("Keys").assertDoesNotExist() // no bottom bar
        rule.onNodeWithText("terminal screen").performClick()
        rule.onNodeWithText("Keys").assertExists()
    }

    @Test
    fun `theme modes pick the right scheme`() {
        var primary = 0
        var mode by mutableStateOf(ThemeMode.LIGHT)
        rule.setContent { BridleTheme(mode) { primary = MaterialTheme.colorScheme.primary.toArgb() } }
        rule.waitForIdle()
        primary shouldBe LightColors.primary.toArgb()
        mode = ThemeMode.DARK
        rule.waitForIdle()
        primary shouldBe DarkColors.primary.toArgb()
    }

    @Test
    @Config(sdk = [30])
    fun `system and dynamic fall back on older devices`() {
        var primary = 0
        rule.setContent { BridleTheme(ThemeMode.DYNAMIC) { primary = MaterialTheme.colorScheme.primary.toArgb() } }
        rule.waitForIdle()
        primary shouldBe LightColors.primary.toArgb()
    }

    @Test
    fun `dynamic color on android 12 plus and system mode`() {
        var mode by mutableStateOf(ThemeMode.SYSTEM)
        rule.setContent { BridleTheme(mode) { Text(mode.name) } }
        rule.onNodeWithText("SYSTEM").assertExists()
        mode = ThemeMode.DYNAMIC
        rule.onNodeWithText("DYNAMIC").assertExists()
    }
}
