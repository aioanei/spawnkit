import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Root-project quality gates: Spotless with ktlint (official Kotlin style) and aggregate test tasks.
 *
 * - `allJvmAndWebTests`: JVM, Node.js (JS/Wasm), linuxX64, Android host and server tests (CI on Linux).
 * - `localNativeTests`: mingwX64 tests (Windows developer machines).
 */
class SpawnkitQualityPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            check(this == rootProject) { "spawnkit.quality must be applied to the root project" }
            pluginManager.apply("com.diffplug.spotless")
            val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
            extensions.configure<SpotlessExtension> {
                kotlin {
                    target("**/*.kt")
                    targetExclude("**/build/**", "**/bin/**")
                    ktlint(ktlintVersion)
                }
                kotlinGradle {
                    target("**/*.kts")
                    targetExclude("**/build/**", "**/bin/**")
                    ktlint(ktlintVersion)
                }
            }
            registerAggregate(
                name = "allJvmAndWebTests",
                taskNames = setOf("jvmTest", "jsNodeTest", "wasmJsNodeTest", "linuxX64Test", "testAndroidHostTest", "test"),
                description = "Runs JVM, Node.js (JS/Wasm), linuxX64, Android host and server tests in every module.",
            )
            registerAggregate(
                name = "localNativeTests",
                taskNames = setOf("mingwX64Test"),
                description = "Runs mingwX64 native tests (Windows hosts).",
            )
        }
    }

    private fun Project.registerAggregate(
        name: String,
        taskNames: Set<String>,
        description: String,
    ) {
        tasks.register(name) {
            group = "verification"
            this.description = description
            dependsOn(subprojects.map { sub -> sub.tasks.matching { it.name in taskNames } })
        }
    }
}
