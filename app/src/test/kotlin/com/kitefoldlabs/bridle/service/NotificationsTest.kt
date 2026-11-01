package com.kitefoldlabs.bridle.service

import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.session.ClaudeStatusEvent
import com.kitefoldlabs.bridle.core.tmux.ClaudeState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun event(to: ClaudeState) = ClaudeStatusEvent(7, "claude-api", ClaudeState.WORKING, to, "Allow Bash?")

    @Test
    fun `channels are created with the right importance`() {
        Notifications.createChannels(context)
        manager.getNotificationChannel(Notifications.CHANNEL_SESSIONS).importance shouldBe
            NotificationManager.IMPORTANCE_LOW
        manager.getNotificationChannel(Notifications.CHANNEL_CLAUDE).importance shouldBe
            NotificationManager.IMPORTANCE_HIGH
    }

    @Test
    fun `permission prompts get approve and deny actions`() {
        val notifier = ClaudeNotifier(context)
        val n = notifier.build(event(ClaudeState.WAITING_PERMISSION), "devbox")!!
        NotificationCompat.getContentTitle(n).toString() shouldContain "claude-api · devbox"
        n.actions.map { it.title.toString() } shouldBe listOf("Approve", "Deny")
        for (state in listOf(ClaudeState.WAITING_INPUT, ClaudeState.IDLE, ClaudeState.EXITED)) {
            val other = notifier.build(event(state), "devbox")!!
            (other.actions?.size ?: 0) shouldBe 0
        }
        notifier.build(event(ClaudeState.WORKING), "devbox") shouldBe null
        notifier.build(event(ClaudeState.UNKNOWN), "devbox") shouldBe null
    }

    @Test
    fun `posting respects the notification permission`() {
        Notifications.createChannels(context)
        val notifier = ClaudeNotifier(context)
        shadowOf(manager).setNotificationsEnabled(false)
        notifier.post(event(ClaudeState.IDLE), "devbox") shouldBe false
        shadowOf(manager).setNotificationsEnabled(true)
        notifier.post(event(ClaudeState.WORKING), "devbox") shouldBe false
        notifier.post(event(ClaudeState.IDLE), "devbox") shouldBe true
        shadowOf(manager).allNotifications.size shouldBe 1
        ClaudeNotifier.notificationId(7, "a") shouldBe ClaudeNotifier.notificationId(7, "a")
        ClaudeNotifier.notificationId(7, "a") shouldNotBe ClaudeNotifier.notificationId(7, "b")
        (ClaudeNotifier.notificationId(Long.MAX_VALUE, "x") > 0) shouldBe true
    }

    @Test
    fun `session notification shows the count and a disconnect action`() {
        val n = SessionService.notification(context, 3)
        NotificationCompat.getContentText(n).toString() shouldBe "3 sessions open"
        n.actions
            .single()
            .title
            .toString() shouldBe "Disconnect all"
        (n.flags and android.app.Notification.FLAG_ONGOING_EVENT) shouldNotBe 0
    }

    @Test
    fun `foreground service type depends on the android version`() {
        SessionService.foregroundType(34) shouldBe android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        SessionService.foregroundType(33) shouldBe 0
    }
}
