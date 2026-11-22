import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Pure-Kotlin library for the client platforms only (Android, iOS, JVM desktop, JS and Wasm), for modules whose
 * dependencies have no native desktop targets, such as Koog in `ai`. Explicit API and warnings as errors, like core.
 */
class SpawnkitClientPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")
            extensions.configure<KotlinMultiplatformExtension> {
                configureSpawnkitTargets(this, nativeDesktop = false, nodeTests = true)
                explicitApi()
                compilerOptions {
                    allWarningsAsErrors.set(true)
                }
            }
        }
    }
}
