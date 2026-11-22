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
            GamePreset("coin-dash", "Coin Dash", "Move with the arrows and collect every coin."),
            GamePreset("tap-targets", "Tap Targets", "Tap the moving targets before time runs out."),
            GamePreset("maze-run", "Maze Run", "Find the exit without touching a wall."),
        )
}
