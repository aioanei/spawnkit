package dev.spawnkit.build.templates

import org.jetbrains.amper.plugins.Configurable

/** Settings of the `embedded-templates` plugin. */
@Configurable
interface Settings {
    /** Package of the generated object. */
    val packageName: String get() = "dev.spawnkit.templates"

    /** Directory under the project root holding one `<name>/project.json` per document. */
    val directory: String get() = "templates"

    /** Name of the generated object. */
    val objectName: String get() = "EmbeddedTemplates"
}
