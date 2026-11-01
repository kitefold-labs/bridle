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

    override fun onCreate() {
        super.onCreate()
        getSystemService<ConnectivityManager>()?.registerDefaultNetworkCallback(networkCallback)
        // Claude status notifications for hosts with open tabs, for as long as the service runs.
        lifecycleScope.launch { statusHub.followOpenTabs() }
        lifecycleScope.launch {
            coordinator.tabs.collect { tabs ->
                if (tabs.isEmpty()) {
                    ServiceCompat.stopForeground(this@SessionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    goForeground(tabs.size)
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground(
            coordinator.tabs.value.size
                .coerceAtLeast(1),
        )
        return START_NOT_STICKY
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
