package dev.spawnkit.presets

import dev.spawnkit.editing.Project
import dev.spawnkit.editing.ProjectCodec

/**
 * A ready-made game offered by Project → New game (C10). Each is a normal project file in `presets/<id>/`, made with
 * the builder's own operations, so creators can change everything in it.
 */
public class GamePreset(
    /** Folder name under `presets/`. */
    public val id: String,
    /** Game name, the same as the project's name. */
    public val name: String,
    /** One line that tells creators what the game is. */
    public val pitch: String,
    /** Rule blocks the preset shows how to use, by their names in the rule editor; the picker lists them. */
    public val teaches: List<String>,
) {
    /** A fresh copy of the preset's project. Throws [IllegalArgumentException] when the embedded file cannot be read. */
    public fun project(): Project =
        ProjectCodec.decode(EmbeddedPresets.all[id] ?: throw IllegalArgumentException("Preset '$id' is missing"))
}

/** The preset games, in the order the New game picker lists them. */
public object GamePresets {
    /** Every preset. */
    public val all: List<GamePreset> =
        listOf(
            GamePreset(
                "coin-dash",
                "Coin Dash",
                "Move with the arrows and collect every coin.",
                listOf("Holding a key", "Touching", "Add to a number", "Count of objects", "Go to scene"),
            ),
            GamePreset(
                "tap-targets",
                "Tap Targets",
                "Tap the moving targets before time runs out.",
                listOf("Tapped", "Every … seconds", "Number is", "Set a number", "Go to scene"),
            ),
            GamePreset(
                "maze-run",
                "Maze Run",
                "Find the exit without touching a wall.",
                listOf("Holding a key", "Touching", "Go to scene"),
            ),
            GamePreset(
                "aura-farmer",
                "Aura Farmer",
                "Tap the Aura Toaster fast enough to farm 50 aura in time.",
                listOf("Tapped", "Add to a number", "Show a number", "Number is", "Every … seconds", "Go to scene"),
            ),
            GamePreset(
                "npc-dodge",
                "NPC Dodge",
                "Survive 20 seconds while NPCs ricochet around you.",
                listOf("Holding a key", "Touching", "Number is", "Set a number", "Every … seconds", "Go to scene"),
            ),
        )
}
