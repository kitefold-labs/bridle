package com.kitefoldlabs.bridle.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.session.ClaudeStatusEvent
import com.kitefoldlabs.bridle.core.tmux.ClaudeState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Turns Claude status transitions into notifications (only while connected; there is no push). */
@Singleton
class ClaudeNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val links: SessionLinks,
    ) {
        /**
         * Builds the notification for [event], or null if the transition shouldn't notify. A
         * permission request gets Approve/Deny actions bound to [prompt] (see [promptFingerprint]);
         * without one (the screen couldn't be read) it can only be opened.
         */
        fun build(
            event: ClaudeStatusEvent,
            hostLabel: String,
            prompt: String? = null,
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
                    links.intent(SessionLink(event.hostId, event.session)),
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
                    // The message, session and host names stay off a locked screen that hides
                    // sensitive content; it shows only this generic version.
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicVersion())
            if (event.to == ClaudeState.WAITING_PERMISSION) builder.setPriority(NotificationCompat.PRIORITY_HIGH)
            if (event.to == ClaudeState.WAITING_PERMISSION && prompt != null) {
                builder
                    .addAction(
                        claudeAction(R.string.action_approve, SessionActionReceiver.ACTION_APPROVE, event, prompt),
                    ).addAction(claudeAction(R.string.action_deny, SessionActionReceiver.ACTION_DENY, event, prompt))
            }
            return builder.build()
        }

        /**
         * Approve or Deny. Both need the device unlocked (Android 12+): answering a permission prompt
         * lets Claude run a command, which nobody holding a locked phone should be able to do.
         */
        private fun claudeAction(
            title: Int,
            action: String,
            event: ClaudeStatusEvent,
            prompt: String,
        ): NotificationCompat.Action =
            NotificationCompat.Action
                .Builder(
                    0,
                    context.getString(title),
                    SessionActionReceiver.claudeAction(context, action, event.hostId, event.session, prompt),
                ).setAuthenticationRequired(true)
                .build()

        private fun publicVersion(): Notification =
            NotificationCompat
                .Builder(context, Notifications.CHANNEL_CLAUDE)
                .setSmallIcon(R.drawable.ic_stat_bridle)
                .setContentTitle(context.getString(R.string.claude_notification_public))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()

        /** Posts the notification for [event] if allowed. Returns whether one was posted. */
        fun post(
            event: ClaudeStatusEvent,
            hostLabel: String,
            prompt: String? = null,
        ): Boolean {
            val manager = NotificationManagerCompat.from(context)
            // Reflects the POST_NOTIFICATIONS grant on Android 13+ and the app-level switch everywhere.
            val notification = build(event, hostLabel, prompt)
            if (!manager.areNotificationsEnabled() || notification == null) return false
            try {
                manager.notify(notificationId(event.hostId, event.session), notification)
            } catch (_: SecurityException) {
                return false // permission revoked between the check and the post
            }
            return true
        }

        /**
         * Takes down the notifications for [sessions] on [hostId]. Called when the host stops being
         * monitored: nothing would replace them as Claude moves on, so they would only go stale.
         */
        fun cancel(
            hostId: EntityId,
            sessions: Iterable<String>,
        ) {
            val manager = NotificationManagerCompat.from(context)
            for (session in sessions) manager.cancel(notificationId(hostId, session))
        }

        companion object {
            /**
             * Identifies a permission prompt by the screen that shows it: the last [PROMPT_LINES]
             * lines of [screen] (what [com.kitefoldlabs.bridle.core.tmux.ClaudeScreen.classify]
             * reads), right-trimmed, hashed. Approve/Deny carry it, and [SessionActions] answers
             * only while the session still shows the same prompt.
             */
            fun promptFingerprint(screen: String): String {
                val tail =
                    screen
                        .trimEnd()
                        .lines()
                        .takeLast(PROMPT_LINES)
                        .joinToString("\n") { it.trimEnd() }
                return MessageDigest
                    .getInstance("SHA-256")
                    .digest(tail.toByteArray())
                    .take(FINGERPRINT_BYTES)
                    .joinToString("") { "%02x".format(it) }
            }

            /** [com.kitefoldlabs.bridle.core.tmux.ClaudeScreen]'s TAIL_LINES. */
            private const val PROMPT_LINES = 25
            private const val FINGERPRINT_BYTES = 8

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
