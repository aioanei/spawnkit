package dev.spawnkit.engine

import dev.spawnkit.model.Body
import dev.spawnkit.model.Checkpoint
import dev.spawnkit.model.Collectible
import dev.spawnkit.model.Component
import dev.spawnkit.model.EmptyTileChars
import dev.spawnkit.model.EntityInstance
import dev.spawnkit.model.GameProject
import dev.spawnkit.model.Goal
import dev.spawnkit.model.Hazard
import dev.spawnkit.model.Patrol
import dev.spawnkit.model.PlayerController
import dev.spawnkit.model.Scene
import dev.spawnkit.model.SceneId
import dev.spawnkit.model.TileBehavior
import dev.spawnkit.model.Visual
import dev.spawnkit.model.prefab
import dev.spawnkit.model.scene
import dev.spawnkit.model.validateRows
import kotlin.reflect.KClass

/** Result of compiling a scene into a [RuntimeWorld]. */
public sealed interface CompileResult {
    /** The scene compiled successfully. */
    public data class Ok(
        val world: RuntimeWorld,
    ) : CompileResult

    /** The scene cannot be played; [errors] are human-readable reasons. */
    public data class Failed(
        val errors: List<String>,
    ) : CompileResult
}

/** Turns an authoring [Scene] into an index-based, mutable [RuntimeWorld] (ADR-0001). */
public object WorldCompiler {
    /** Compiles scene [sceneId] of [project]. Never throws for invalid documents; returns [CompileResult.Failed]. */
    public fun compile(
        project: GameProject,
        sceneId: SceneId,
    ): CompileResult {
        val scene = project.scene(sceneId) ?: return CompileResult.Failed(listOf("Scene '${sceneId.value}' does not exist"))
        val errors = mutableListOf<String>()
        val grid = compileTiles(project, scene, errors)
        val components = scene.entities.map { resolveComponents(project, it, errors) }
        val playerIndices = components.indices.filter { components[it].containsKey(PlayerController::class) }
        if (playerIndices.size != 1) {
            errors += "Scene '${scene.name}' needs exactly one entity with a PlayerController, found ${playerIndices.size}"
        }
        if (errors.isNotEmpty()) return CompileResult.Failed(errors)

        val variableIndex = project.variables.mapIndexed { index, def -> def.id to index }.toMap()
        val store = buildStore(scene.entities, components, variableIndex)
        val playerIndex = playerIndices.single()
        val controller = components[playerIndex][PlayerController::class] as PlayerController
        val player =
            PlayerState(
                index = playerIndex,
                controller = controller,
                spawnX = store.x[playerIndex],
                spawnY = store.y[playerIndex],
                deathCounter = controller.deathCounter?.let { variableIndex[it] } ?: -1,
            )
        val variables = DoubleArray(project.variables.size) { project.variables[it].initial }
        return CompileResult.Ok(RuntimeWorld(project, scene, grid, store, variableIndex, variables, player))
    }

    private fun compileTiles(
        project: GameProject,
        scene: Scene,
        errors: MutableList<String>,
    ): TileGrid {
        val width = scene.size.width
        val height = scene.size.height
        val cells = Array(width * height) { TileBehavior.Empty }
        scene.layers.forEach { layer ->
            errors += layer.validateRows(width, height)
            layer.rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, ch ->
                    if (ch !in EmptyTileChars && x < width && y < height) {
                        val def = project.tileset[ch]
                        if (def == null) {
                            errors += "Layer '${layer.id.value}' uses tile '$ch' at ($x, $y), which is not in the tileset"
                        } else if (def.behavior != TileBehavior.Empty) {
                            cells[y * width + x] = def.behavior
                        }
                    }
                }
            }
        }
        return TileGrid(width, height, cells)
    }

    /** Prefab components first, then the instance's own components replacing those of the same kind. */
    private fun resolveComponents(
        project: GameProject,
        entity: EntityInstance,
        errors: MutableList<String>,
    ): Map<KClass<out Component>, Component> {
        val merged = LinkedHashMap<KClass<out Component>, Component>()
        entity.prefab?.let { prefabId ->
            val prefab = project.prefab(prefabId)
            if (prefab == null) {
                errors += "Entity '${entity.id.value}' references missing prefab '${prefabId.value}'"
            } else {
                prefab.components.forEach { merged[it::class] = it }
            }
        }
        entity.components.forEach { merged[it::class] = it }
        return merged
    }

    private fun buildStore(
        entities: List<EntityInstance>,
        components: List<Map<KClass<out Component>, Component>>,
        variableIndex: Map<dev.spawnkit.model.VariableId, Int>,
    ): EntityStore {
        val count = entities.size
        val visuals = Array(count) { components[it][Visual::class] as Visual? }
        val bodies = Array(count) { components[it][Body::class] as Body? }
        val collectibles = Array(count) { components[it][Collectible::class] as Collectible? }
        val patrols = Array(count) { components[it][Patrol::class] as Patrol? }
        return EntityStore(
            ids = Array(count) { entities[it].id },
            visuals = visuals,
            roles = IntArray(count) { roleFlags(components[it]) },
            x = DoubleArray(count) { entities[it].position.x },
            y = DoubleArray(count) { entities[it].position.y },
            bodyOffsetX = DoubleArray(count) { bodies[it]?.offset?.x ?: 0.0 },
            bodyOffsetY = DoubleArray(count) { bodies[it]?.offset?.y ?: 0.0 },
            bodyWidth = DoubleArray(count) { bodies[it]?.size?.width ?: visuals[it]?.size?.width ?: 1.0 },
            bodyHeight = DoubleArray(count) { bodies[it]?.size?.height ?: visuals[it]?.size?.height ?: 1.0 },
            collectVariable = IntArray(count) { collectibles[it]?.let { c -> variableIndex[c.variable] } ?: -1 },
            collectAmount = DoubleArray(count) { collectibles[it]?.amount ?: 0.0 },
            patrolSpeed = DoubleArray(count) { patrols[it]?.speed ?: 0.0 },
            patrolTurnAtEdges = BooleanArray(count) { patrols[it]?.turnAtEdges ?: false },
        )
    }

    private fun roleFlags(components: Map<KClass<out Component>, Component>): Int {
        var roles = 0
        if (components.containsKey(PlayerController::class)) roles = roles or Roles.PLAYER
        if (components.containsKey(Collectible::class)) roles = roles or Roles.COLLECTIBLE
        if (components.containsKey(Hazard::class)) roles = roles or Roles.HAZARD
        if (components.containsKey(Patrol::class)) roles = roles or Roles.PATROL
        if (components.containsKey(Checkpoint::class)) roles = roles or Roles.CHECKPOINT
        if (components.containsKey(Goal::class)) roles = roles or Roles.GOAL
        return roles
    }
}
