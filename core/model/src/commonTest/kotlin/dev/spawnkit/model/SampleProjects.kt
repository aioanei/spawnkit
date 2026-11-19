package dev.spawnkit.model

/** A small project that uses every component subtype, for serialization tests. */
internal fun sampleProject(): GameProject {
    val coins = VariableId("coins")
    return GameProject(
        id = ProjectId("sample"),
        meta = ProjectMeta(name = "Sample", description = "Uses every component"),
        settings = GameSettings(startScene = SceneId("level-1"), hud = listOf(coins)),
        tileset =
            mapOf(
                '#' to TileDef(TileBehavior.Solid, ColorRgba(0xFF5B6EE1)),
                '=' to TileDef(TileBehavior.OneWay, ColorRgba(0xFF8F9BEF)),
                '^' to TileDef(TileBehavior.Hazard, ColorRgba(0xFFE15B5B)),
            ),
        variables = listOf(VariableDef(coins, name = "Coins")),
        prefabs =
            listOf(
                Prefab(
                    id = PrefabId("coin"),
                    name = "Coin",
                    tags = setOf("pickup"),
                    components =
                        listOf(
                            Visual(size = Size2(0.6, 0.6), color = ColorRgba(0xFFFFD43B), shape = Shape.Circle),
                            Body(size = Size2(0.6, 0.6), offset = Vec2(0.2, 0.2)),
                            Collectible(variable = coins, amount = 1.0),
                        ),
                ),
            ),
        scenes =
            listOf(
                Scene(
                    id = SceneId("level-1"),
                    name = "Level 1",
                    size = GridSize(width = 6, height = 3),
                    background = ColorRgba(0xFF101828),
                    layers = listOf(TileLayer(LayerId("ground"), "Ground", listOf("......", "..=..^", "######"))),
                    entities =
                        listOf(
                            EntityInstance(
                                id = EntityId("hero"),
                                name = "Hero",
                                position = Vec2(0.0, 1.0),
                                components =
                                    listOf(
                                        Visual(size = Size2(0.8, 0.9), color = ColorRgba(0xFF22D3EE), asset = AssetId("hero-png")),
                                        Body(size = Size2(0.8, 0.9)),
                                        PlayerController(),
                                    ),
                            ),
                            EntityInstance(EntityId("coin-1"), "Coin", Vec2(2.0, 0.0), prefab = PrefabId("coin")),
                            EntityInstance(EntityId("slime"), "Slime", Vec2(3.0, 1.0), components = listOf(Patrol(speed = 2.0))),
                            EntityInstance(EntityId("spikes"), "Spikes", Vec2(4.0, 1.0), components = listOf(Hazard)),
                            EntityInstance(EntityId("flag"), "Checkpoint", Vec2(1.0, 1.0), components = listOf(Checkpoint)),
                            EntityInstance(EntityId("goal"), "Goal", Vec2(5.0, 1.0), components = listOf(Goal)),
                        ),
                ),
            ),
    )
}
