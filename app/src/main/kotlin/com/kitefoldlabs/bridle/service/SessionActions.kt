package com.kitefoldlabs.bridle.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.kitefoldlabs.bridle.core.tmux.ClaudeScreen
import com.kitefoldlabs.bridle.core.tmux.ClaudeState
import com.kitefoldlabs.bridle.core.tmux.TmuxController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
                    answer(hostId, session, key, intent.getStringExtra(SessionActionReceiver.EXTRA_PROMPT))
                    context
                        .getSystemService<NotificationManager>()
                        ?.cancel(ClaudeNotifier.notificationId(hostId, session))
                }
            }
        }

        /**
         * Answers within [timeout], or not at all: an unreachable host or a vanished session just
         * dismisses the notification (it is stale whatever went wrong). Cancellation of the caller
         * is not a failure and propagates.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private suspend fun answer(
            hostId: Long,
            session: String,
            key: String,
            prompt: String?,
        ) {
            try {
                withTimeoutOrNull(timeout) {
                    coordinator.withTmux(hostId) { answerIfStillAsking(it, session, key, prompt) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not logged: the message may name the host.
            }
        }

        /**
         * Sends [key] only if [session]'s screen still shows the permission prompt the notification
         * was for ([prompt] is its [ClaudeNotifier.promptFingerprint]). The notification may be
         * hours old: that prompt may have been answered at the desk and Claude may be asking about
         * something else now, or a stray `1` or Escape would land in whatever is on screen.
         */
        private suspend fun answerIfStillAsking(
            tmux: TmuxController,
            session: String,
            key: String,
            prompt: String?,
        ) {
            val screen = tmux.preview(session)
            if (ClaudeScreen.classify(screen).state == ClaudeState.WAITING_PERMISSION &&
                ClaudeNotifier.promptFingerprint(screen) == prompt
            ) {
                tmux.sendKeys(session, key)
            }
        }
    }
