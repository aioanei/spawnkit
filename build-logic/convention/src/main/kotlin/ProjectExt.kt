import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** The shared `libs` version catalog. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Reads an integer version such as `android-compileSdk` from the catalog. */
internal fun Project.intVersion(alias: String): Int =
    libs
        .findVersion(alias)
        .get()
        .requiredVersion
        .toInt()

/** Derives an Android namespace from the Gradle path, e.g. `:core:model` -> `dev.spawnkit.core.model`. */
internal fun Project.spawnkitNamespace(): String = "dev.spawnkit." + path.trim(':').split(':').joinToString(".") { it.lowercase() }
