package com.kitefoldlabs.bridle

import android.app.Application
import com.kitefoldlabs.bridle.crash.CrashReporter
import com.kitefoldlabs.bridle.service.Notifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BridleApplication : Application() {
    @Inject lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        super.onCreate()
        crashReporter.install()
        Notifications.createChannels(this)
    }
}
