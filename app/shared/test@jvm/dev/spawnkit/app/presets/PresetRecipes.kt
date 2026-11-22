package dev.spawnkit.app.presets

import dev.spawnkit.app.editor.DefaultPresets
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.Project
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Comparison
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Direction
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleOperations
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.gameplay.Target

/**
 * How the preset games in `presets/` were made (C10). Every step is a call the builder's own controls make: object
 * presets from the palette, catalog operations for the inspector and scene list, rule operations for the rule editor
 * and "Add title screen". `PresetRecipesTest` checks the files against these recipes and rewrites them on request.
 */
internal object PresetRecipes {
    /** Recipes by preset folder, in picker order. */
    val all: Map<String, () -> Project> =
        linkedMapOf(
            "coin-dash" to ::coinDash,
            "tap-targets" to ::tapTargets,
            "maze-run" to ::mazeRun,
            "aura-farmer" to ::auraFarmer,
            "npc-dodge" to ::npcDodge,
        )

    private val keyMoves =
        listOf(
            StandardActions.LEFT to Direction.Left,
            StandardActions.RIGHT to Direction.Right,
            StandardActions.UP to Direction.Up,
            StandardActions.DOWN to Direction.Down,
        )

    fun coinDash(): Project =
        Recipe("Coin Dash").run {
            renameScene("Level")
            val level = sceneId
            box("Grass", 0.0, 0.0, 8.6, 7.6, 0xFF2E7D32, layer = -1)
            box("Left edge", -4.45, 0.0, 0.3, 8.2, 0xFF5D4037, tag = "edge-left")
            box("Right edge", 4.45, 0.0, 0.3, 8.2, 0xFF5D4037, tag = "edge-right")
            box("Top edge", 0.0, -3.95, 9.2, 0.3, 0xFF5D4037, tag = "edge-top")
            box("Bottom edge", 0.0, 3.95, 9.2, 0.3, 0xFF5D4037, tag = "edge-bottom")
            val score = place("Score", "Coins", -2.6, -3.3)
            set(score, "graphics:text", "text", "Coins: 0")
            set(score, "graphics:text", "size", 0.6)

            val player = place("Player", "Player", -3.0, 0.0)
            keyMoves.forEach { (key, direction) ->
                rule(player, Rule(Condition.KeyHeld(key), actions = listOf(Action.Move(direction, 4.0))))
            }
            // Edges push back as hard as the arrows push, so the player stops at the edge of the field.
            listOf(
                "edge-left" to Direction.Right,
                "edge-right" to Direction.Left,
                "edge-top" to Direction.Down,
                "edge-bottom" to Direction.Up,
            ).forEach { (edge, back) -> rule(player, Rule(Condition.Touching(edge), actions = listOf(Action.Move(back, 4.0)))) }

            val coin = place("Coin", "Coin", -1.0, -2.0)
            rule(
                coin,
                Rule(
                    Condition.Touching("player"),
                    actions =
                        listOf(
                            Action.ChangeNumber(Target.Tagged("score"), 1),
                            Action.ShowNumber(Target.Tagged("score"), "Coins: "),
                            Action.RemoveSelf,
                        ),
                ),
            )
            listOf(1.5 to -2.5, 3.0 to -0.5, 2.0 to 2.0, -0.5 to 2.5, 0.5 to 0.0, -2.5 to 2.6).forEach { (x, y) -> duplicateAt(coin, x, y) }

            val win = endScene("You win", "You win!", "Every coin collected.", "Play again", level, 0xFF1B5E20)
            open(level)
            rule(score, Rule(Condition.CountIs("coin", Comparison.Equal, 0), actions = listOf(Action.GoToScene(win))))
            titleScreen(
                0xFF1B5E20,
                "Arrow keys or the on-screen arrows move you.",
                listOf(
                    "Arrow keys move the Player.",
                    "Edges push the Player back.",
                    "A Coin touching the Player",
                    "adds 1 to Coins, then goes.",
                    "When no coin is left,",
                    "Coins goes to scene You win.",
                ),
                listOf("Try this: select Player, open", "Rules and make Move speed 6."),
            )
        }

