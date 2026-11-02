package com.kitefoldlabs.bridle.feature.sessions

import android.Manifest
import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationPermissionTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var requests = 0

    /** Answers every permission request with [granted], as if the user had tapped it. */
    private fun answering(granted: Boolean) =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry =
                object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<I, O>,
                        input: I,
                        options: ActivityOptionsCompat?,
                    ) {
                        requests++
                        dispatchResult(requestCode, granted)
                    }
                }
        }

    private fun show(
        enabled: Boolean = true,
        granted: Boolean = true,
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides answering(granted)) {
                MaterialTheme { NotificationPermissionCard(enabled) }
            }
        }
    }

    @Test
    fun `explains why, then asks and hides once allowed`() {
        show()
        rule.onNodeWithText("Get notified when Claude needs you").assertExists()
        rule.onNodeWithText("Allow").performClick()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        requests shouldBe 1
    }

    @Test
    fun `a denial hides it`() {
        show(granted = false)
        rule.onNodeWithText("Allow").performClick()
        rule.onNodeWithText("Allow").assertDoesNotExist()
    }

    @Test
    fun `not now hides it`() {
        show()
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("Allow").assertDoesNotExist()
        requests shouldBe 0
    }

    @Test
    fun `nothing to ask while notifications are off`() {
        show(enabled = false)
        rule.onNodeWithText("Allow").assertDoesNotExist()
    }

    @Test
    fun `the permission only exists from android 13`() {
        hasNotificationPermission(context, 32) shouldBe true
        hasNotificationPermission(context, 34) shouldBe false
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        hasNotificationPermission(context, 34) shouldBe true
    }
}
