package com.kitefoldlabs.bridle

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.service.Notifications
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = BridleApplication::class)
class BridleApplicationTest {
    @Test
    fun `startup installs crash reporting and notification channels`() {
        val app = ApplicationProvider.getApplicationContext<BridleApplication>()
        app.crashReporter shouldNotBe null
        app
            .getSystemService(
                NotificationManager::class.java,
            ).getNotificationChannel(Notifications.CHANNEL_CLAUDE) shouldNotBe
            null
        Thread.getDefaultUncaughtExceptionHandler() shouldNotBe null
    }
}