    fun tapTargets(): Project =
        Recipe("Tap Targets").run {
            renameScene("Play")
            val play = sceneId
            box("Sky", 0.0, 0.0, 8.6, 7.6, 0xFF283593, layer = -1)
            box("Left wall", -4.45, 0.0, 0.3, 7.6, 0xFF7986CB, tag = "wall-left")
            box("Right wall", 4.45, 0.0, 0.3, 7.6, 0xFF7986CB, tag = "wall-right")
            val score = place("Score", "Score", -2.4, -3.3)
            set(score, "graphics:text", "size", 0.6)
            val timer = place("Score", "Timer", 2.4, -3.3)
            set(timer, "gameplay:tag", "tag", "timer")
            set(timer, "gameplay:number", "number", COUNTDOWN)
            set(timer, "graphics:text", "text", "Time: $COUNTDOWN")
            set(timer, "graphics:text", "size", 0.6)
            rule(
                timer,
                Rule(
                    Condition.EverySeconds(1),
                    actions = listOf(Action.ChangeNumber(Target.Self, -1), Action.ShowNumber(Target.Self, "Time: ")),
                ),
            )

            val slow = target("Target", -3.0, -2.3, 2.5, 0xFFE53935)
            val fast = target("Fast target", 2.0, -1.4, 4.0, 0xFFFF9800)
            listOf(1.0 to -0.5, -2.0 to 1.3).forEach { (x, y) -> duplicateAt(slow, x, y, direction = 1) }
            listOf(-1.0 to 0.4, 3.0 to 2.2).forEach { (x, y) -> duplicateAt(fast, x, y, direction = 1) }

            val win = endScene("You win", "You win!", "Every target hit in time.", "Play again", play, 0xFF1B5E20)
            val timesUp = endScene("Time's up", "Time's up!", "Try to hit them all faster.", "Try again", play, 0xFFB71C1C)
            open(play)
            rule(timer, Rule(Condition.NumberIs(Target.Self, Comparison.AtMost, 0), actions = listOf(Action.GoToScene(timesUp))))
            rule(score, Rule(Condition.CountIs("target", Comparison.Equal, 0), actions = listOf(Action.GoToScene(win))))
            titleScreen(
                0xFF4A148C,
                "Tap each target before the timer runs out.",
                listOf(
                    "Each Target's number is its",
                    "direction: 0 right, 1 left.",
                    "Touching a wall flips it.",
                    "Tapping a Target adds 1 to",
                    "Score and removes it.",
                    "Timer counts down each second;",
                    "at 0 it goes to Time's up.",
                ),
                listOf("Try this: select Timer and", "set its Number to 10."),
            )
        }

    fun mazeRun(): Project =
        Recipe("Maze Run").run {
            renameScene("Maze")
            val maze = sceneId
            box("Floor", 0.0, 0.0, 8.0, 7.0, 0xFF263238, layer = -1)
            val wall = 0xFF90A4AE
            box("Top wall", 0.0, -3.5, 8.3, 0.3, wall, tag = "wall")
            box("Bottom wall", 0.0, 3.5, 8.3, 0.3, wall, tag = "wall")
            box("Left wall", -4.0, 0.0, 0.3, 7.3, wall, tag = "wall")
            box("Right wall", 4.0, 0.0, 0.3, 7.3, wall, tag = "wall")
            box("Wall A", -2.0, -1.0, 0.3, 5.0, wall, tag = "wall")
            box("Wall B", 0.0, 1.0, 0.3, 5.0, wall, tag = "wall")
            box("Wall C", 2.0, -1.0, 0.3, 5.0, wall, tag = "wall")
            box("Exit", 3.0, -2.5, 0.8, 0.8, 0xFF66BB6A, tag = "exit")

            val player = place("Player", "Player", -3.0, -2.5)
            set(player, "graphics:shape", "width", 0.6)
            set(player, "graphics:shape", "height", 0.6)
            keyMoves.forEach { (key, direction) ->
                rule(player, Rule(Condition.KeyHeld(key), actions = listOf(Action.Move(direction, 3.0))))
            }
            rule(player, Rule(Condition.Touching("wall"), actions = listOf(Action.GoToScene(maze))))

            val escaped = endScene("You escaped", "You escaped!", "You found the way out.", "Play again", maze, 0xFF1B5E20)
            open(maze)
            rule(player, Rule(Condition.Touching("exit"), actions = listOf(Action.GoToScene(escaped))))
            titleScreen(
                0xFF37474F,
                "Reach the green exit. Walls send you back!",
                listOf(
                    "Arrow keys move the Player.",
                    "Touching a wall goes to scene",
                    "Maze: the maze starts again.",
                    "Touching the exit goes to",
                    "scene You escaped.",
                ),
                listOf("Try this: add a Box, give it", "the tag wall, make a new path."),
            )
        }

