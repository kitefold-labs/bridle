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
 * Foreground service that keeps open SSH sessions alive while Bridle is in the background. It
 * runs only while tabs are open and reconnects immediately when the network changes. If Android
 * stops it anyway, nothing is lost: the work lives in tmux on the host.
 */
@AndroidEntryPoint
class SessionService : LifecycleService() {
    @Inject lateinit var coordinator: SessionCoordinator

    @Inject lateinit var statusHub: ClaudeStatusHub

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lifecycleScope.launch { coordinator.connections.onNetworkChanged() }
            }
        }

    private var watchingTabs = false

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
        // the last tab closed in the meantime; so go foreground first, then follow the tabs.
        goForeground(
            coordinator.tabs.value.size
                .coerceAtLeast(1),
        )
        if (!watchingTabs) {
            watchingTabs = true
            lifecycleScope.launch { coordinator.tabs.collect(::follow) }
        } else {
            // The tabs may all have closed before this start arrived, and the collector has
            // nothing new to report then: check here, or the service would stay in the foreground.
            follow(coordinator.tabs.value)
        }
        return START_NOT_STICKY
    }

    private fun follow(tabs: List<TerminalTab>) {
        if (tabs.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            // Only if no start arrived since: a tab opened right after the last one closed has
            // already called startForegroundService(), and stopping before that start is handled
            // would crash the app ("did not then call startForeground"). It stops on that start.
            stopSelf(lastStartId)
        } else {
            goForeground(tabs.size)
        }
    }

    override fun onDestroy() {
        getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }

    private fun goForeground(count: Int) {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_SESSIONS,
            notification(this, count),
            foregroundType(),
        )
    }

    companion object {
        /** Starts (or refreshes) the service; call after opening a tab. */
        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        }

        /**
         * `specialUse` exists from Android 14; earlier versions take no type for this service. The
         * constant is inlined at compile time and only used behind the version check.
         */
        @SuppressLint("InlinedApi")
        internal fun foregroundType(sdk: Int = Build.VERSION.SDK_INT): Int =
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

        internal fun notification(
            context: Context,
            count: Int,
        ): Notification {
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
                .setContentText(context.resources.getQuantityString(R.plurals.sessions_notification_text, count, count))
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
