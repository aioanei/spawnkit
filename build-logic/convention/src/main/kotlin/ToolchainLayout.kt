import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Kotlin Toolchain coexistence (issue #58).
 *
 * A module that has been migrated to the Kotlin Toolchain contains a `module.yaml` and uses the Toolchain layout
 * (`src/`, `src@jvm/`, `test/`, `test@jvm/`, `resources/`, `composeResources/`, ...). Until Gradle is retired, Gradle
 * reads the very same directories, so both builds compile identical sources and Gradle remains the CI authority.
 * Modules without `module.yaml` keep the Gradle default layout.
 */
internal val Project.usesKotlinToolchainLayout: Boolean
    get() = file("module.yaml").isFile

/**
 * Adds the Kotlin Toolchain directory to every Kotlin source set of a migrated module. Directories are added, not
 * replaced: other plugins (Compose resources, embedded templates) register generated source directories on the same
 * source sets, and the Gradle default directories no longer exist once sources have moved.
 */
internal fun Project.configureKotlinToolchainLayout(kmp: KotlinMultiplatformExtension) {
    if (!usesKotlinToolchainLayout) return
    kmp.sourceSets.configureEach {
        val dirs = toolchainDirectories(name) ?: return@configureEach
        kotlin.srcDir(dirs.sources)
        resources.srcDir(dirs.resources)
    }
}

/** Source and resource directories of one Gradle source set in the Kotlin Toolchain layout. */
internal data class ToolchainDirectories(
    val sources: String,
    val resources: String,
)

/**
 * Maps a Gradle KMP source set name to Kotlin Toolchain directories: `commonMain` -> `src`, `jvmTest` -> `test@jvm`,
 * `webMain` -> `src@web`, and the Android KMP library's `androidHostTest` -> `test@android`.
 */
internal fun toolchainDirectories(sourceSetName: String): ToolchainDirectories? {
    val (platform, isTest) =
        when {
            sourceSetName == "androidHostTest" -> "android" to true
            sourceSetName.endsWith("Main") -> sourceSetName.removeSuffix("Main") to false
            sourceSetName.endsWith("Test") -> sourceSetName.removeSuffix("Test") to true
            else -> return null
        }
    val qualifier = if (platform == "common") "" else "@$platform"
    return if (isTest) {
        ToolchainDirectories("test$qualifier", "testResources$qualifier")
    } else {
        ToolchainDirectories("src$qualifier", "resources$qualifier")
    }
}