    /** Brain-rot style clicker (C10b): tap the Aura Toaster to farm aura before the timer ends. Original characters. */
    fun auraFarmer(): Project =
        Recipe("Aura Farmer").run {
            renameScene("Farm")
            val farm = sceneId
            box("Background", 0.0, 0.0, 8.6, 7.6, 0xFF311B92, layer = -1)
            label("Goal", 0.0, -3.4, "Farm $AURA_GOAL aura before time runs out", 0.32)
            val aura = place("Score", "Aura", 0.0, -2.5)
            set(aura, "gameplay:tag", "tag", "aura")
            set(aura, "graphics:text", "text", "Aura: 0")
            set(aura, "graphics:text", "size", 0.7)
            val toaster = box("Aura Toaster", 0.0, 0.3, 3.2, 2.4, 0xFFFF7043, tag = "toaster")
            label("Toaster label", 0.0, 0.3, "TAP ME", 0.6)
            rule(
                toaster,
                Rule(
                    Condition.Tapped,
                    actions = listOf(Action.ChangeNumber(Target.Tagged("aura"), 1), Action.ShowNumber(Target.Tagged("aura"), "Aura: ")),
                ),
            )
            val timer = place("Score", "Timer", 0.0, 2.6)
            set(timer, "gameplay:tag", "tag", "timer")
            set(timer, "gameplay:number", "number", AURA_SECONDS)
            set(timer, "graphics:text", "text", "Time: $AURA_SECONDS")
            set(timer, "graphics:text", "size", 0.6)
            rule(
                timer,
                Rule(Condition.EverySeconds(1), actions = listOf(Action.ChangeNumber(Target.Self, -1), Action.ShowNumber(Target.Self, "Time: "))),
            )

            val max = endScene("MAX AURA", "MAX AURA!", "Main character energy unlocked.", "Farm again", farm, 0xFFAD1457)
            val negative = endScene("Negative aura", "Negative aura...", "The toaster is disappointed.", "Try again", farm, 0xFF37474F)
            open(farm)
            rule(aura, Rule(Condition.NumberIs(Target.Self, Comparison.AtLeast, AURA_GOAL), actions = listOf(Action.GoToScene(max))))
            rule(timer, Rule(Condition.NumberIs(Target.Self, Comparison.AtMost, 0), actions = listOf(Action.GoToScene(negative))))
            titleScreen(
                0xFF4A148C,
                "Tap the toaster. Farm that aura.",
                listOf(
                    "Tapping the Aura Toaster adds",
                    "1 to Aura and shows it.",
                    "Aura at $AURA_GOAL or more goes to",
                    "scene MAX AURA.",
                    "Timer counts down each second;",
                    "at 0 it goes to Negative aura.",
                ),
                listOf("Try this: select Aura, open", "Rules and make the goal 30."),
            )
        }

