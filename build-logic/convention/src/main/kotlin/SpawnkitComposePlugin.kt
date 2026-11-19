import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Compose Multiplatform library: Android, iOS, JVM desktop, JS and Wasm with the common Compose dependencies. */
class SpawnkitComposePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.configure<KotlinMultiplatformExtension> {
                configureSpawnkitTargets(this, nativeDesktop = false, nodeTests = false)
                // Compose resources (fonts, images) must be packaged into the Android library.
                (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                    androidResources { enable = true }
                }
                sourceSets.getByName("commonMain").dependencies {
                    listOf(
                        "compose-runtime",
                        "compose-foundation",
                        "compose-ui",
                        "compose-material3",
                        "compose-components-resources",
                    ).forEach { implementation(libs.findLibrary(it).get()) }
                }
            }
        }
    }
}
