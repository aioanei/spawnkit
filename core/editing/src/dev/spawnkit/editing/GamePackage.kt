package dev.spawnkit.editing

import dev.spawnkit.runtime.Runtime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** Decodes and validates a package. Throws [IllegalArgumentException] explaining, for players, why it cannot be played. */
    public fun decode(text: String): GamePackage {
        if (text.length > MAX_PACKAGE_CHARS) throw unplayable("This game is too large to open")
        return try {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: damaged("it is not a game file")
            if (root.string("format") != FORMAT) throw PackageProblem("Choose a SpawnKit game package")
            val version = (root["packageVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (version != PACKAGE_VERSION.toLong()) throw PackageProblem("This game needs a newer SpawnKit player")
            val project =
                try {
                    ProjectCodec.decode((root["project"] as? JsonObject ?: damaged("its game data is missing")).toString())
                } catch (_: IllegalArgumentException) {
                    damaged("its game data could not be read")
                }
            val declared =
                (root["requiredComponents"] as? JsonArray ?: damaged("its capability list is missing"))
                    .map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: damaged("its capability list is invalid") }
            if (declared != project.requiredComponents()) damaged("its contents do not match")
            val title = root.string("title")?.trim()?.takeIf { it.isNotEmpty() && it.length <= GamePackage.MAX_TITLE_LENGTH }
            val start = root.string("startScene")?.takeIf { project.scene(it) != null }
            GamePackage(title ?: damaged("its title is invalid"), start ?: damaged("its start scene is missing"), declared, project)
        } catch (problem: Exception) {
            // Only this codec's own explanations reach players; parser and library details never do.
            throw unplayable((problem as? PackageProblem)?.message ?: "the package is incomplete or damaged", problem)
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun damaged(detail: String): Nothing = throw PackageProblem("The game package is damaged: $detail")

    private fun unplayable(
        detail: String,
        cause: Throwable? = null,
    ) = IllegalArgumentException("This game cannot be played: $detail", cause)

    /** A player-facing explanation raised while decoding. */
    private class PackageProblem(
        message: String,
    ) : IllegalArgumentException(message)
}

private fun Project.requiredComponents(): List<String> =
    scenes
        .flatMap { scene -> scene.objects.flatMap { obj -> obj.components.map { it.componentId } } }
        .distinct()
        .sorted()
