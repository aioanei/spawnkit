import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Pure-Kotlin core library: every SpawnKit target including native desktop, explicit API, warnings as errors. */
class SpawnkitCorePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")
            extensions.configure<KotlinMultiplatformExtension> {
                configureSpawnkitTargets(this, nativeDesktop = true, nodeTests = true)
                explicitApi()
                compilerOptions {
                    allWarningsAsErrors.set(true)
                }
            }
        }
    }
}
