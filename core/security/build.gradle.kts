plugins {
    id("bridle.android.library")
}

android {
    namespace = "com.kitefoldlabs.bridle.core.security"
}

dependencies {
    api(project(":core:ssh"))
    // sshj's key-file readers parse imported OpenSSH/PEM keys; Tink AEAD protects them at rest.
    implementation(libs.sshj)
    implementation(libs.tink.android)
    implementation(libs.androidx.biometric)

    // BouncyCastle's PEM writers generate throwaway test keys at runtime (none are committed).
    testImplementation(libs.bouncycastle.pkix)
}
