package dev.spawnkit.build.templates

import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Embeds every `templates/<name>/project.json` into a generated `EmbeddedTemplates` Kotlin object, so template
 * documents load identically on every Kotlin target without resource APIs (ADR-0003).
 */
@TaskAction
fun generateEmbeddedTemplates(
    @Input templatesDir: Path,
    @Output outputDir: Path,
    packageName: String,
) {
    val templates =
        templatesDir
            .listDirectoryEntries()
            .filter { it.isDirectory() && it.resolve("project.json").isRegularFile() }
            .sortedBy { it.name }
            .map { EmbeddedTemplate(it.name, it.resolve("project.json").readText()) }
    val packageDir = outputDir.resolve(packageName.replace('.', '/')).createDirectories()
    packageDir.resolve("EmbeddedTemplates.kt").writeText(renderEmbeddedTemplates(packageName, templates))
}
