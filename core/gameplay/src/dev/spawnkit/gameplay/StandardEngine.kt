package dev.spawnkit.gameplay

import dev.spawnkit.editing.EditingOperations
import dev.spawnkit.editing.OperationCatalog
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.instantiateObject
import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.graphics.Transform2D
import dev.spawnkit.program.ProgramFeature
import dev.spawnkit.program.ProgramHost
import dev.spawnkit.program.ProgramHostBuilder
import dev.spawnkit.program.ProgramLimits
import dev.spawnkit.program.TextValue
import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.InputAction
import dev.spawnkit.runtime.Runtime
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.World

/** IDs of the standard input actions. Hosts map keys, buttons and touch controls onto them. */
public object StandardActions {
    /** Move left. */
    public const val LEFT: String = "input:left"

    /** Move right. */
    public const val RIGHT: String = "input:right"

    /** Move up. */
    public const val UP: String = "input:up"

    /** Move down. */
    public const val DOWN: String = "input:down"

    /** Jump or the main action. */
    public const val JUMP: String = "input:jump"

    /** Every standard action with its friendly name. */
    public val ALL: List<Pair<String, String>> = listOf(LEFT to "Left", RIGHT to "Right", UP to "Up", DOWN to "Down", JUMP to "Jump")
}

/**
 * The packages every SpawnKit game installs: 2D presentation, gameplay, standard input actions and rules. The builder
 * and exported players construct the same engine, so a game behaves identically while editing and after release.
 * Extensions register further packages through the public runtime SDK.
 */
public class StandardEngine(
    seed: Long = DEFAULT_SEED,
    limits: ProgramLimits = ProgramLimits(),
) {
    private val builder = RuntimeBuilder()

    /** Transforms, shapes, sprites, text and cameras. */
    public val graphics: Graphics2D = Graphics2D(builder)

    /** Tags, numbers, random numbers, touching and tapping. */
    public val gameplay: Gameplay = Gameplay(builder, graphics, seed)

    /** Standard input actions by ID (see [StandardActions]). */
    public val actions: Map<String, InputAction> =
        StandardActions.ALL.associate { (id, name) -> id to builder.action(id, AuthoringInfo(name, category = "Input")) }

    /** Everything rules can read, change and call. */
    public val host: ProgramHost = gameplay.bind(ProgramHostBuilder(limits)).inputs(actions.values.toList()).build()

    /** Rules attached to objects. */
    public val programs: ProgramFeature = ProgramFeature(builder, host)

    /** The frozen runtime used for editing, preview, play and export. */
    public val runtime: Runtime = builder.build()

    /**
     * Everything creators and agents can do to a project of this engine: the general editor operations, grouping and rule
     * edits.
     * The builder's controls and the agent tools both use these entries (ADR-0009).
     */
    public val operations: OperationCatalog = EditingOperations.catalog + ObjectOperations.catalog + RuleOperations.catalog

    /**
     * The scene a "Go to scene" block asked for during the last ticks of [world], or `null`; see [takeRequests]. Spawn
     * requests taken here are dropped, so players that support spawning call [handleRequests] instead.
     */
    public fun takeSceneRequest(world: World): String? = takeRequests(world).scene

    /**
     * What the rules asked for during the last ticks of [world]: a scene change (the last request wins) and copies to
     * spawn, in order. Delivered requests are acknowledged, so each is returned once.
     */
    public fun takeRequests(world: World): EngineRequests {
        var scene: String? = null
        val spawns = mutableListOf<SpawnRequest>()
        for (entity in world.entities(programs.behavior)) {
            val instance = world.get(entity, programs.behavior) ?: continue
            val pending = instance.pendingEffects
            if (pending.isEmpty()) continue
            for (effect in pending) {
                val payload = (effect.payload as? TextValue)?.value ?: continue
                when (effect.effect) {
                    RuleBlocks.GO_TO_SCENE -> scene = payload
                    RuleBlocks.SPAWN -> SpawnRequest.parse(payload)?.let(spawns::add)
                }
            }
            instance.acknowledgeEffects(pending.last().id)
        }
        return EngineRequests(scene, spawns)
    }

    /** [project] as it is played: objects marked Template are left out until a rule spawns a copy. */
    public fun playProject(project: Project): Project = project.withoutTemplates()

    /**
     * Makes the copy [request] asks for in [world], from the authored object of scene [sceneId] in [project] with that
     * tag (a template first). The copy runs its own rules. Returns it, or `null` when no object has the tag or the
     * complete copy would exceed [MAX_OBJECTS] objects.
     */
    public fun spawn(
        world: World,
        project: Project,
        sceneId: String,
        request: SpawnRequest,
    ): EntityHandle? {
        val scene = project.scene(sceneId) ?: return null
        val tag = request.tag.trim().lowercase()
        val source =
            scene.objects
                .filter { it.tagName() == tag }
                .sortedBy { !it.isTemplate() }
                .firstOrNull() ?: return null
        if (world.entityCount + scene.subtree(source.id).size > MAX_OBJECTS) return null
        val copy = instantiateObject(runtime, world, project, sceneId, source.id, programs)?.entity(source.id) ?: return null
        world.remove(copy, gameplay.template)
        val at = world.get(copy, graphics.transform) ?: Transform2D()
        world.set(copy, graphics.transform, at.copy(x = request.x, y = request.y))
        if (request.vx != 0.0 || request.vy != 0.0) world.set(copy, gameplay.motion, Motion(request.vx, request.vy))
        return copy
    }

    /**
     * What players do after each tick of [world], playing scene [sceneId] of [project]: makes the requested copies,
     * then returns the scene a rule asked for, or `null`.
     */
    public fun handleRequests(
        world: World,
        project: Project,
        sceneId: String,
    ): String? {
        val requests = takeRequests(world)
        requests.spawns.forEach { spawn(world, project, sceneId, it) }
        return requests.scene
    }

    /**
     * Carries game-wide values from [from] to [to] when the game changes scene. Every kept number in [from] is recorded
     * in [memory] by tag (the first object with a tag wins, ignoring case and spaces); then every kept number in [to]
     * whose tag is in [memory] takes that value. [memory] lives for the whole play, so a value survives a scene that
     * has no object for it.
     */
    public fun carryKeptNumbers(
        from: World,
        to: World,
        memory: MutableMap<String, Long>,
    ) {
        val seen = mutableSetOf<String>()
        for (entity in from.entities(gameplay.number)) {
            val number = from.get(entity, gameplay.number)?.takeIf { it.keep } ?: continue
            val key = keptKey(from, entity) ?: continue
            if (seen.add(key)) memory[key] = number.value
        }
        for (entity in to.entities(gameplay.number)) {
            val number = to.get(entity, gameplay.number)?.takeIf { it.keep } ?: continue
            val value = keptKey(to, entity)?.let(memory::get) ?: continue
            to.set(entity, gameplay.number, number.copy(value = value))
        }
    }

    private fun keptKey(
        world: World,
        entity: EntityHandle,
    ): String? =
        world
            .get(entity, gameplay.tag)
            ?.name
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    /** Engine defaults. */
    public companion object {
        /** Seed used when a game does not choose one. */
        public const val DEFAULT_SEED: Long = 1L

        /** Most objects a play world holds; spawning stops there, so a runaway spawner cannot exhaust memory. */
        public const val MAX_OBJECTS: Int = 2_000
    }
}
