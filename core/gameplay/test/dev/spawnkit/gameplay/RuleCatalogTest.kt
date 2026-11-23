package dev.spawnkit.gameplay

import dev.spawnkit.editing.BuildResult
import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.Picked
import dev.spawnkit.editing.buildFromJson
import dev.spawnkit.editing.toolDefinitions
import dev.spawnkit.program.Handler
import dev.spawnkit.program.SourceRef
import dev.spawnkit.program.Trigger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuleCatalogTest {
    /** One sample of every block type. The `when`s have no `else`, so a new block does not compile until it is listed. */
    private val conditions: List<Condition> =
        listOf(
            Condition.Always,
            Condition.GameStarts,
            Condition.EverySeconds(5),
            Condition.Touching("player"),
            Condition.Tapped,
            Condition.KeyHeld(StandardActions.LEFT),
            Condition.KeyPressed(StandardActions.JUMP),
            Condition.NumberIs(Target.Tagged("score"), Comparison.AtLeast, 10),
            Condition.CountIs("coin", Comparison.Equal, 0),
            Condition.LeftArea(30.0, 20.0),
        ).onEach {
            when (it) {
                Condition.Always, Condition.GameStarts, is Condition.EverySeconds, is Condition.Touching, Condition.Tapped,
                is Condition.KeyHeld, is Condition.KeyPressed, is Condition.NumberIs, is Condition.CountIs,
                is Condition.LeftArea,
                -> Unit
            }
        }

    private val actions: List<Action> =
        listOf(
            Action.Move(Direction.Up, 2.5),
            Action.ChangeNumber(Target.Tagged("score"), -3),
            Action.SetNumber(Target.Self, 7),
            Action.SetText(Target.Tagged("sign"), "Game over"),
            Action.ShowNumber(Target.Self, "Lives: "),
            Action.RemoveSelf,
            Action.GoToScene("scene-2"),
            Action.SetRandomNumber(Target.Self, 1, 6),
            Action.AddNumber(Target.Tagged("coins"), Target.Tagged("income")),
            Action.Spawn("enemy", SpawnPlace.RandomArea(-5, 5, 0, 0), Direction.Down, 3.0),
        ).onEach {
            when (it) {
                is Action.Move, is Action.ChangeNumber, is Action.SetNumber, is Action.SetText, is Action.ShowNumber,
                Action.RemoveSelf, is Action.GoToScene, is Action.SetRandomNumber, is Action.AddNumber,
                is Action.Spawn,
                -> Unit
            }
        }

    @Test
    fun everyBlockHasOneDescribedEntryWhoseArgumentsRoundTrip() {
        assertEquals(conditions.size, RuleBlockCatalog.conditions.size)
        assertEquals(actions.size, RuleBlockCatalog.actions.size)
        assertEquals(conditions.map { RuleBlockCatalog.spec(it).id }.toSet().size, conditions.size)
        assertEquals(actions.map { RuleBlockCatalog.spec(it).id }.toSet().size, actions.size)
        for (spec in RuleBlockCatalog.conditions + RuleBlockCatalog.actions) {
            assertTrue(spec.authoring.name.isNotBlank() && spec.authoring.description.isNotBlank(), spec.id)
            assertTrue(spec.examples.isNotEmpty(), spec.id)
            assertIs<BlockResult.Created<*>>(spec.create(spec.defaults()), "Defaults of ${spec.id} make a block")
        }
        for (condition in conditions) {
            assertEquals(BlockResult.Created(condition), RuleBlockCatalog.condition(RuleBlockCatalog.picked(condition)))
        }
        for (action in actions) {
            assertEquals(BlockResult.Created(action), RuleBlockCatalog.action(RuleBlockCatalog.picked(action)))
        }
    }

    @Test
    fun limitsAndWrongPartsAreExplained() {
        fun invalid(result: BlockResult<*>): String = assertIs<BlockResult.Invalid>(result).message
        assertEquals(
            "Seconds: Choose a whole number from 1 to 86400",
            invalid(RuleBlockCatalog.condition(Picked("EverySeconds", mapOf("seconds" to 0L)))),
        )
        // Decimal limits print differently on JS, so only the start is compared.
        assertTrue(invalid(RuleBlockCatalog.action(Picked("Move", mapOf("speed" to 5000)))).startsWith("Speed: Choose a number from 0"))
        assertEquals("Give a value for Tag", invalid(RuleBlockCatalog.condition(Picked("Touching"))))
        assertTrue(invalid(RuleBlockCatalog.condition(Picked("Move"))).startsWith("Move is not a condition block"))
        assertTrue(invalid(RuleBlockCatalog.action(Picked("Fly"))).startsWith("Fly is not an action block, so it cannot be a Do block"))
        assertEquals(
            BlockResult.Created(Action.ChangeNumber(Target.Tagged("score"), 5)),
            RuleBlockCatalog.action(Picked("ChangeNumber", mapOf("target" to Picked("tagged", mapOf("tag" to "score")), "amount" to 5))),
            "Plain numbers are accepted where whole numbers are expected",
        )
    }

    private class Fixture {
        val engine = StandardEngine()
        val editor = Editor(engine.runtime, programs = engine.programs)
        val scene =
            editor.project.scenes
                .first()
                .id
        val coin = add("Coin", ComponentDoc("gameplay:tag", mapOf("tag" to "coin")))

        fun add(
            name: String,
            vararg components: ComponentDoc,
        ): String =
            assertIs<OperationResult.Applied>(
                editor.apply(EditOperation.AddObject(scene, name, components = components.toList())),
            ).createdIds.single()

        fun call(
            id: String,
            arguments: Map<String, Any?>,
        ): OperationResult =
            editor.apply(engine.operations.build(id, mapOf("scene" to scene, "object" to coin) + arguments, editor.catalogContext()))

        fun json(
            id: String,
            input: String,
        ): OperationResult {
            val body = Json.parseToJsonElement(input).jsonObject
            val withObject =
                kotlinx.serialization.json.JsonObject(
                    body + mapOf("scene" to Json.parseToJsonElement("\"$scene\""), "object" to Json.parseToJsonElement("\"$coin\"")),
                )
            return editor.apply(engine.operations.buildFromJson(id, withObject, editor.catalogContext()))
        }

        fun rules(): List<RuleItem> =
            RuleBlocks.decompile(
                editor.project
                    .scene(scene)!!
                    .find(coin)!!
                    .rules,
            )
    }

    private val collect =
        Rule(
            Condition.Touching("player"),
            actions =
                listOf(
                    Action.ChangeNumber(Target.Tagged("score"), 1),
                    Action.ShowNumber(Target.Tagged("score"), "Score: "),
                    Action.RemoveSelf,
                ),
        )

    @Test
    fun aJsonToolCallBuildsTheSameRuleAsTheBlocksAndUndoesInOneStep() {
        val fixture = Fixture()
        val result =
            fixture.json(
                "add_rule",
                """
                {"when":{"block":"Touching","tag":"player"},
                 "do":[{"block":"ChangeNumber","target":{"kind":"tagged","tag":"score"},"amount":1},
                       {"block":"ShowNumber","target":{"kind":"tagged","tag":"score"}},
                       {"block":"RemoveSelf"}]}
                """.trimIndent(),
            )
        assertIs<OperationResult.Applied>(result)
        assertEquals(listOf(RuleItem.Blocks(collect)), fixture.rules())
        assertEquals(
            RuleBlocks.compile(listOf(collect)),
            fixture.editor.project
                .scene(fixture.scene)!!
                .find(fixture.coin)!!
                .rules,
        )
        assertEquals("Add a rule to Coin", fixture.editor.undoLabel)
        fixture.editor.undo()
        assertEquals(emptyList(), fixture.rules())
    }

    @Test
    fun blocksAreAddedChangedAndRemovedByTheNumbersTheBuilderShows() {
        val fixture = Fixture()
        fixture.call("add_rule", mapOf("when" to Picked("Touching", mapOf("tag" to "player"))))
        fixture.call("add_block", mapOf("rule" to 1L, "block" to Picked("ChangeNumber", mapOf("target" to "self", "amount" to 1L))))
        fixture.call("add_block", mapOf("rule" to 1L, "block" to Picked("Tapped")))
        // "Make coins worth 5"
        assertIs<OperationResult.Applied>(
            fixture.call(
                "change_block",
                mapOf(
                    "rule" to 1L,
                    "part" to "do",
                    "position" to 1L,
                    "block" to Picked("ChangeNumber", mapOf("amount" to 5L)),
                ),
            ),
        )
        assertIs<OperationResult.Applied>(fixture.call("remove_block", mapOf("rule" to 1L, "part" to "and_if", "position" to 1L)))
        assertEquals(
            listOf(RuleItem.Blocks(Rule(Condition.Touching("player"), actions = listOf(Action.ChangeNumber(Target.Self, 5))))),
            fixture.rules(),
        )

        fun refused(
            id: String,
            arguments: Map<String, Any?>,
        ) = assertIs<OperationResult.Rejected>(fixture.call(id, arguments)).message
        assertEquals(
            "A rule always has a When block; change it instead, or delete the rule",
            refused("remove_block", mapOf("rule" to 1L, "part" to "when")),
        )
        assertEquals("There is no rule 3; rules go from 1 to 1", refused("remove_rule", mapOf("rule" to 3L)))
        assertEquals(
            "RemoveSelf is an action; When and And if blocks must be conditions",
            refused("change_block", mapOf("rule" to 1L, "part" to "when", "block" to Picked("RemoveSelf"))),
        )
        assertEquals(
            "There is no Do block 4; they go from 1 to 1",
            refused(
                "remove_block",
                mapOf(
                    "rule" to 1L,
                    "part" to "do",
                    "position" to 4L,
                ),
            ),
        )
    }

    @Test
    fun movingAndDuplicatingRenumbersBlockRulesAndKeepsAdvancedRules() {
        val fixture = Fixture()
        val advanced = Handler(Trigger.EveryTick, emptyList(), SourceRef("custom", "Made elsewhere"))
        fixture.editor.apply(
            EditOperation.SetRules(
                fixture.scene,
                fixture.coin,
                listOf(advanced) + RuleBlocks.compile(listOf(Rule(Condition.Tapped), collect)),
            ),
        )
        assertIs<OperationResult.Applied>(fixture.call("move_rule", mapOf("rule" to 3L, "to" to 1L)))
        assertIs<OperationResult.Applied>(fixture.call("duplicate_rule", mapOf("rule" to 1L)))
        val items = fixture.rules()
        assertEquals(
            listOf(
                RuleItem.Blocks(collect),
                RuleItem.Blocks(collect),
                RuleItem.Advanced(advanced),
                RuleItem.Blocks(Rule(Condition.Tapped)),
            ),
            items,
        )
        // Block rules carry their position, so problems point at the rule the builder shows.
        val handlers =
            fixture.editor.project
                .scene(fixture.scene)!!
                .find(fixture.coin)!!
                .rules
        assertEquals(listOf("rule0", "rule1", "custom", "rule3"), handlers.map { it.source.nodeId })
        assertEquals("Duplicate rule 1 of Coin", fixture.editor.undoLabel)
        assertEquals(
            "Rule 3 is an advanced rule; its blocks cannot be changed here",
            assertIs<OperationResult.Rejected>(fixture.call("add_block", mapOf("rule" to 3L, "block" to Picked("RemoveSelf")))).message,
        )
    }

    @Test
    fun theTypedPathAndTheCatalogBuildIdenticalOperations() {
        val fixture = Fixture()
        val typed = RuleOperations.addRule(fixture.editor.project, fixture.scene, fixture.coin, collect)
        val catalog =
            fixture.engine.operations.build(
                "add_rule",
                mapOf(
                    "scene" to fixture.scene,
                    "object" to fixture.coin,
                    "when" to RuleBlockCatalog.picked(collect.condition),
                    "do" to collect.actions.map(RuleBlockCatalog::picked),
                ),
                fixture.editor.catalogContext(),
            )
        assertEquals(typed, catalog)
        assertIs<BuildResult.Ready>(typed)
        val tools =
            fixture.engine.operations
                .toolDefinitions(fixture.engine.runtime)
                .map { it.name }
        assertTrue(
            tools.containsAll(
                listOf("add_rule", "add_block", "change_block", "remove_block", "move_rule", "duplicate_rule", "remove_rule"),
            ),
        )
        assertTrue(tools.containsAll(listOf("add_object", "set_property")))
    }
}
