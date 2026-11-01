import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** Kover variant that the 95% gate and CI reports use (see root build.gradle.kts). */
const val COVERAGE_VARIANT: String = "bridle"

internal val Project.libs get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.version(name: String): String = libs.findVersion(name).get().requiredVersion

internal fun Project.library(name: String) = libs.findLibrary(name).get()

/** Settings shared by Android libraries and the app. */
internal fun Project.configureAndroid(android: CommonExtension) {
    android.apply {
        compileSdk = version("compile-sdk").toInt()
        defaultConfig.minSdk = version("min-sdk").toInt()
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
        testOptions.unitTests.isIncludeAndroidResources = true
        lint.warningsAsErrors = true
        lint.abortOnError = true
        lint.checkDependencies = true
        lint.lintConfig = rootProject.file("config/lint.xml")
        lint.disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
    }
    // Unit tests (Robolectric) run for the debug variant only; release runs would double CI time
    // without testing anything different.
    extensions.configure<AndroidComponentsExtension<*, *, *>>("androidComponents") {
        beforeVariants(selector().withBuildType("release")) { variant ->
            (variant as? HasHostTestsBuilder)?.hostTests?.get(HostTestBuilder.UNIT_TEST_TYPE)?.enable = false
        }
    }
    // Coverage is measured on the debug build (the one the unit tests run against); Kover's
    // default total variant would also pull in release classes, whose bytecode differs.
    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        currentProject { createVariant(COVERAGE_VARIANT) { add("debug") } }
    }
    tasks.withType<Test>().configureEach {
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
        systemProperty("robolectric.logging.enabled", "false")
        // Optional mirror for Robolectric's android-all jars (set in ~/.gradle/gradle.properties).
        providers.gradleProperty("bridle.robolectric.repoUrl").orNull?.let {
            systemProperty("robolectric.dependency.repo.url", it)
        }
    }
    dependencies {
        add("testImplementation", library("junit4"))
        add("testImplementation", library("robolectric"))
        add("testImplementation", library("androidx-test-junit"))
        add("testImplementation", library("kotest-assertions"))
        add("testImplementation", library("kotlinx-coroutines-test"))
        add("testImplementation", library("turbine"))
        add("testImplementation", library("mockk"))
    }
}

class BridleAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("bridle.quality")
            extensions.configure<LibraryExtension> {
                configureAndroid(this)
                defaultConfig.consumerProguardFiles("consumer-rules.pro")
            }
            extensions.configure<KotlinAndroidProjectExtension> { explicitApi() }
        }
}

class BridleAndroidApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("bridle.quality")
            extensions.configure<ApplicationExtension> {
                configureAndroid(this)
                defaultConfig.targetSdk = version("target-sdk").toInt()
            }
        }
}

class BridleAndroidComposePlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.configure<CommonExtension>("android") {
                buildFeatures.compose = true
            }
            dependencies {
                val bom = platform(library("compose-bom"))
                add("implementation", bom)
                add("testImplementation", bom)
                add("implementation", library("compose-ui"))
                add("implementation", library("compose-material3"))
                add("implementation", library("compose-ui-tooling-preview"))
                add("debugImplementation", library("compose-ui-tooling"))
                add("debugImplementation", library("compose-ui-test-manifest"))
                add("testImplementation", library("compose-ui-test-junit4"))
            }
        }
}

class BridleHiltPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            dependencies {
                add("implementation", library("hilt-android"))
                add("ksp", library("hilt-compiler"))
                add("testImplementation", library("hilt-android-testing"))
                add("kspTest", library("hilt-compiler"))
            }
        }
}
