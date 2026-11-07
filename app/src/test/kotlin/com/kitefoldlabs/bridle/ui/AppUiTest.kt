package com.kitefoldlabs.bridle.ui

import android.app.Application
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kitefoldlabs.bridle.core.data.settings.ThemeMode
import com.kitefoldlabs.bridle.feature.forwards.ForwardsDestination
import com.kitefoldlabs.bridle.feature.sftp.SftpDestination
import com.kitefoldlabs.bridle.feature.snippets.SnippetsDestination
import com.kitefoldlabs.bridle.ui.nav.BridleNavHost
import com.kitefoldlabs.bridle.ui.nav.HostTool
import com.kitefoldlabs.bridle.ui.nav.HostTools
import com.kitefoldlabs.bridle.ui.nav.LocalTabRequests
import com.kitefoldlabs.bridle.ui.nav.OnTabRequest
import com.kitefoldlabs.bridle.ui.nav.TabRequest
import com.kitefoldlabs.bridle.ui.nav.TabRequests
import com.kitefoldlabs.bridle.ui.nav.TopLevel
import com.kitefoldlabs.bridle.ui.nav.openTab
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
    fun `bottom bar returns to the start tab after a tab was opened from outside it`() {
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            BridleTheme {
                BridleNavHost(
                    mapOf(
                        TopLevel.HOSTS to { Text("hosts screen") },
                        TopLevel.KEYS to { Text("keys screen") },
                    ),
                    nav = nav,
                )
            }
        }
        rule.runOnUiThread { nav.openTab(TopLevel.KEYS) } // as onboarding's "Generate a key" does
        rule.onNodeWithText("keys screen").assertExists()
        rule.onNodeWithText("Hosts").performClick()
        rule.onNodeWithText("hosts screen").assertExists()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Hosts").performClick()
        rule.onNodeWithText("hosts screen").assertExists()
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
    fun `showing the terminal again returns to it, closing what was opened above`() {
        val requests = Channel<Unit>(Channel.CONFLATED)
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            BridleTheme {
                BridleNavHost(
                    nav = nav,
                    routes = { composable("tool") { Text("tool screen") } },
                    terminal = { onBack -> Button(onClick = onBack) { Text("terminal screen") } },
                    showTerminal = requests.receiveAsFlow(),
                )
            }
        }
        requests.trySend(Unit)
        rule.onNodeWithText("terminal screen").assertExists()
        rule.runOnUiThread { nav.navigate("tool") }
        rule.onNodeWithText("tool screen").assertExists()
        requests.trySend(Unit)
        rule.onNodeWithText("terminal screen").assertExists()
        rule.onNodeWithText("terminal screen").performClick() // back: to Hosts, not the tool or another terminal
        rule.onNodeWithText("Keys").assertExists()
        nav.currentBackStackEntry!!.destination.route shouldBe TopLevel.HOSTS.route
    }

    @Test
    fun `host tools open their routes, and do nothing without navigation`() {
        HostTool.FILES.route(4) shouldBe SftpDestination.route(4)
        HostTool.SNIPPETS.route(4) shouldBe SnippetsDestination.route(4)
        HostTool.FORWARDS.route(4) shouldBe ForwardsDestination.route(4)
        HostTools(null).open(4, HostTool.FILES)
    }

    @Test
    fun `tab requests are handled once, by the tab they name, also when made before it shows`() {
        val requests = TabRequests()
        requests.request(TabRequest.ADD_HOST)
        var shown by mutableStateOf(false)
        var provided by mutableStateOf(requests)
        val opened = mutableListOf<TabRequest>()
        rule.setContent {
            OnTabRequest(TabRequest.GENERATE_KEY) { opened += TabRequest.GENERATE_KEY } // not provided: inert
            CompositionLocalProvider(LocalTabRequests provides provided) {
                if (shown) OnTabRequest(TabRequest.ADD_HOST) { opened += TabRequest.ADD_HOST }
            }
        }
        rule.waitForIdle()
        opened shouldBe emptyList()
        shown = true
        rule.waitForIdle()
        opened shouldBe listOf(TabRequest.ADD_HOST)
        requests.request(TabRequest.GENERATE_KEY) // nobody here handles it
        rule.waitForIdle()
        requests.request(TabRequest.ADD_HOST)
        rule.waitForIdle()
        opened shouldBe listOf(TabRequest.ADD_HOST, TabRequest.ADD_HOST)
        // Another app's requests (an activity recreated): its waiting request is handled too.
        provided = TabRequests().apply { request(TabRequest.ADD_HOST) }
        rule.waitForIdle()
        opened shouldBe List(3) { TabRequest.ADD_HOST }
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
