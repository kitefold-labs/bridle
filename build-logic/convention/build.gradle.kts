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
    implementation(libs.android.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "bridle.jvm.library"
            implementationClass = "BridleJvmLibraryPlugin"
        }
        register("androidLibrary") {
            id = "bridle.android.library"
            implementationClass = "BridleAndroidLibraryPlugin"
        }
        register("androidApplication") {
            id = "bridle.android.application"
            implementationClass = "BridleAndroidApplicationPlugin"
        }
        register("androidCompose") {
            id = "bridle.android.compose"
            implementationClass = "BridleAndroidComposePlugin"
        }
        register("hilt") {
            id = "bridle.hilt"
            implementationClass = "BridleHiltPlugin"
        }
        register("quality") {
            id = "bridle.quality"
            implementationClass = "BridleQualityPlugin"
        }
    }
}
