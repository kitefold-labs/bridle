plugins {
    id("bridle.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.sshj)
    // BouncyCastleRegistration installs the full provider under sshj's "BC" name.
    implementation(libs.bouncycastle.prov)
    constraints {
        // sshj pulls BouncyCastle 1.84; keep every BC artifact on the same, newest version
        // (mixed versions fail at class-initialisation time).
        implementation(libs.bouncycastle.prov)
        implementation(libs.bouncycastle.pkix)
        implementation(libs.bouncycastle.util)
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.mina.sshd.core)
    testImplementation(libs.mina.sshd.sftp)
    testRuntimeOnly(libs.junit.platform.launcher)
}
