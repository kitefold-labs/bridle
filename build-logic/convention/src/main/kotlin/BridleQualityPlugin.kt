import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * Static analysis shared by every module: ktlint (formatting), detekt (code smells and
 * complexity) and Kover (coverage instrumentation; the 95% gate lives in the root build).
 */
class BridleQualityPlugin : Plugin<Project> {
    private companion object {
        /** Keep in sync with `jacoco` in gradle/libs.versions.toml. */
        const val JACOCO_VERSION = "0.8.16"
    }

    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            pluginManager.apply("org.jetbrains.kotlinx.kover")

            extensions.configure<KoverProjectExtension> {
                useJacoco(JACOCO_VERSION)
            }

            extensions.configure<KtlintExtension> {
                android.set(false)
                ignoreFailures.set(false)
                filter {
                    exclude { it.file.path.contains("/build/") }
                }
            }

            extensions.configure<DetektExtension> {
                buildUponDefaultConfig = true
                allRules = false
                parallel = true
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            }
            tasks.withType<Detekt>().configureEach {
                jvmTarget = "17"
                reports {
                    html.required.set(true)
                    sarif.required.set(true)
                    xml.required.set(false)
                    txt.required.set(false)
                    md.required.set(false)
                }
            }
        }
}
