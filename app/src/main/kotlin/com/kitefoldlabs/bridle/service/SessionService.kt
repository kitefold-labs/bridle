package com.kitefoldlabs.bridle.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kitefoldlabs.bridle.MainActivity
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.feature.sessions.ClaudeStatusHub
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps open SSH sessions and file transfers alive while Bridle is in the
 * background. It runs only while tabs are open or transfers are queued or running ([ForegroundWork])
 * and reconnects immediately when the network changes. If Android stops it anyway, nothing is
 * lost: the work lives in tmux on the host.
 */
@AndroidEntryPoint
class SessionService : LifecycleService() {
    @Inject lateinit var coordinator: SessionCoordinator

    @Inject lateinit var statusHub: ClaudeStatusHub

    @Inject lateinit var work: ForegroundWork

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lifecycleScope.launch { coordinator.connections.onNetworkChanged() }
            }
        }

    private var watching = false

    /** The newest start request delivered; see [onStartCommand]. */
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        getSystemService<ConnectivityManager>()?.registerDefaultNetworkCallback(networkCallback)
        // Claude status notifications for hosts with open tabs, for as long as the service runs.
        lifecycleScope.launch { statusHub.followOpenTabs() }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        // startForegroundService() requires startForeground() before the service may stop, even if
        // the last tab closed (or transfer ended) in the meantime; so go foreground first, then
        // follow the work.
        goForeground(work.current)
        if (!watching) {
            watching = true
            lifecycleScope.launch { work.changes.collect(::follow) }
        } else {
            // The work may all have ended before this start arrived, and the collector has
            // nothing new to report then: check here, or the service would stay in the foreground.
            follow(work.current)
        }
        return START_NOT_STICKY
    }

    private fun follow(active: ActiveWork) {
        if (active.isIdle) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            // Only if no start arrived since: a tab opened right after the last one closed has
            // already called startForegroundService(), and stopping before that start is handled
            // would crash the app ("did not then call startForeground"). It stops on that start.
            stopSelf(lastStartId)
        } else {
            goForeground(active)
        }
    }

    override fun onDestroy() {
        getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }

    private fun goForeground(active: ActiveWork) {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_SESSIONS,
            notification(this, active),
            foregroundType(),
        )
    }

    companion object {
        /**
         * Starts (or refreshes) the service; call after opening a tab or queueing a transfer.
         * Returns false when Android won't start a foreground service right now: from the
         * background on Android 12+ (say, a reconnect or a notification action) it throws
         * `ForegroundServiceStartNotAllowedException`, an [IllegalStateException]. That is not
         * worth failing for: the tab still works while Bridle runs, the work itself lives in tmux
         * on the host, and the next start (opening a tab with the app in front) brings the service
         * back. Only a fixed line is logged.
         */
        @Suppress("SwallowedException")
        fun ensureRunning(context: Context): Boolean =
            try {
                ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Foreground service start not allowed now; continuing without it")
                false
            }

        private const val TAG = "SessionService"

        /**
         * `specialUse` exists from Android 14; earlier versions take no type for this service. The
         * constant is inlined at compile time and only used behind the version check.
         */
        @SuppressLint("InlinedApi")
        internal fun foregroundType(sdk: Int = Build.VERSION.SDK_INT): Int =
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

        /** The ongoing notification; [active] says what is open (idle: a start that is about to stop). */
        internal fun notification(
            context: Context,
            active: ActiveWork,
        ): Notification {
            val res = context.resources
            val sessions = active.tabs.coerceAtLeast(if (active.transfers == 0) 1 else 0)
            val parts =
                listOfNotNull(
                    sessions.takeIf { it > 0 }?.let {
                        res.getQuantityString(
                            R.plurals.sessions_notification_text,
                            it,
                            it,
                        )
                    },
                    active.transfers.takeIf { it > 0 }?.let {
                        res.getQuantityString(R.plurals.sessions_notification_transfers, it, it)
                    },
                )
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            return NotificationCompat
                .Builder(context, Notifications.CHANNEL_SESSIONS)
                .setSmallIcon(R.drawable.ic_stat_bridle)
                .setContentTitle(context.getString(R.string.sessions_notification_title))
                .setContentText(parts.joinToString(" · "))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(
                    0,
                    context.getString(R.string.action_disconnect_all),
                    SessionActionReceiver.disconnectAll(context),
                ).build()
        }
    }
}
