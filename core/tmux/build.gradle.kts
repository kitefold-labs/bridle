plugins {
    id("bridle.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:ssh"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The host helper ships inside the app as a resource; its sha256 is verified after upload.
tasks.processResources {
    from(rootProject.file("host-helper/bridle-helper")) {
        into("com/kitefoldlabs/bridle/core/tmux")
    }
}
