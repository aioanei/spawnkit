import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Core library whose `commonMain` contains the generated `EmbeddedTemplates` object built from `<root>/templates`. */
class SpawnkitTemplatesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(SpawnkitCorePlugin::class.java)
            val generate =
                tasks.register<GenerateEmbeddedTemplatesTask>("generateEmbeddedTemplates") {
                    group = "build"
                    description = "Embeds template Game Documents into generated Kotlin sources."
                    templatesDir.set(rootProject.layout.projectDirectory.dir("templates"))
                    outputDir.set(layout.buildDirectory.dir("generated/templates/commonMain/kotlin"))
                }
            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.getByName("commonMain").kotlin.srcDir(generate.flatMap { it.outputDir })
            }
        }
    }
}
