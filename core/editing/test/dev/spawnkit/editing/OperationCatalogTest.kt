package dev.spawnkit.editing

import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.runtime.RuntimeBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OperationCatalogTest {
    private class Fixture {
        val builder = RuntimeBuilder()
        val graphics = Graphics2D(builder)
        val runtime = builder.build()
        val editor = Editor(runtime)
        val catalog = EditingOperations.catalog
        val scene: String get() =
            editor.project.scenes
                .first()
                .id

        fun build(
            id: String,
            arguments: Map<String, Any?>,
        ): BuildResult = catalog.build(id, arguments, editor.catalogContext())

        fun json(
            id: String,
            input: String,
        ): BuildResult = catalog.buildFromJson(id, Json.parseToJsonElement(input).jsonObject, editor.catalogContext())

        fun box(): String {
            val result =
                editor.apply(
                    EditOperation.AddObject(
                        scene,
                        "Box",
                        components = listOf(ComponentDoc("graphics:transform"), ComponentDoc("graphics:shape")),
                    ),
                )
            return assertIs<OperationResult.Applied>(result).createdIds.single()
        }
    }

    @Test
    fun everyEntryIsDescribedAndDerivesAnAgentTool() {
        val fixture = Fixture()
        for (spec in fixture.catalog.operations) {
            assertTrue(spec.authoring.name.isNotBlank() && spec.authoring.description.isNotBlank(), spec.id)
            assertTrue(spec.examples.isNotEmpty(), spec.id)
            val tool = spec.toolDefinition(fixture.runtime)
            assertEquals(spec.id, tool.name)
            assertTrue(spec.authoring.description in tool.description, spec.id)
            assertEquals(JsonPrimitive("object"), tool.inputSchema["type"])
            val properties = assertIs<JsonObject>(tool.inputSchema["properties"])
            assertEquals(spec.parameters.map { it.id }.toSet(), properties.keys, spec.id)
            val required = assertIs<JsonArray>(tool.inputSchema["required"]).map { (it as JsonPrimitive).content }
            assertEquals(spec.parameters.filter { it.required }.map { it.id }, required, spec.id)
        }
        val component = fixture.catalog["add_component"]!!.toolDefinition(fixture.runtime)
        val choices = assertIs<JsonObject>(assertIs<JsonObject>(component.inputSchema["properties"])["component"])["enum"]
        assertEquals(fixture.runtime.catalog.map { JsonPrimitive(it.component.id) }, assertIs<JsonArray>(choices).toList())
    }

    /**
     * Every editor operation is either a catalog entry or is listed here with the reason it has none. The `when` has no
     * `else`, so a new operation does not compile until it is classified.
     */
    @Test
    fun everyEditorOperationIsCoveredOrHasARecordedReason() {
        val fixture = Fixture()
        val samples =
            listOf(
                EditOperation.RenameProject("x"),
                EditOperation.AddObject("s", "x"),
                EditOperation.RemoveObject("s", "o"),
                EditOperation.RenameObject("s", "o", "x"),
                EditOperation.ReparentObject("s", "o", null),
                EditOperation.DuplicateObject("s", "o"),
                EditOperation.AddComponent("s", "o", "c"),
                EditOperation.RemoveComponent("s", "o", "c"),
                EditOperation.SetProperty("s", "o", "c", "p", 1L),
                EditOperation.AddScene("x"),
                EditOperation.RenameScene("s", "x"),
                EditOperation.SetRules("s", "o", emptyList()),
                EditOperation.Batch(emptyList(), "x"),
            )
        for (operation in samples) {
            val entry: String? =
                when (operation) {
                    is EditOperation.RenameProject -> "rename_project"

                    is EditOperation.AddObject -> "add_object"

                    is EditOperation.RemoveObject -> "remove_object"

                    is EditOperation.RenameObject -> "rename_object"

                    is EditOperation.DuplicateObject -> "duplicate_object"

                    is EditOperation.AddComponent -> "add_component"

                    is EditOperation.RemoveComponent -> "remove_component"

                    is EditOperation.SetProperty -> "set_property"

                    // No builder control yet; added to the catalog together with the control (ADR-0009).
                    is EditOperation.ReparentObject, is EditOperation.AddScene, is EditOperation.RenameScene -> null

                    // Rule edits are catalogued by the rule block package (gameplay's rule operations).
                    is EditOperation.SetRules -> null

                    // A changeset groups catalog operations; it is not an operation of its own.
                    is EditOperation.Batch -> null
                }
            if (entry != null) assertTrue(fixture.catalog[entry] != null, "Missing catalog entry $entry")
        }
    }

    @Test
    fun argumentsAndJsonToolCallsBuildTheOperationsTheBuilderUses() {
        val fixture = Fixture()
        val box = fixture.box()
        val scene = fixture.scene
        assertEquals(
            BuildResult.Ready(EditOperation.RenameObject(scene, box, "Hero")),
            fixture.build("rename_object", mapOf("scene" to scene, "object" to box, "name" to "Hero")),
        )
        assertEquals(
            BuildResult.Ready(EditOperation.SetProperty(scene, box, "graphics:shape", "color", 0xFFFF0000, "Change Color of Box")),
            fixture.json(
                "set_property",
                """{"scene":"$scene","object":"$box","component":"graphics:shape","property":"color","value":"#FF0000"}""",
            ),
        )
        // JSON whole numbers become decimals where a property is decimal.
        val added =
            fixture.json(
                "add_object",
                """{"scene":"$scene","name":"Coin","components":[{"component":"graphics:transform","values":{"x":3,"y":2.5}}]}""",
            )
        assertEquals(
            BuildResult.Ready(
                EditOperation.AddObject(
                    scene,
                    "Coin",
                    components = listOf(ComponentDoc("graphics:transform", mapOf("x" to 3.0, "y" to 2.5))),
                ),
            ),
            added,
        )
        assertIs<OperationResult.Applied>(fixture.editor.apply(added))
        assertEquals("Add Coin", fixture.editor.undoLabel)
        assertTrue(fixture.editor.undo())
        assertEquals(
            listOf("Box"),
            fixture.editor.project.scenes
                .single()
                .objects
                .map { it.name },
        )
    }

    @Test
    fun badArgumentsAreRefusedInPlainLanguageWithoutChanges() {
        val fixture = Fixture()
        val box = fixture.box()
        val scene = fixture.scene
        val before = fixture.editor.project

        fun refused(
            id: String,
            arguments: Map<String, Any?>,
        ): String = assertIs<BuildResult.Rejected>(fixture.build(id, arguments)).message

        assertEquals("Give a value for Name", refused("rename_object", mapOf("scene" to scene, "object" to box)))
        assertTrue("no setting called 'colour'" in refused("rename_project", mapOf("name" to "A", "colour" to "red")))
        assertEquals("There is no object 'obj-99' in Main scene", refused("remove_object", mapOf("scene" to scene, "object" to "obj-99")))
        assertEquals("There is no scene 'scene-9'", refused("remove_object", mapOf("scene" to "scene-9", "object" to box)))
        assertEquals(
            "No installed component is called 'physics:body'",
            refused("add_component", mapOf("scene" to scene, "object" to box, "component" to "physics:body")),
        )
        assertEquals(
            "Shape has no 'radius'",
            refused(
                "set_property",
                mapOf(
                    "scene" to scene,
                    "object" to box,
                    "component" to "graphics:shape",
                    "property" to "radius",
                    "value" to 1,
                ),
            ),
        )
        assertTrue(
            refused(
                "set_property",
                mapOf("scene" to scene, "object" to box, "component" to "graphics:transform", "property" to "x", "value" to "far"),
            ).startsWith("X needs a number"),
        )
        assertTrue(
            refused(
                "set_property",
                mapOf("scene" to scene, "object" to box, "component" to "graphics:transform", "property" to "x", "value" to 1e9),
            ).startsWith("X: Choose a number"),
        )
        assertEquals("There is no operation called 'fly'", refused("fly", emptyMap()))
        assertEquals(before, fixture.editor.project)
        // The editor still refuses what only the project can rule out.
        assertIs<OperationResult.Rejected>(
            fixture.editor.apply(fixture.build("add_component", mapOf("scene" to scene, "object" to box, "component" to "graphics:shape"))),
        )
    }

    @Test
    fun variantsListsAndChoicesAreCheckedAndRoundTripThroughJson() {
        val target =
            ParameterType.OneOf(
                "kind",
                listOf(
                    Variant("self", dev.spawnkit.runtime.AuthoringInfo("This object", "The rule's own object")),
                    Variant(
                        "tagged",
                        dev.spawnkit.runtime.AuthoringInfo("Object with a tag", "The first object with the tag"),
                        listOf(
                            Parameter(
                                "tag",
                                "Tag",
                                "Tag to find",
                                ParameterType.Value(
                                    dev.spawnkit.runtime.ValueKind
                                        .Text(10),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        val direction =
            dev.spawnkit.runtime.ValueKind.Choice(
                listOf(dev.spawnkit.runtime.ChoiceOption("left", "Left"), dev.spawnkit.runtime.ChoiceOption("right", "Right")),
            )
        val parameters =
            listOf(
                Parameter("targets", "Targets", "Who", ParameterType.ListOf(target, 2)),
                Parameter("direction", "Direction", "Where", ParameterType.Value(direction), default = "left"),
            )
        val valid =
            checkArguments(
                parameters,
                mapOf(
                    "targets" to listOf("self", Picked("tagged", mapOf("tag" to "coin"))),
                    "direction" to "Right",
                ),
            )
        val values = assertIs<CheckedArguments.Valid>(valid).values
        assertEquals(listOf(Picked("self"), Picked("tagged", mapOf("tag" to "coin"))), values["targets"])
        assertEquals("right", values["direction"], "Choices accept their label")
        val json = encodeArguments(parameters, values)
        assertEquals(values, (checkArguments(parameters, decodeArguments(parameters, json)) as CheckedArguments.Valid).values)
        assertEquals(
            "Object with a tag: Tag: Use at most 10 characters",
            (
                checkArguments(
                    parameters,
                    mapOf("targets" to listOf(Picked("tagged", mapOf("tag" to "x".repeat(11))))),
                ) as CheckedArguments.Invalid
            ).message,
        )
        assertEquals(
            "Targets: use at most 2",
            (checkArguments(parameters, mapOf("targets" to listOf("self", "self", "self"))) as CheckedArguments.Invalid).message,
        )
        assertEquals(
            "left",
            (checkArguments(parameters, mapOf("targets" to emptyList<Any>())) as CheckedArguments.Valid).values["direction"],
            "Omitted arguments take their default",
        )
    }
}