    /**
     * Brain-rot style survival (C10b): the Main Character survives while NPCs bounce along lanes and columns. Original
     * characters. The spots between the lanes and away from the columns are safe, so moving players can survive.
     */
    fun npcDodge(): Project =
        Recipe("NPC Dodge").run {
            renameScene("Dodge")
            val dodge = sceneId
            box("Background", 0.0, 0.0, 8.6, 7.6, 0xFF00695C, layer = -1)
            box("Left edge", -4.45, 0.0, 0.3, 8.2, 0xFF004D40, tag = "edge-left")
            box("Right edge", 4.45, 0.0, 0.3, 8.2, 0xFF004D40, tag = "edge-right")
            box("Top edge", 0.0, -3.95, 9.2, 0.3, 0xFF004D40, tag = "edge-top")
            box("Bottom edge", 0.0, 3.95, 9.2, 0.3, 0xFF004D40, tag = "edge-bottom")
            val timer = place("Score", "Timer", 2.4, -3.4)
            set(timer, "gameplay:tag", "tag", "timer")
            set(timer, "gameplay:number", "number", SURVIVE_SECONDS)
            set(timer, "graphics:text", "text", "Survive: $SURVIVE_SECONDS")
            set(timer, "graphics:text", "size", 0.45)
            rule(
                timer,
                Rule(
                    Condition.EverySeconds(1),
                    actions = listOf(Action.ChangeNumber(Target.Self, -1), Action.ShowNumber(Target.Self, "Survive: ")),
                ),
            )

            val hero = place("Player", "Main Character", -3.0, 0.0)
            set(hero, "graphics:shape", "width", 0.7)
            set(hero, "graphics:shape", "height", 0.7)
            set(hero, "graphics:shape", "color", 0xFFFFD600)
            keyMoves.forEach { (key, direction) -> rule(hero, Rule(Condition.KeyHeld(key), actions = listOf(Action.Move(direction, 4.0)))) }
            listOf("edge-left" to Direction.Right, "edge-right" to Direction.Left, "edge-top" to Direction.Down, "edge-bottom" to Direction.Up)
                .forEach { (edge, back) -> rule(hero, Rule(Condition.Touching(edge), actions = listOf(Action.Move(back, 4.0)))) }

            npc("Lane NPC 1", 2.0, -2.0, 3.0, horizontal = true, direction = 1)
            npc("Lane NPC 2", 3.0, 0.0, 3.5, horizontal = true, direction = 0)
            npc("Lane NPC 3", -1.0, 2.0, 3.0, horizontal = true, direction = 0)
            npc("Column NPC 1", -2.0, -3.0, 2.5, horizontal = false, direction = 0)
            npc("Column NPC 2", 2.0, 3.0, 3.0, horizontal = false, direction = 1)

            val survived = endScene("You survived", "You survived!", "Main character energy.", "Play again", dodge, 0xFF1B5E20)
            val caught = endScene("You got NPC'd", "You got NPC'd", "An NPC bumped into you.", "Try again", dodge, 0xFFB71C1C)
            open(dodge)
            rule(hero, Rule(Condition.Touching("npc"), actions = listOf(Action.GoToScene(caught))))
            rule(timer, Rule(Condition.NumberIs(Target.Self, Comparison.AtMost, 0), actions = listOf(Action.GoToScene(survived))))
            titleScreen(
                0xFF004D40,
                "Survive $SURVIVE_SECONDS seconds. Avoid the NPCs.",
                listOf(
                    "Arrow keys move the Main",
                    "Character; edges push back.",
                    "Each NPC's number is its",
                    "direction; edges flip it.",
                    "Touching an NPC goes to",
                    "scene You got NPC'd.",
                    "Survive the Timer to win.",
                ),
                listOf("Try this: duplicate an NPC", "for even more chaos."),
            )
        }

    /** Seconds on the Tap Targets timer. */
    const val COUNTDOWN = 20L

    /** Aura needed for MAX AURA. */
    const val AURA_GOAL = 50L

    /** Seconds to farm the aura. */
    const val AURA_SECONDS = 15L

    /** Seconds to survive in NPC Dodge. */
    const val SURVIVE_SECONDS = 20L
}

