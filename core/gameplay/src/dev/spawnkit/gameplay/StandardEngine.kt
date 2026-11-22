package dev.spawnkit.gameplay

import dev.spawnkit.editing.EditingOperations
import dev.spawnkit.editing.OperationCatalog
import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.program.ProgramFeature
import dev.spawnkit.program.ProgramHost
import dev.spawnkit.program.ProgramHostBuilder
import dev.spawnkit.program.ProgramLimits
import dev.spawnkit.program.TextValue
import dev.spawnkit.runtime.AuthoringInfo
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
     * Everything creators and agents can do to a project of this engine: the general editor operations plus rule edits.
     * The builder's controls and the agent tools both use these entries (ADR-0009).
     */
    public val operations: OperationCatalog = EditingOperations.catalog + RuleOperations.catalog

    /**
     * The scene a "Go to scene" block asked for during the last ticks of [world], or `null`. Delivered requests are
     * acknowledged, so each is returned once. When several rules ask, the last request wins. Players call this after
     * each tick and switch to a fresh world of that scene.
     */
    public fun takeSceneRequest(world: World): String? {
        var requested: String? = null
        for (entity in world.entities(programs.behavior)) {
            val instance = world.get(entity, programs.behavior) ?: continue
            val pending = instance.pendingEffects
            if (pending.isEmpty()) continue
            pending.lastOrNull { it.effect == RuleBlocks.GO_TO_SCENE }?.let { requested = (it.payload as TextValue).value }
            instance.acknowledgeEffects(pending.last().id)
        }
        return requested
    }

    /** Engine defaults. */
    public companion object {
        /** Seed used when a game does not choose one. */
        public const val DEFAULT_SEED: Long = 1L
    }
}
