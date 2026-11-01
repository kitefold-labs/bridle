import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.license.report)
}

// ---------------------------------------------------------------------------------------------
// Coverage: every module's Kover data is merged here and gated at >= 95% line AND branch
// coverage of handwritten code. Only generated code is excluded (see docs/development.md).
// ---------------------------------------------------------------------------------------------
val coveredModules =
    listOf(
        ":core:model",
        ":core:ssh",
        ":core:tmux",
    )

dependencies {
    coveredModules.forEach { kover(project(it)) }
}

kover {
    // JaCoCo's Kotlin filters drop compiler-generated code (default-argument bridges, data-class
    // members, kotlinx.serialization constructors) so the gate measures only handwritten code.
    useJacoco(libs.versions.jacoco.get())
    reports {
        filters {
            excludes {
                annotatedBy("*Generated*", "androidx.compose.ui.tooling.preview.Preview")
                classes(
                    "*.BuildConfig",
                    "*.R",
                    "*.R$*",
                    "*_Factory",
                    "*_Factory$*",
                    "*_MembersInjector",
                    "*_HiltModules*",
                    "Hilt_*",
                    "*_Impl",
                    "*_Impl$*",
                    "*ComposableSingletons*",
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
