package dev.spawnkit.build.ktlint

import org.jetbrains.amper.plugins.Configurable

/** Settings of the `ktlint` plugin. Rules and style come from `.editorconfig`, exactly as with Spotless. */
@Configurable
interface Settings {
    /** Directory names that are never linted (build outputs, IDE and tool caches). */
    val excludedDirectories: List<String>
        get() = listOf("build", "bin", ".gradle", ".kotlin", "node_modules")
}
