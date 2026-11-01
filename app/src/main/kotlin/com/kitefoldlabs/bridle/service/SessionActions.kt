package com.kitefoldlabs.bridle.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import javax.inject.Inject
import javax.inject.Singleton

/** What [SessionActionReceiver] does with each notification action. */
@Singleton
class SessionActions
    @Inject
    constructor(
        private val coordinator: SessionCoordinator,
    ) {
        suspend fun handle(
            context: Context,
            intent: Intent,
        ) {
            when (intent.action) {
                SessionActionReceiver.ACTION_DISCONNECT_ALL -> coordinator.disconnectAll()
                SessionActionReceiver.ACTION_APPROVE, SessionActionReceiver.ACTION_DENY -> {
                    val hostId = intent.getLongExtra(SessionActionReceiver.EXTRA_HOST, -1)
                    val session = intent.getStringExtra(SessionActionReceiver.EXTRA_SESSION) ?: return
                    if (hostId < 0) return
                    // Claude Code's permission menu: "1" selects "Yes"; Escape declines.
                    val key = if (intent.action == SessionActionReceiver.ACTION_APPROVE) "1" else "Escape"
                    runCatching { coordinator.withTmux(hostId) { it.sendKeys(session, key) } }
                    context
                        .getSystemService<NotificationManager>()
                        ?.cancel(ClaudeNotifier.notificationId(hostId, session))
                }
            }
        }
    }