/** The builder, driven through the same calls its controls make. Any rejection stops the recipe with its message. */
private class Recipe(
    name: String,
) {
    val state = EditorState()

    init {
        ok(state.applyCatalog("rename_project", mapOf("name" to name)))
    }

    val sceneId: String get() = state.sceneId

    fun ok(result: OperationResult): OperationResult.Applied =
        result as? OperationResult.Applied ?: error((result as OperationResult.Rejected).message)

    fun renameScene(name: String) = ok(state.applyCatalog("rename_scene", mapOf("scene" to sceneId, "name" to name)))

    /** Adds a scene at the end of the scene list and opens it. */
    fun addScene(name: String): String {
        val id = ok(state.applyCatalog("add_scene", mapOf("name" to name))).createdIds.single()
        open(id)
        return id
    }

    fun open(scene: String) = state.selectScene(scene)

    /** Drops palette preset [preset] at ([x], [y]) and names it [name]. */
    fun place(
        preset: String,
        name: String,
        x: Double,
        y: Double,
    ): String {
        state.add(DefaultPresets.single { it.name == preset }, x, y)
        val id = checkNotNull(state.selection) { state.message ?: "Could not add $preset" }
        if (state.scene.find(id)?.name != name) {
            ok(state.applyCatalog("rename_object", mapOf("scene" to sceneId, "object" to id, "name" to name)))
        }
        return id
    }

    fun set(
        id: String,
        component: String,
        property: String,
        value: Any,
    ) = ok(
        state.applyCatalog(
            "set_property",
            mapOf("scene" to sceneId, "object" to id, "component" to component, "property" to property, "value" to value),
        ),
    )

    fun give(
        id: String,
        component: String,
    ) = ok(state.applyCatalog("add_component", mapOf("scene" to sceneId, "object" to id, "component" to component)))

    fun takeAway(
        id: String,
        component: String,
    ) = ok(state.applyCatalog("remove_component", mapOf("scene" to sceneId, "object" to id, "component" to component)))

    fun rule(
        id: String,
        rule: Rule,
    ) = ok(state.apply(RuleOperations.addRule(state.project, sceneId, id, rule)))

    /** A rectangle from the palette's Box, sized, colored and optionally tagged. */
    fun box(
        name: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        color: Long,
        layer: Long = 0,
        tag: String? = null,
    ): String {
        val id = place("Box", name, x, y)
        set(id, "graphics:shape", "width", width)
        set(id, "graphics:shape", "height", height)
        set(id, "graphics:shape", "color", color)
        if (layer != 0L) set(id, "graphics:shape", "layer", layer)
        if (tag != null) {
            give(id, "gameplay:tag")
            set(id, "gameplay:tag", "tag", tag)
        }
        return id
    }

    /** A line of text: the palette's Score without its number and tag. */
    fun label(
        name: String,
        x: Double,
        y: Double,
        text: String,
        size: Double,
    ): String {
        val id = place("Score", name, x, y)
        takeAway(id, "gameplay:number")
        takeAway(id, "gameplay:tag")
        set(id, "graphics:text", "text", text)
        set(id, "graphics:text", "size", size)
        return id
    }

    /** Duplicates [id] like the inspector's Duplicate and moves the copy to ([x], [y]), optionally setting its number. */
    fun duplicateAt(
        id: String,
        x: Double,
        y: Double,
        direction: Long? = null,
    ): String {
        val copy = ok(state.applyCatalog("duplicate_object", mapOf("scene" to sceneId, "object" to id))).createdIds.first()
        set(copy, "graphics:transform", "x", x)
        set(copy, "graphics:transform", "y", y)
        if (direction != null) set(copy, "gameplay:number", "number", direction)
        return copy
    }

    /** A target that bounces between the side walls; its number is its direction (0 right, 1 left). Tapping scores. */
    /**
     * An NPC that bounces between the edges, along its row when [horizontal] or its column otherwise. Its number is its
     * direction: 0 is right or down, 1 is left or up.
     */
    fun npc(
        name: String,
        x: Double,
        y: Double,
        speed: Double,
        horizontal: Boolean,
        direction: Long,
    ): String {
        val id = box(name, x, y, 0.8, 0.8, 0xFFBDBDBD, tag = "npc")
        give(id, "gameplay:number")
        if (direction != 0L) set(id, "gameplay:number", "number", direction)
        val (forward, backward) = if (horizontal) Direction.Right to Direction.Left else Direction.Down to Direction.Up
        val (far, near) = if (horizontal) "edge-right" to "edge-left" else "edge-bottom" to "edge-top"
        rule(id, Rule(Condition.NumberIs(Target.Self, Comparison.Equal, 0), actions = listOf(Action.Move(forward, speed))))
        rule(id, Rule(Condition.NumberIs(Target.Self, Comparison.Equal, 1), actions = listOf(Action.Move(backward, speed))))
        rule(id, Rule(Condition.Touching(far), actions = listOf(Action.SetNumber(Target.Self, 1))))
        rule(id, Rule(Condition.Touching(near), actions = listOf(Action.SetNumber(Target.Self, 0))))
        return id
    }

    fun target(
        name: String,
        x: Double,
        y: Double,
        speed: Double,
        color: Long,
    ): String {
        val id = place("Circle", name, x, y)
        set(id, "graphics:shape", "width", 0.9)
        set(id, "graphics:shape", "height", 0.9)
        set(id, "graphics:shape", "color", color)
        give(id, "gameplay:tag")
        set(id, "gameplay:tag", "tag", "target")
        give(id, "gameplay:number")
        rule(id, Rule(Condition.NumberIs(Target.Self, Comparison.Equal, 0), actions = listOf(Action.Move(Direction.Right, speed))))
        rule(id, Rule(Condition.NumberIs(Target.Self, Comparison.Equal, 1), actions = listOf(Action.Move(Direction.Left, speed))))
        rule(id, Rule(Condition.Touching("wall-right"), actions = listOf(Action.SetNumber(Target.Self, 1))))
        rule(id, Rule(Condition.Touching("wall-left"), actions = listOf(Action.SetNumber(Target.Self, 0))))
        rule(
            id,
            Rule(
                Condition.Tapped,
                actions =
                    listOf(
                        Action.ChangeNumber(Target.Tagged("score"), 1),
                        Action.ShowNumber(Target.Tagged("score"), "Score: "),
                        Action.RemoveSelf,
                    ),
            ),
        )
        return id
    }

    /** An ending scene with a headline and a button back to scene [back]. */
    fun endScene(
        name: String,
        headline: String,
        message: String,
        button: String,
        back: String,
        color: Long,
    ): String {
        val id = addScene(name)
        box("Background", 0.0, 0.0, 16.0, 9.0, color, layer = -1)
        label("Headline", 0.0, -1.5, headline, 1.2)
        label("Message", 0.0, 0.0, message, 0.45)
        val again = box("$button button", 0.0, 2.0, 4.0, 1.5, 0xFF43A047, tag = "play-button")
        label("$button label", 0.0, 2.0, button, 0.6)
        rule(again, Rule(Condition.Tapped, actions = listOf(Action.GoToScene(back))))
        return id
    }

    /**
     * "Add title screen", then the game's colors, a line on how to play, and a How it works button leading to a scene
     * that explains the rules in [explain] lines (at most 32 characters each) and suggests one change in [tryThis].
     */
    fun titleScreen(
        color: Long,
        howToPlay: String,
        explain: List<String>,
        tryThis: List<String>,
    ): Project {
        state.addTitleScreen()
        check(state.scene.name == "Title") { state.message ?: "No title screen" }
        val title = sceneId
        val objects = state.scene.objects.associate { it.name to it.id }
        set(objects.getValue("Background"), "graphics:shape", "color", color)
        set(objects.getValue("Title text"), "graphics:text", "size", 1.1)
        label("How to play", 0.0, 0.0, howToPlay, 0.4)

        val how = addScene("How it works")
        box("Background", 0.0, 0.0, 16.0, 9.0, 0xFF212121, layer = -1)
        label("Heading", 0.0, -3.3, "How it works", 0.7)
        (explain + tryThis).forEachIndexed { index, line ->
            require(line.length <= LINE) { "'$line' is longer than $LINE characters" }
            val trying = index >= explain.size
            // The suggestion sits a little lower and in yellow, so it reads as an invitation rather than a rule.
            val y = -2.4 + index * 0.6 + if (trying) 0.4 else 0.0
            val id = label(if (trying) "Try this ${index - explain.size + 1}" else "Rule ${index + 1}", 0.0, y, line, 0.34)
            if (trying) set(id, "graphics:text", "color", 0xFFFFEB3B)
        }
        val back = box("Back button", 0.0, 3.3, 3.0, 0.8, 0xFF546E7A, tag = "back-button")
        label("Back label", 0.0, 3.3, "Back", 0.45)
        rule(back, Rule(Condition.Tapped, actions = listOf(Action.GoToScene(title))))

        open(title)
        val button = box("How it works button", 0.0, 3.4, 3.6, 0.8, 0xFF546E7A, tag = "how-button")
        label("How it works label", 0.0, 3.4, "How it works", 0.45)
        rule(button, Rule(Condition.Tapped, actions = listOf(Action.GoToScene(how))))
        return state.editor.saveSnapshot()
    }

    private companion object {
        /** Longest line on a How it works scene that fits a phone held upright. */
        const val LINE = 32
    }
}
