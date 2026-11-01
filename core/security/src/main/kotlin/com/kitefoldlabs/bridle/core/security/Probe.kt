package com.kitefoldlabs.bridle.core.security

import android.os.Build

/** Temporary probe used to validate the Android build setup. */
public object Probe {
    public fun sdk(): Int = Build.VERSION.SDK_INT
}
