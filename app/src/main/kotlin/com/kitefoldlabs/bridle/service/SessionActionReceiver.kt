package com.kitefoldlabs.bridle.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Notification actions: Disconnect all, and Approve/Deny for Claude permission prompts. Kept thin:
 * Hilt rewrites entry-point bytecode, so their logic lives in [SessionActions] where it is tested
 * and measured.
 */
@AndroidEntryPoint
class SessionActionReceiver : BroadcastReceiver() {
    @Inject lateinit var actions: SessionActions

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                actions.handle(context, intent)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DISCONNECT_ALL: String = "com.kitefoldlabs.bridle.DISCONNECT_ALL"
        const val ACTION_APPROVE: String = "com.kitefoldlabs.bridle.CLAUDE_APPROVE"
        const val ACTION_DENY: String = "com.kitefoldlabs.bridle.CLAUDE_DENY"
        const val EXTRA_HOST: String = "host"
        const val EXTRA_SESSION: String = "session"

        fun disconnectAll(context: Context): PendingIntent = broadcast(context, ACTION_DISCONNECT_ALL, 0)

        fun claudeAction(
            context: Context,
            action: String,
            hostId: EntityId,
            session: String,
        ): PendingIntent =
            broadcast(
                context,
                action,
                ClaudeNotifier.notificationId(hostId, session) * 2 + if (action == ACTION_APPROVE) 0 else 1,
            ) {
                putExtra(EXTRA_HOST, hostId)
                putExtra(EXTRA_SESSION, session)
            }

        private fun broadcast(
            context: Context,
            action: String,
            requestCode: Int,
            extras: Intent.() -> Unit = {},
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, SessionActionReceiver::class.java).setAction(action).apply(extras),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
