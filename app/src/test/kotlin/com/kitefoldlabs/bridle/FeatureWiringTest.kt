package com.kitefoldlabs.bridle

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.composable
import com.kitefoldlabs.bridle.feature.forwards.ForwardsDestination
import com.kitefoldlabs.bridle.feature.settings.SettingsRoute
import com.kitefoldlabs.bridle.feature.sftp.SftpDestination
import com.kitefoldlabs.bridle.feature.snippets.SnippetsDestination
import com.kitefoldlabs.bridle.ui.BridleApp
import com.kitefoldlabs.bridle.ui.nav.LocalNavController
import com.kitefoldlabs.bridle.ui.nav.TopLevel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The feature screens resolve their Hilt ViewModels and routes through the real app graph. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class FeatureWiringTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) =
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `first run shows onboarding, then the settings tab`() {
        waitForText("Skip")
        rule.onNodeWithText("Skip").performClick()
        rule.onNodeWithText("Settings").performClick()
        waitForText("Appearance")
    }

    @Test
    fun `host tools open from a route builder and go back`() {
        var extra = false
        rule.runOnUiThread {
            rule.activity.setContent {
                BridleApp(
                    screens =
                        mapOf(
                            TopLevel.HOSTS to {
                                val nav = LocalNavController.current!!
                                Column {
                                    Button(onClick = { nav.navigate(SftpDestination.route(1)) }) { Text("open files") }
                                    Button(
                                        onClick = { nav.navigate(SnippetsDestination.route(1)) },
                                    ) { Text("open snippets") }
                                    Button(
                                        onClick = { nav.navigate(ForwardsDestination.route(1)) },
                                    ) { Text("open forwards") }
                                    Button(onClick = { nav.navigate("extra") }) { Text("open extra") }
                                }
                            },
                            TopLevel.SETTINGS to { SettingsRoute() },
                        ),
                    routes = {
                        composable("extra") {
                            extra = true
                            Text("extra screen")
                        }
                    },
                )
            }
        }
        waitForText("Skip")
        rule.onNodeWithText("Skip").performClick()
        for (tool in listOf("files" to "Files", "snippets" to "Snippets", "forwards" to "Port forwards")) {
            rule.onNodeWithText("open ${tool.first}").performClick()
            waitForText(tool.second)
            rule.onNodeWithText("Hosts").assertDoesNotExist() // no bottom bar on host tools
            rule.onNodeWithContentDescription("Back").performClick()
            waitForText("open files")
        }
        rule.onNodeWithText("open extra").performClick()
        waitForText("extra screen")
        extra shouldBe true
    }
}
