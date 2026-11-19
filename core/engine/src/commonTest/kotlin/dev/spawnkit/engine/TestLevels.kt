package dev.spawnkit.engine

import dev.spawnkit.model.Body
import dev.spawnkit.model.ColorRgba
import dev.spawnkit.model.Component
import dev.spawnkit.model.EntityId
import dev.spawnkit.model.EntityInstance
import dev.spawnkit.model.GameProject
import dev.spawnkit.model.GameSettings
import dev.spawnkit.model.GridSize
import dev.spawnkit.model.LayerId
import dev.spawnkit.model.PlayerController
import dev.spawnkit.model.Prefab
import dev.spawnkit.model.ProjectId
import dev.spawnkit.model.ProjectMeta
import dev.spawnkit.model.Scene
import dev.spawnkit.model.SceneId
import dev.spawnkit.model.Size2
import dev.spawnkit.model.TileBehavior
import dev.spawnkit.model.TileDef
import dev.spawnkit.model.TileLayer
import dev.spawnkit.model.VariableDef
import dev.spawnkit.model.VariableId
import dev.spawnkit.model.Vec2

internal val COINS = VariableId("coins")
internal val DEATHS = VariableId("deaths")
internal const val PLAYER_W = 0.8
internal const val PLAYER_H = 0.9

/** Builds a one-scene project from ASCII rows (`#` solid, `=` one-way, `^` hazard). */
internal fun testProject(
    rows: List<String>,
    player: Vec2,
    entities: List<EntityInstance> = emptyList(),
    prefabs: List<Prefab> = emptyList(),
    controller: PlayerController = PlayerController(deathCounter = DEATHS),
): GameProject {
    val hero =
        EntityInstance(
            id = EntityId("hero"),
            name = "Hero",
            position = player,
            components = listOf(Body(Size2(PLAYER_W, PLAYER_H)), controller),
        )
    return GameProject(
        id = ProjectId("test"),
        meta = ProjectMeta("Test"),
        settings = GameSettings(startScene = SceneId("s")),
        tileset =
            mapOf(
                '#' to TileDef(TileBehavior.Solid, ColorRgba(0xFF000000)),
                '=' to TileDef(TileBehavior.OneWay, ColorRgba(0xFF000000)),
                '^' to TileDef(TileBehavior.Hazard, ColorRgba(0xFF000000)),
            ),
        variables = listOf(VariableDef(COINS, "Coins"), VariableDef(DEATHS, "Deaths")),
        prefabs = prefabs,
        scenes =
            listOf(
                Scene(
                    id = SceneId("s"),
                    name = "S",
                    size = GridSize(rows.first().length, rows.size),
                    background = ColorRgba(0xFF000000),
                    layers = listOf(TileLayer(LayerId("l"), "L", rows)),
                    entities = listOf(hero) + entities,
                ),
            ),
    )
}

internal fun entity(
    id: String,
    x: Double,
    y: Double,
    vararg components: Component,
): EntityInstance = EntityInstance(EntityId(id), id, Vec2(x, y), components = components.toList())

/** A 40×6 level with solid ground rows 4–5; the player starts standing at x = 2 (bottom = 4). */
internal val FLAT_GROUND: List<String> =
    List(4) { ".".repeat(40) } + List(2) { "#".repeat(40) }

internal const val FLAT_GROUND_TOP = 4.0

internal fun GameSession.run(
    ticks: Int,
    input: InputFrame = InputFrame.NONE,
): List<GameEvent> = (1..ticks).flatMap { step(input) }

internal val RuntimeWorld.playerBottom: Double get() = y(playerIndex) + PLAYER_H

internal fun right(): InputFrame = InputFrame.of(InputAction.Right)

internal fun jump(): InputFrame = InputFrame.of(InputAction.Jump)
