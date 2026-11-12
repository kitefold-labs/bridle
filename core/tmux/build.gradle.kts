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

// The host helper and the shell integration scripts ship inside the app as resources; their
// sha256 is verified after upload.
tasks.processResources {
    from(rootProject.file("host-helper")) {
        include("bridle-helper", "shell-integration.bash", "shell-integration.zsh")
        into("com/kitefoldlabs/bridle/core/tmux")
    }
}
