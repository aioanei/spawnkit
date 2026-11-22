import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Where `spawnkit.templates` reads documents from and what it generates; mirrors the Toolchain plugin's settings. */
interface SpawnkitTemplatesExtension {
    /** Directory under the root project holding one `<name>/project.json` per document. */
    val directory: Property<String>

    /** Package of the generated object. */
    val packageName: Property<String>

    /** Name of the generated object. */
    val objectName: Property<String>
}

/**
 * Core library whose `commonMain` contains a generated object embedding `<root>/<directory>/<name>/project.json`
 * documents: by default `EmbeddedTemplates` built from `<root>/templates`.
 */
class SpawnkitTemplatesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(SpawnkitCorePlugin::class.java)
            val settings =
                extensions.create<SpawnkitTemplatesExtension>("spawnkitTemplates").apply {
                    directory.convention("templates")
                    packageName.convention("dev.spawnkit.templates")
                    objectName.convention("EmbeddedTemplates")
                }
            val generate =
                tasks.register<GenerateEmbeddedTemplatesTask>("generateEmbeddedTemplates") {
                    group = "build"
                    description = "Embeds project documents into generated Kotlin sources."
                    templatesDir.set(settings.directory.map { rootProject.layout.projectDirectory.dir(it) })
                    packageName.set(settings.packageName)
                    objectName.set(settings.objectName)
                    outputDir.set(layout.buildDirectory.dir("generated/templates/commonMain/kotlin"))
                }
            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.getByName("commonMain").kotlin.srcDir(generate.flatMap { it.outputDir })
            }
        }
    }
}
