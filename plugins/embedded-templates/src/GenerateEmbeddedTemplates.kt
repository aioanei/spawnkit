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
 * Embeds every `<templatesDir>/<name>/project.json` into a generated Kotlin object (`EmbeddedTemplates` by default), so
 * the documents load identically on every Kotlin target without resource APIs (ADR-0003).
 */
@TaskAction
fun generateEmbeddedTemplates(
    @Input templatesDir: Path,
    @Output outputDir: Path,
    packageName: String,
    objectName: String,
) {
    // Like the former Gradle task: a missing templates directory yields an empty map, and stale output is removed.
    val entries = if (templatesDir.isDirectory()) templatesDir.listDirectoryEntries() else emptyList()
    val templates =
        entries
            .filter { it.isDirectory() && it.resolve("project.json").isRegularFile() }
            .sortedBy { it.name }
            .map { EmbeddedTemplate(it.name, it.resolve("project.json").readText()) }
    outputDir.toFile().listFiles()?.forEach { it.deleteRecursively() }
    val packageDir = outputDir.resolve(packageName.replace('.', '/')).createDirectories()
    packageDir.resolve("$objectName.kt").writeText(renderEmbeddedTemplates(packageName, templates, objectName))
}
