package com.kitefoldlabs.bridle

import android.app.Application
import com.kitefoldlabs.bridle.core.ssh.BouncyCastleRegistration
import com.kitefoldlabs.bridle.crash.CrashReporter
import com.kitefoldlabs.bridle.crash.CrashScrubList
import com.kitefoldlabs.bridle.service.Notifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BridleApplication : Application() {
    @Inject lateinit var crashReporter: CrashReporter

    @Inject lateinit var crashScrubList: CrashScrubList

    override fun onCreate() {
        // Before anything (Hilt's graph included) can reach sshj: see BouncyCastleRegistration.
        BouncyCastleRegistration.install()
        super.onCreate()
        crashReporter.install()
        crashScrubList.start()
        Notifications.createChannels(this)
    }
}
