package com.kitefoldlabs.bridle.feature

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.kitefoldlabs.bridle.MainActivity
import com.kitefoldlabs.bridle.core.data.HostRepository
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.feature.terminal.SessionServiceStarter
import com.kitefoldlabs.bridle.feature.terminal.TerminalEngineFactory
import com.kitefoldlabs.bridle.feature.terminal.TerminalModule
import com.kitefoldlabs.bridle.service.SessionCoordinator
import com.kitefoldlabs.bridle.service.SessionLink
import com.kitefoldlabs.bridle.service.SessionLinks
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The feature screens wired through Hilt in the real activity, with a fake emulator. */
@HiltAndroidTest
@UninstallModules(TerminalModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class, qualifiers = "w420dp-h2000dp")
class FeatureWiringTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @BindValue
    @JvmField
    val engines: TerminalEngineFactory = TerminalEngineFactory { FakeEngine() }

    @BindValue
    @JvmField
    val service: SessionServiceStarter = SessionServiceStarter {}

    @Inject lateinit var coordinator: SessionCoordinator

    @Inject lateinit var hosts: HostRepository

    @Inject lateinit var links: SessionLinks

    @Before
    fun setUp() = hilt.inject()

    @After
    fun tearDown() = runBlocking { coordinator.disconnectAll() }

    @Test
    fun `a notification tap opens its session, and the sessions tab lists the host`() {
        val id = runBlocking { hosts.upsert(Host(label = "devbox", hostname = "127.0.0.1", port = 1, username = "me")) }
        val intent = links.intent(SessionLink(id, "api"))
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) { coordinator.tabs.value.isNotEmpty() }
            coordinator.tabs.value
                .single()
                .session.attach shouldBe AttachSpec.Tmux("api")
            awaitTerminal()
            compose.onNodeWithText("api · devbox").assertExists()

            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Sessions").performClick()
            awaitText("1 terminal open")
            awaitText("devbox")
            compose.onNodeWithText("Show").performClick()
            awaitTerminal()
        }
    }

    /** Screens fill in as their data loads from the database. */
    private fun awaitText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The terminal appears once its screen state is built (after settings and history load). */
    private fun awaitTerminal() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(FakeEngine.TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
