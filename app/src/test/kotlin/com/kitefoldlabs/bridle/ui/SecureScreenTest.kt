package com.kitefoldlabs.bridle.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.feature.eventually
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SecureScreenTest {
    /** Records what the app asks of the recent-apps overview. */
    class RecordingActivity : ComponentActivity() {
        val recentsScreenshots = mutableListOf<Boolean>()

        override fun setRecentsScreenshotEnabled(enabled: Boolean) {
            recentsScreenshots += enabled
        }
    }

    private val data = testData(ApplicationProvider.getApplicationContext())

    @Test
    fun `app lock keeps the app out of the recent-apps thumbnail`() {
        val activity = Robolectric.buildActivity(RecordingActivity::class.java).setup().get()
        SecureScreen(data.settings).bind(activity)
        eventually { activity.recentsScreenshots == listOf(true) }
        runBlocking { data.settings.setAppLockEnabled(true) }
        eventually { activity.recentsScreenshots == listOf(true, false) }
        runBlocking { data.settings.setAppLockEnabled(false) }
        eventually { activity.recentsScreenshots == listOf(true, false, true) }
        data.close()
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 13 there is no recent-apps switch to set`() {
        val activity = Robolectric.buildActivity(RecordingActivity::class.java).setup().get()
        SecureScreen(data.settings).hideFromRecents(activity, hide = true)
        activity.recentsScreenshots.shouldBeEmpty()
        data.close()
    }
}
