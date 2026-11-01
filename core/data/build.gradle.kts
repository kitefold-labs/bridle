plugins {
    id("bridle.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kitefoldlabs.bridle.core.data"
}

ksp {
    // Exported schemas are committed so every future migration can be tested against them.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:ssh"))
    api(project(":core:backup"))
    api(project(":core:terminal"))
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)
}
