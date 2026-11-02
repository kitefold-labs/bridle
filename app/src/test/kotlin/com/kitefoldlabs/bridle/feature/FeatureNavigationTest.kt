package com.kitefoldlabs.bridle.feature

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kitefoldlabs.bridle.MainActivity
import com.kitefoldlabs.bridle.connect.InteractionBroker
import com.kitefoldlabs.bridle.core.security.AeadProvider
import com.kitefoldlabs.bridle.core.security.KeyStoreBackend
import com.kitefoldlabs.bridle.core.security.KeyWrappingStore
import com.kitefoldlabs.bridle.core.ssh.HostKeyPrompt
import com.kitefoldlabs.bridle.di.KeystoreModule
import com.kitefoldlabs.bridle.feature.terminal.SessionServiceStarter
import com.kitefoldlabs.bridle.feature.terminal.TerminalEngineFactory
import com.kitefoldlabs.bridle.feature.terminal.TerminalModule
import com.kitefoldlabs.bridle.service.SessionCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
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
import javax.inject.Singleton
import kotlin.concurrent.thread

/** Robolectric has no Android Keystore: Hilt tests get software key storage instead. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [KeystoreModule::class])
object TestKeystoreModule {
    @Provides
    @Singleton
    fun keyStoreBackend(): KeyStoreBackend = SoftwareBackend()

    @Provides
    @Singleton
    fun aeadProvider(): AeadProvider = MemoryAead()

    @Provides
    @Singleton
    fun keyWrappingStore(): KeyWrappingStore = SoftwareWrapping()
}

/**
 * Walks the Hosts and Keys tabs of the real app, with the real Hilt graph and database (and a fake
 * emulator: termlib's native library does not load on the host JVM).
 */
@HiltAndroidTest
@UninstallModules(TerminalModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class, qualifiers = "w411dp-h2400dp")
class FeatureNavigationTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val onboarded = OnboardedRule()

    @get:Rule(order = 2)
    val rule = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    val engines: TerminalEngineFactory = TerminalEngineFactory { FakeEngine() }

    @BindValue
    @JvmField
    val service: SessionServiceStarter = SessionServiceStarter {}

    @Inject lateinit var broker: InteractionBroker

    @Inject lateinit var coordinator: SessionCoordinator

    @Before
    fun setUp() = hilt.inject()

    @After
    fun tearDown() = runBlocking { coordinator.disconnectAll() }

    private fun waitFor(
        text: String,
        substring: Boolean = false,
    ) {
        rule.waitUntil(
            30_000,
        ) { rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `hosts tab adds, edits and imports hosts`() {
        waitFor("No hosts yet. Tap + to add one, or import your ~/.ssh/config.")
        rule.onNodeWithContentDescription("Add host").performClick()
        waitFor("Host name or IP address")
        rule.onNodeWithText("Name").performTextInput("Dev")
        rule.onNodeWithText("Host name or IP address").performTextInput("dev.example")
        rule.onNodeWithText("Username").performTextInput("nate")
        rule.onNodeWithText("Save").performClick()
        waitFor("nate@dev.example")
        rule.onNodeWithText("Dev").performClick() // opens a terminal on the host
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(FakeEngine.TAG).fetchSemanticsNodes().isNotEmpty() }
        coordinator.tabs.value
            .single()
            .title shouldBe "Dev"
        // The terminal's menu opens the tab's host's files and snippets; back returns to the terminal.
        val files = hasContentDescription("Upload files")
        val snippets = hasText("No snippets yet", substring = true)
        for ((tool, shown) in listOf("Files" to files, "Snippets" to snippets)) {
            rule.onNodeWithContentDescription("More options").performClick()
            rule.onNodeWithText(tool).performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(shown).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("Back").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag(FakeEngine.TAG).fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor("Dev")
        // The host's menu opens its tools; back returns to the list.
        val forwards = hasText("No port forwards yet.")
        for ((tool, shown) in listOf("Files" to files, "Snippets" to snippets, "Port forwards" to forwards)) {
            rule.onNodeWithContentDescription("More actions for Dev").performClick()
            rule.onNodeWithText(tool).performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(shown).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Hosts").assertDoesNotExist() // full screen, no bottom bar
            rule.onNodeWithContentDescription("Back").performClick()
            waitFor("nate@dev.example")
        }
        rule.onNodeWithContentDescription("More actions for Dev").performClick()
        waitFor("Edit")
        rule.onNodeWithText("Edit").performClick()
        waitFor("Edit host")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Import from ~/.ssh/config").performClick()
        waitFor("Import ~/.ssh/config")
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor("nate@dev.example")
    }

    @Test
    fun `keys tab generates a key and opens the other key screens`() {
        rule.onAllNodesWithText("Keys").onFirst().performClick()
        waitFor("No keys yet", substring = true)
        rule.onNodeWithContentDescription("Generate key").performClick()
        waitFor("Key name")
        rule.onNodeWithText("Key name").performTextInput("Pixel")
        rule.onNodeWithText("Generate").performClick()
        waitFor("Public key")
        rule.onNodeWithText("Pixel").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor("Hardware key")
        rule.onNodeWithText("Pixel").performClick()
        waitFor("Public key")
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor("Hardware key")
        rule.onNodeWithContentDescription("Import key").performClick()
        waitFor("Private key")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Known hosts").performClick()
        waitFor("No trusted host keys yet", substring = true)
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor("Hardware key")
    }

    @Test
    fun `connection questions appear over any screen`() {
        var trusted: Boolean? = null
        val asking =
            thread {
                trusted =
                    broker.confirmNewHost(HostKeyPrompt("dev.example", 22, "ssh-ed25519", "SHA256:x"))
            }
        waitFor("Trust this host?")
        rule.onNodeWithText("Trust and connect").performClick()
        asking.join(10_000)
        trusted shouldBe true
    }
}
