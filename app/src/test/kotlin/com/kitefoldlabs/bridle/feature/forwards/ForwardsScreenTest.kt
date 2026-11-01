package com.kitefoldlabs.bridle.feature.forwards

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.feature.FakeConnection
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ForwardsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val data: DataModule = testData(ApplicationProvider.getApplicationContext())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hostId: EntityId =
        runBlocking { data.hosts.upsert(Host(label = "dev", hostname = "dev.example.com", username = "me")) }
    private var backs = 0

    @After
    fun tearDown() {
        scope.cancel()
        data.close()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun show(connects: Boolean = true) {
        val manager = ConnectionManager({ if (connects) FakeConnection() else awaitCancellation() }, scope)
        val vm =
            ForwardsViewModel(
                SavedStateHandle(mapOf(ForwardsDestination.ARG_HOST to hostId)),
                data.portForwards,
                ForwardManager(manager, data.portForwards, scope),
            )
        rule.setContent { ForwardsScreen(vm, onBack = { backs++ }) }
    }

    private fun waitForText(text: String) =
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun click(text: String) = rule.onNodeWithText(text).performClick()

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label, substring = true))

    @Test
    fun `creates local and SOCKS forwards and runs them`() {
        show()
        waitForText("No port forwards yet.")
        rule.onNodeWithText("loopback", substring = true).assertExists()
        rule.onNodeWithContentDescription("New port forward").performClick()
        click("Save")
        rule.onAllNodesWithText("Enter a port number", substring = true).assertCountEquals(2)
        val port = freePort()
        field("Port on this phone").performTextInput("$port")
        field("Destination host").performTextReplacement("bad host")
        field("Destination port").performTextInput("30x00")
        click("Save")
        field("Destination host").performTextReplacement("localhost")
        click("Start automatically when connected")
        click("Save")
        waitForText("localhost:$port → localhost:3000")
        rule.onNodeWithText("Stopped · Starts automatically").assertExists()

        rule.onNodeWithContentDescription("Run localhost:$port → localhost:3000").performClick()
        waitForText("Running · 0 connections")
        rule.onNodeWithContentDescription("Run localhost:$port → localhost:3000").performClick()
        waitForText("Stopped")

        rule.onNodeWithContentDescription("New port forward").performClick()
        click("SOCKS (-D)")
        rule.onNodeWithText("Opens a SOCKS5 proxy", substring = true).assertExists()
        click("Local (-L)")
        click("SOCKS (-D)")
        field("Port on this phone").performTextInput("$port")
        click("Save")
        waitForText("SOCKS proxy on localhost:$port")
        rule.onNodeWithContentDescription("Run SOCKS proxy on localhost:$port").performClick()
        rule.onNodeWithContentDescription("Run localhost:$port → localhost:3000").performClick()
        waitForText("Failed: ")
        rule.onNodeWithContentDescription("Back").performClick()
        backs shouldBe 1
    }

    @Test
    fun `edits and deletes`() {
        runBlocking {
            data.portForwards.upsert(PortForward(hostId = hostId, type = PortForwardType.DYNAMIC, bindPort = 1080))
        }
        show()
        waitForText("SOCKS proxy on localhost:1080")
        rule.onNodeWithContentDescription("Actions for SOCKS proxy on localhost:1080").performClick()
        click("Edit")
        rule.onNodeWithText("Edit port forward").assertExists()
        field("Port on this phone").performTextReplacement("1081")
        click("Save")
        waitForText("SOCKS proxy on localhost:1081")
        rule.onNodeWithContentDescription("Actions for SOCKS proxy on localhost:1081").performClick()
        click("Edit")
        click("Cancel")
        rule.onNodeWithContentDescription("Actions for SOCKS proxy on localhost:1081").performClick()
        click("Delete")
        rule.onNodeWithText("Delete SOCKS proxy on localhost:1081?").assertExists()
        click("Cancel")
        rule.onNodeWithContentDescription("Actions for SOCKS proxy on localhost:1081").performClick()
        click("Delete")
        click("Delete")
        waitForText("No port forwards yet.")
    }

    @Test
    fun `shows a forward that is still connecting`() {
        runBlocking {
            data.portForwards.upsert(
                PortForward(hostId = hostId, type = PortForwardType.DYNAMIC, bindPort = freePort()),
            )
        }
        show(connects = false)
        waitForText("SOCKS proxy")
        rule.onNode(hasContentDescription("Run SOCKS", substring = true)).performClick()
        waitForText("Starting…")
    }
}
