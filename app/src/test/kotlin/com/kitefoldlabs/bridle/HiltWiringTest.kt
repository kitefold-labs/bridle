package com.kitefoldlabs.bridle

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.settings.SettingsRepository
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.service.Notifications
import com.kitefoldlabs.bridle.service.SessionActionReceiver
import com.kitefoldlabs.bridle.service.SessionCoordinator
import com.kitefoldlabs.bridle.service.SessionService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class HiltWiringTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var coordinator: SessionCoordinator

    @Inject lateinit var settings: SettingsRepository

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun `main activity starts with the navigation scaffold`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.isFinishing shouldBe false }
        }
    }

    @Test
    fun `service goes foreground while tabs are open and stops after`() =
        runBlocking<Unit> {
            val tab = coordinator.openTab(42, "x", AttachSpec.Shell, { _, _ -> })
            val controller = Robolectric.buildService(SessionService::class.java).create().startCommand(0, 1)
            ShadowLooper.idleMainLooper()
            val service = controller.get()
            shadowOf(service).lastForegroundNotification shouldNotBe null
            SessionService.ensureRunning(context)
            coordinator.closeTab(tab.id)
            ShadowLooper.idleMainLooper()
            shadowOf(service).isStoppedBySelf shouldBe true
            controller.destroy()
        }

    @Test
    fun `a start with no tabs left goes foreground before it stops`() {
        // The tab that asked for the service closed before the start arrived. Stopping without
        // startForeground() would crash on Android 8+ (startForegroundService's contract).
        val controller = Robolectric.buildService(SessionService::class.java).create().startCommand(0, 1)
        ShadowLooper.idleMainLooper()
        val shadow = shadowOf(controller.get())
        shadow.lastForegroundNotificationId shouldBe Notifications.ID_SESSIONS // startForeground() ran
        shadow.isForegroundStopped shouldBe true // ...and was followed by stopForeground(), not preceded
        shadow.isStoppedBySelf shouldBe true
        controller.startCommand(0, 2) // a second start doesn't watch the tabs twice
        ShadowLooper.idleMainLooper()
        controller.destroy()
    }

    @Test
    fun `main activity follows the secure screen setting`() =
        runBlocking<Unit> {
            settings.setSecureScreenEnabled(true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitFlag(scenario, secure = true)
                settings.setSecureScreenEnabled(false)
                awaitFlag(scenario, secure = false)
            }
        }

    private fun awaitFlag(
        scenario: ActivityScenario<MainActivity>,
        secure: Boolean,
    ) {
        var flags = 0
        repeat(200) {
            ShadowLooper.idleMainLooper()
            scenario.onActivity { flags = it.window.attributes.flags }
            if ((flags and WindowManager.LayoutParams.FLAG_SECURE != 0) == secure) return
            Thread.sleep(10)
        }
        (flags and WindowManager.LayoutParams.FLAG_SECURE != 0) shouldBe secure
    }

    @Test
    fun `receiver injects and handles disconnect all`() =
        runBlocking<Unit> {
            coordinator.openTab(42, "x", AttachSpec.Shell, { _, _ -> })
            // Delivered by the framework (not by calling onReceive), so goAsync() has a result to finish.
            val disconnect = Intent(context, SessionActionReceiver::class.java)
            context.sendBroadcast(disconnect.setAction(SessionActionReceiver.ACTION_DISCONNECT_ALL))
            ShadowLooper.idleMainLooper()
            repeat(100) {
                if (coordinator.tabs.value.isNotEmpty()) Thread.sleep(10)
            }
            coordinator.tabs.value shouldBe emptyList()
        }
}
