package dev.spawnkit.editing

import dev.spawnkit.runtime.Runtime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A release package: the portable game that a SpawnKit player runs without the creator app (C6, ADR-0007). It wraps
 * the unchanged [ProjectCodec] document with the title shown to players, the scene to start in and the components the
 * game needs, so a player or release service can refuse an incompatible game before running it. Instances are only
 * created through the validating [GamePackage.of] and [GamePackageCodec.decode], so `copy()` is not public.
 */
@ConsistentCopyVisibility
public data class GamePackage internal constructor(
    /** Player-facing title. */
    public val title: String,
    /** ID of the scene the game starts in. */
    public val startScene: String,
    /** Sorted, distinct component IDs used anywhere in [project]. */
    public val requiredComponents: List<String>,
    /** The authored game. */
    public val project: Project,
) {
    /** Components this game needs that [runtime] does not install; empty when the player can run it. */
    public fun missingComponents(runtime: Runtime): List<String> {
        val installed = runtime.catalog.map { it.component.id }.toSet()
        return requiredComponents.filterNot { it in installed }
    }

    /** Factories and limits. */
    public companion object {
        /** Longest accepted title, in characters. */
        public const val MAX_TITLE_LENGTH: Int = 80

        /**
         * Packages [project] for release. Throws [IllegalArgumentException] with an explanation when the title is blank
         * or too long, the project has no scenes, or [startScene] does not exist.
         */
        public fun of(
            project: Project,
            title: String = project.name,
            startScene: String? = null,
        ): GamePackage {
            val trimmed = title.trim()
            require(trimmed.isNotEmpty()) { "Give the game a title" }
            require(trimmed.length <= MAX_TITLE_LENGTH) { "Keep the title to $MAX_TITLE_LENGTH characters or fewer" }
            require(project.scenes.isNotEmpty()) { "Add a scene before releasing the game" }
            val start = startScene ?: project.scenes.first().id
            require(project.scene(start) != null) { "The start scene no longer exists; choose another one" }
            return GamePackage(trimmed, start, project.requiredComponents(), project)
        }
    }
}

/** Versioned encoding of [GamePackage]. Unsupported or damaged packages fail explicitly. */
public object GamePackageCodec {
    /** Current package format version. */
    public const val PACKAGE_VERSION: Int = 1

    /** Maximum UTF-16 characters in a package: a maximal project file plus the package envelope. */
    public const val MAX_PACKAGE_CHARS: Int = ProjectCodec.MAX_FILE_CHARS + 64 * 1024

    private const val FORMAT = "spawnkit-game"

    /** Encodes [pkg]; the project is embedded as its [ProjectCodec] document. */
    public fun encode(pkg: GamePackage): String {
        val project = Json.parseToJsonElement(ProjectCodec.encode(pkg.project))
        return JsonObject(
            linkedMapOf(
                "format" to JsonPrimitive(FORMAT),
                "packageVersion" to JsonPrimitive(PACKAGE_VERSION),
                "title" to JsonPrimitive(pkg.title),
                "startScene" to JsonPrimitive(pkg.startScene),
                "requiredComponents" to JsonArray(pkg.requiredComponents.map(::JsonPrimitive)),
                "project" to project,
            ),
        ).toString()
    }

    /** Decodes and validates a package. Throws [IllegalArgumentException] explaining why it cannot be played. */
    public fun decode(text: String): GamePackage {
        require(text.length <= MAX_PACKAGE_CHARS) { "This game is too large to open" }
        return try {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: error("not an object")
            val format = (root["format"] as? JsonPrimitive)?.contentOrNull
            require(format == FORMAT) { "Choose a SpawnKit game package" }
            val version = (root["packageVersion"] as? JsonPrimitive)?.longOrNull
            require(version == PACKAGE_VERSION.toLong()) { "This game needs a newer SpawnKit player" }
            val project = ProjectCodec.decode(root.getValue("project").toString())
            val declared = root.getValue("requiredComponents").jsonArray.map { it.jsonPrimitive.content }
            require(declared == project.requiredComponents()) { "The game package is damaged: its contents do not match" }
            GamePackage.of(
                project,
                title = root.getValue("title").jsonPrimitive.content,
                startScene = root.getValue("startScene").jsonPrimitive.content,
            )
        } catch (problem: Exception) {
            // SerializationException extends IllegalArgumentException, so it must be matched first: parser details are
            // not useful to players.
            val detail =
                when (problem) {
                    is SerializationException, is IllegalStateException, is NoSuchElementException -> null
                    else -> problem.message
                }
            throw IllegalArgumentException("This game cannot be played: ${detail ?: "the package is incomplete or damaged"}", problem)
        }
    }
}

private fun Project.requiredComponents(): List<String> =
    scenes
        .flatMap { scene -> scene.objects.flatMap { obj -> obj.components.map { it.componentId } } }
        .distinct()
        .sorted()
