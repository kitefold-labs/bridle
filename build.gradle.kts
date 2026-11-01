import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    // Declared here (not applied) so AGP, the Kotlin plugins, KSP and Hilt share one classloader;
    // otherwise AGP's built-in Kotlin support can't see AGP classes from the Kotlin plugin.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.license.report)
}

// ---------------------------------------------------------------------------------------------
// Coverage: every module's Kover data is merged here and gated at >= 95% line AND branch
// coverage of handwritten code. Only generated code is excluded (see docs/development.md).
// ---------------------------------------------------------------------------------------------
// Every module with a build file is covered automatically, so a new module can't silently skip
// the gate.
val coveredModules =
    rootProject.subprojects
        .filter { it.buildFile.exists() }
        .map { it.path }

dependencies {
    coveredModules.forEach { kover(project(it)) }
}

kover {
    // The `bridle` variant = JVM modules + the debug build of Android modules. Use
    // `koverVerifyBridle` / `koverXmlReportBridle` / `koverHtmlReportBridle`.
    currentProject { createVariant("bridle") {} }
    // JaCoCo's Kotlin filters drop compiler-generated code (default-argument bridges, data-class
    // members, kotlinx.serialization constructors) so the gate measures only handwritten code.
    useJacoco(libs.versions.jacoco.get())
    reports {
        filters {
            excludes {
                annotatedBy(
                    "javax.annotation.processing.Generated",
                    "javax.annotation.Generated",
                    "dagger.internal.DaggerGenerated",
                    "androidx.compose.ui.tooling.preview.Preview",
                )
                // Generated sources only (patterns are fully-qualified class names).
                classes(
                    "*.BuildConfig",
                    "*.R",
                    "*.R$*",
                    "*.ComposableSingletons*",
                    "*_Factory",
                    "*_Factory$*",
                    "*_MembersInjector",
                    "*_HiltModules*",
                    "*_HiltComponents*",
                    "*_GeneratedInjector",
                    "*_ComponentTreeDeps",
                    "*.Hilt_*",
                    "*.Dagger*Component*",
                    "hilt_aggregated_deps.*",
                    "dagger.hilt.internal.aggregatedroot.codegen.*",
                    "*_Impl",
                    "*_Impl$*",
                    "*\$serializer",
                )
            }
        }
        verify {
            rule("Handwritten code coverage") {
                minBound(95, CoverageUnit.LINE)
                minBound(95, CoverageUnit.BRANCH)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// License compliance: Bridle is Apache-2.0, so runtime dependencies must be permissive.
// ---------------------------------------------------------------------------------------------
licenseReport {
    allowedLicensesFile = file("config/license/allowed-licenses.json")
    configurations = arrayOf("runtimeClasspath")
}

listOf("generateLicenseReport", "checkLicense", "checkLicensePreparation").forEach { name ->
    tasks.matching { it.name == name }.configureEach {
        notCompatibleWithConfigurationCache("dependency-license-report is not configuration-cache compatible")
    }
}
