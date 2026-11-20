package dev.spawnkit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins the version-1 document format. The JSON below is written by hand and must keep decoding to the same values:
 * if this test fails after a refactoring, a property, enum value or `@SerialName` was renamed and existing documents
 * would break. Fix the code, or bump [CURRENT_SCHEMA_VERSION] and add a migration (ADR-0002).
 */
class V1FixtureTest {
    @Test
    fun handWrittenV1DocumentDecodesToExpectedValues() {
        val project = assertIs<DecodeResult.Ok>(SpawnJson.decode(V1_DOCUMENT)).project

        assertEquals(ProjectId("fixture"), project.id)
        assertEquals(ProjectMeta("Fixture", "All v1 fields", "2.1.0"), project.meta)
        assertEquals(
            GameSettings(
                viewportTiles = Size2(16.0, 9.0),
                tilePixels = 16,
                startScene = SceneId("s1"),
                physics = PhysicsSettings(gravity = 40.0, maxFallSpeed = 18.0),
                hud = listOf(VariableId("coins")),
            ),
            project.settings,
        )
        assertEquals(
            mapOf(
                '#' to TileDef(TileBehavior.Solid, ColorRgba(0xFF101010), AssetId("rock")),
                '=' to TileDef(TileBehavior.OneWay, ColorRgba(0xFF202020)),
                '^' to TileDef(TileBehavior.Hazard, ColorRgba(0xFF303030)),
                '~' to TileDef(TileBehavior.Decor, ColorRgba(0xFF404040)),
                'x' to TileDef(TileBehavior.Empty, ColorRgba(0xFF505050)),
            ),
            project.tileset,
        )
        assertEquals(listOf(VariableDef(VariableId("coins"), "Coins", initial = 5.0, persistent = true)), project.variables)
        assertEquals(
            Prefab(PrefabId("coin"), "Coin", setOf("pickup"), listOf(Collectible(VariableId("coins"), amount = 2.0))),
            project.prefabs.single(),
        )

        val scene = project.scenes.single()
        assertEquals(Scene(SceneId("s1"), "One", GridSize(3, 2), ColorRgba(0xFF000000), scene.layers, scene.entities), scene)
        assertEquals(TileLayer(LayerId("l1"), "Ground", listOf("..=", "#^~")), scene.layers.single())
        assertEquals(
            listOf(
                EntityInstance(
                    id = EntityId("hero"),
                    name = "Hero",
                    position = Vec2(0.5, 0.25),
                    tags = setOf("player"),
                    components =
                        listOf(
                            Visual(Size2(0.8, 0.9), ColorRgba(0xFF22D3EE), AssetId("hero"), Shape.Circle),
                            Body(Size2(0.8, 0.9), Vec2(0.1, 0.0)),
                            PlayerController(moveSpeed = 7.0, jumpVelocity = 15.0, coyoteTicks = 4, jumpBufferTicks = 5),
                        ),
                ),
                EntityInstance(EntityId("c1"), "Coin", Vec2(1.0, 0.0), prefab = PrefabId("coin")),
                EntityInstance(
                    id = EntityId("misc"),
                    name = "Misc",
                    position = Vec2(2.0, 0.0),
                    components =
                        listOf(
                            Visual(Size2(1.0, 1.0), ColorRgba(0xFF000000), shape = Shape.Rect),
                            Visual(Size2(1.0, 1.0), ColorRgba(0xFF000000), shape = Shape.RoundedRect),
                            Patrol(speed = 1.5, turnAtEdges = false),
                            Hazard,
                            Checkpoint,
                            Goal,
                        ),
                ),
            ),
            scene.entities,
        )
    }

    private companion object {
        val V1_DOCUMENT =
            """
            {
              "schemaVersion": 1,
              "id": "fixture",
              "meta": { "name": "Fixture", "description": "All v1 fields", "version": "2.1.0" },
              "settings": {
                "viewportTiles": { "width": 16.0, "height": 9.0 },
                "tilePixels": 16,
                "startScene": "s1",
                "physics": { "gravity": 40.0, "maxFallSpeed": 18.0 },
                "hud": ["coins"]
              },
              "tileset": {
                "#": { "behavior": "solid", "color": "#FF101010", "asset": "rock" },
                "=": { "behavior": "oneWay", "color": "#FF202020" },
                "^": { "behavior": "hazard", "color": "#FF303030" },
                "~": { "behavior": "decor", "color": "#FF404040" },
                "x": { "behavior": "empty", "color": "#FF505050" }
              },
              "variables": [ { "id": "coins", "name": "Coins", "initial": 5.0, "persistent": true } ],
              "prefabs": [
                { "id": "coin", "name": "Coin", "tags": ["pickup"],
                  "components": [ { "kind": "collectible", "variable": "coins", "amount": 2.0 } ] }
              ],
              "scenes": [
                {
                  "id": "s1", "name": "One", "size": { "width": 3, "height": 2 }, "background": "#FF000000",
                  "layers": [ { "id": "l1", "name": "Ground", "rows": ["..=", "#^~"] } ],
                  "entities": [
                    { "id": "hero", "name": "Hero", "position": { "x": 0.5, "y": 0.25 }, "tags": ["player"],
                      "components": [
                        { "kind": "visual", "size": { "width": 0.8, "height": 0.9 }, "color": "#FF22D3EE",
                          "asset": "hero", "shape": "circle" },
                        { "kind": "body", "size": { "width": 0.8, "height": 0.9 }, "offset": { "x": 0.1, "y": 0.0 } },
                        { "kind": "player", "moveSpeed": 7.0, "jumpVelocity": 15.0, "coyoteTicks": 4, "jumpBufferTicks": 5 }
                      ] },
                    { "id": "c1", "name": "Coin", "position": { "x": 1.0, "y": 0.0 }, "prefab": "coin" },
                    { "id": "misc", "name": "Misc", "position": { "x": 2.0, "y": 0.0 },
                      "components": [
                        { "kind": "visual", "size": { "width": 1.0, "height": 1.0 }, "color": "#FF000000", "shape": "rect" },
                        { "kind": "visual", "size": { "width": 1.0, "height": 1.0 }, "color": "#FF000000", "shape": "roundedRect" },
                        { "kind": "patrol", "speed": 1.5, "turnAtEdges": false },
                        { "kind": "hazard" },
                        { "kind": "checkpoint" },
                        { "kind": "goal" }
                      ] }
                  ]
                }
              ]
            }
            """.trimIndent()
    }
}
