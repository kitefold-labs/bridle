plugins {
    id("bridle.android.library")
    id("bridle.android.compose")
}

android {
    namespace = "com.kitefoldlabs.bridle.core.terminalui"
}

dependencies {
    api(project(":core:session"))
    api(project(":core:terminal"))
    // ConnectBot's libvterm-based emulator. Only TermlibEngine touches it: its JNI library does
    // not load on the host JVM, so everything else is written against TerminalEngine.
    implementation(libs.termlib)
}
