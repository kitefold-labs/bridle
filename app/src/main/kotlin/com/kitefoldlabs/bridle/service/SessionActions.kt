package com.kitefoldlabs.bridle.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.kitefoldlabs.bridle.core.tmux.ClaudeScreen
import com.kitefoldlabs.bridle.core.tmux.ClaudeState
import com.kitefoldlabs.bridle.core.tmux.TmuxController
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What [SessionActionReceiver] does with each notification action. */
@Singleton
class SessionActions
    @Inject
    constructor(
        private val coordinator: SessionCoordinator,
    ) {
        /**
         * How long Approve/Deny may take. The receiver holds a goAsync() result, which must finish
         * within about 10 seconds; an unreachable host would otherwise keep it waiting for the
         * reconnect loop.
         */
        internal var timeout: Duration = 8.seconds

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
                    runCatching {
                        withTimeout(timeout) { coordinator.withTmux(hostId) { answerIfStillAsking(it, session, key) } }
                    }
                    context
                        .getSystemService<NotificationManager>()
                        ?.cancel(ClaudeNotifier.notificationId(hostId, session))
                }
            }
        }

        /**
         * Sends [key] only if [session]'s screen still shows a permission prompt. The notification
         * may be minutes old: the prompt may have been answered at the desk, or Claude may have
         * moved on, and a stray `1` or Escape would then land in whatever is on screen now.
         */
        private suspend fun answerIfStillAsking(
            tmux: TmuxController,
            session: String,
            key: String,
        ) {
            if (ClaudeScreen.classify(tmux.preview(session)).state == ClaudeState.WAITING_PERMISSION) {
                tmux.sendKeys(session, key)
            }
        }
    }
