plugins {
    id("bridle.android.application")
    id("bridle.android.compose")
    id("bridle.hilt")
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is optional: without these values `assembleRelease` produces an unsigned APK,
// which is what F-Droid's build server expects. See docs/release.md.
fun signingValue(name: String): Provider<String> =
    providers
        .gradleProperty("bridle.signing.$name")
        .orElse(providers.environmentVariable("BRIDLE_SIGNING_${name.uppercase()}"))

val releaseStoreFile = signingValue("storeFile")

android {
    namespace = "com.kitefoldlabs.bridle"

    defaultConfig {
        applicationId = "com.kitefoldlabs.bridle"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // termlib ships four ABIs; 32-bit devices are out of scope (ADR-0002).
        ndk.abiFilters += setOf("arm64-v8a", "x86_64")
    }

    signingConfigs {
        if (releaseStoreFile.isPresent) {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = signingValue("storePassword").get()
                keyAlias = signingValue("keyAlias").get()
                keyPassword = signingValue("keyPassword").get()
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // The dependency-metadata block is encrypted with a Google key; F-Droid rejects APKs that
    // carry it, and it would make GitHub Releases APKs differ from F-Droid's reproducible build.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        // Third-party licence and notice files are merged, not dropped (Apache-2.0 §4).
        resources.merges += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation(project(":core:session"))
    implementation(project(":core:backup"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.serialization.json)
}
