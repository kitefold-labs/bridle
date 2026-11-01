plugins {
    id("bridle.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    // Argon2id via the lightweight API. Bouncy Castle Licence (MIT-style), Apache-2.0 compatible.
    implementation(libs.bouncycastle.prov)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testRuntimeOnly(libs.junit.platform.launcher)
}
