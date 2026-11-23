package dev.spawnkit.ai

import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Direction
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.StandardActions

/**
 * How Character Forge results join a game (ADR-0013), shared by the builder's "Character from photo" dialog and the
 * agent's `make_character` tool so both make exactly the same thing (ADR-0009): every image with its provenance, and a
 * Character showing the chosen image, with a body, the `player` tag, Left and Right walking, and Jump.
 */
public object CharacterRecipe {
    /** Name of the object the recipe makes. */
    public const val NAME: String = "Character"

    /** Undo label of the recipe's batch. */
    public const val LABEL: String = "Add character"

    /** Character Forge styles, ID to name, in the order they are offered. */
    public val STYLES: Map<String, String> = linkedMapOf("pixel" to "Pixel art", "cartoon" to "Cartoon", "chibi" to "Chibi")

    /** [styles] by name in plain words, for example "Pixel art and Cartoon". */
    public fun describe(styles: List<String>): String {
        val names = styles.map { STYLES[it] ?: it }
        return if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString() + " and " + names.last()
    }

    /** What [batch] makes: the operation and the IDs it will create. */
    public class Planned(
        /** One batch: the images, then the Character and its rules. */
        public val operation: EditOperation.Batch,
        /** IDs of the images, in the order of the results. */
        public val imageIds: List<String>,
        /** ID of the Character object. */
        public val characterId: String,
    )

    /**
     * The batch that adds [images] and a Character showing the image of style [chosen] at ([x], [y]) in scene [sceneId].
     * [nextId] is the ID floor the batch mints from (`Editor.saveSnapshot().nextId` or `StagedChanges.nextId`).
     * Returns `null` when no image has style [chosen].
     */
    public fun batch(
        images: List<ForgedImage>,
        chosen: String,
        sceneId: String,
        x: Double,
        y: Double,
        nextId: Long,
    ): Planned? {
        if (images.none { it.style == chosen }) return null
        var next = nextId
        val operations = mutableListOf<EditOperation>()
        val imageIds =
            images.map { image ->
                operations += EditOperation.AddImage(imageName(image), image.data, "Import image ${imageName(image)}", image.provenance)
                "img-${next++}"
            }
        val imageId = imageIds[images.indexOfFirst { it.style == chosen }]
        val components =
            listOf(
                ComponentDoc("graphics:transform", mapOf("x" to x, "y" to y)),
                ComponentDoc(
                    "graphics:sprite",
                    mapOf("asset" to imageId, "width" to SIZE, "height" to SIZE, "pixelated" to (chosen == PIXEL)),
                ),
                ComponentDoc("gameplay:body"),
                ComponentDoc("gameplay:tag", mapOf("tag" to "player")),
            )
        val characterId = "obj-${next++}"
        operations += EditOperation.AddObject(sceneId, NAME, components = components)
        operations += EditOperation.SetRules(sceneId, characterId, RuleBlocks.compile(rules()))
        return Planned(EditOperation.Batch(operations, LABEL), imageIds, characterId)
    }

    /** The name a forged image gets in the game, for example "Pixel art character". */
    public fun imageName(image: ForgedImage): String = "${image.label} character"

    private fun rules(): List<Rule> =
        listOf(
            Rule(Condition.KeyHeld(StandardActions.LEFT), actions = listOf(Action.Move(Direction.Left, WALK))),
            Rule(Condition.KeyHeld(StandardActions.RIGHT), actions = listOf(Action.Move(Direction.Right, WALK))),
            Rule(Condition.KeyPressed(StandardActions.JUMP), actions = listOf(Action.Jump(JUMP))),
        )

    private const val PIXEL = "pixel"
    private const val SIZE = 1.4
    private const val WALK = 4.0
    private const val JUMP = 15.0
}
