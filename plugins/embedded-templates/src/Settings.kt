package dev.spawnkit.build.templates

import org.jetbrains.amper.plugins.Configurable

/** Settings of the `embedded-templates` plugin. */
@Configurable
interface Settings {
    /** Package of the generated `EmbeddedTemplates` object. */
    val packageName: String get() = "dev.spawnkit.templates"
}
