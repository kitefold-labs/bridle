plugins {
    id("bridle.android.application")
    id("bridle.android.compose")
    id("bridle.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kitefoldlabs.bridle"

    defaultConfig {
        applicationId = "com.kitefoldlabs.bridle"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // Tests build an in-memory AEAD for the imported-key vault (Robolectric has no Keystore).
    testImplementation(libs.tink.android)
}
