package com.kitefoldlabs.bridle.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kitefoldlabs.bridle.MainActivity
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.session.ClaudeStatusEvent
import com.kitefoldlabs.bridle.core.tmux.ClaudeState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Turns Claude status transitions into notifications (only while connected; there is no push). */
@Singleton
class ClaudeNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** Builds the notification for [event], or null if the transition shouldn't notify. */
        fun build(
            event: ClaudeStatusEvent,
            hostLabel: String,
        ): Notification? {
            val name = "${event.session} · $hostLabel"
            val title =
                when (event.to) {
                    ClaudeState.WAITING_PERMISSION -> context.getString(R.string.claude_waiting_permission, name)
                    ClaudeState.WAITING_INPUT -> context.getString(R.string.claude_waiting_input, name)
                    ClaudeState.IDLE -> context.getString(R.string.claude_idle, name)
                    ClaudeState.EXITED -> context.getString(R.string.claude_exited, name)
                    ClaudeState.WORKING, ClaudeState.UNKNOWN -> return null
                }
            val open =
                PendingIntent.getActivity(
                    context,
                    notificationId(event.hostId, event.session),
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_HOST, event.hostId)
                        .putExtra(MainActivity.EXTRA_SESSION, event.session),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val builder =
                NotificationCompat
                    .Builder(context, Notifications.CHANNEL_CLAUDE)
                    .setSmallIcon(R.drawable.ic_stat_bridle)
                    .setContentTitle(title)
                    .setContentText(event.message)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setContentIntent(open)
            if (event.to == ClaudeState.WAITING_PERMISSION) {
                builder
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .addAction(
                        0,
                        context.getString(R.string.action_approve),
                        SessionActionReceiver.claudeAction(
                            context,
                            SessionActionReceiver.ACTION_APPROVE,
                            event.hostId,
                            event.session,
                        ),
                    ).addAction(
                        0,
                        context.getString(R.string.action_deny),
                        SessionActionReceiver.claudeAction(
                            context,
                            SessionActionReceiver.ACTION_DENY,
                            event.hostId,
                            event.session,
                        ),
                    )
            }
            return builder.build()
        }

        /** Posts the notification for [event] if allowed. Returns whether one was posted. */
        fun post(
            event: ClaudeStatusEvent,
            hostLabel: String,
        ): Boolean {
            val manager = NotificationManagerCompat.from(context)
            // Reflects the POST_NOTIFICATIONS grant on Android 13+ and the app-level switch everywhere.
            val notification = build(event, hostLabel)
            if (!manager.areNotificationsEnabled() || notification == null) return false
            try {
                manager.notify(notificationId(event.hostId, event.session), notification)
            } catch (_: SecurityException) {
                return false // permission revoked between the check and the post
            }
            return true
        }

        companion object {
            /** Stable id per host+session so a new state replaces the previous notification. */
            fun notificationId(
                hostId: EntityId,
                session: String,
            ): Int = (hostId.hashCode() * HASH_PRIME + session.hashCode()) and Int.MAX_VALUE or ID_FLOOR

            private const val HASH_PRIME = 31

            /** Keeps ids clear of the fixed ids such as [Notifications.ID_SESSIONS]. */
            private const val ID_FLOOR = 0x1000
        }
    }
