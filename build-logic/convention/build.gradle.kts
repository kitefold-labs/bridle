plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "bridle.jvm.library"
            implementationClass = "BridleJvmLibraryPlugin"
        }
        register("quality") {
            id = "bridle.quality"
            implementationClass = "BridleQualityPlugin"
        }
    }
}
