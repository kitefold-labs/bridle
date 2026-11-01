package com.kitefoldlabs.bridle.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.kitefoldlabs.bridle.R

/** Notification channels. Created at startup; users can tune each one in system settings. */
object Notifications {
    const val CHANNEL_SESSIONS: String = "sessions"
    const val CHANNEL_CLAUDE: String = "claude"
    const val ID_SESSIONS: Int = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val sessions =
            NotificationChannel(
                CHANNEL_SESSIONS,
                context.getString(R.string.channel_sessions),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_sessions_description)
                setShowBadge(false)
            }
        val claude =
            NotificationChannel(
                CHANNEL_CLAUDE,
                context.getString(R.string.channel_claude),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_claude_description)
            }
        manager.createNotificationChannels(listOf(sessions, claude))
    }
}
