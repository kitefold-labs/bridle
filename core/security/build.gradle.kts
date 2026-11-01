plugins {
    id("bridle.android.library")
}

android {
    namespace = "com.kitefoldlabs.bridle.core.security"
}

dependencies {
    api(project(":core:ssh"))
    implementation(libs.tink.android)
    implementation(libs.androidx.biometric)
}
