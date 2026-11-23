package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.editor.DefaultPresets
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.app.images.ImagePicker
import dev.spawnkit.app.images.LocalImagePicker
import dev.spawnkit.app.images.PickedImage
import dev.spawnkit.app.persistence.DesktopDraftStore
import dev.spawnkit.app.persistence.ProjectFile
import dev.spawnkit.app.persistence.ProjectFilePicker
import dev.spawnkit.app.persistence.ProjectFiles
import dev.spawnkit.app.persistence.ProjectSession
import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.Picked
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.ProjectImages
import dev.spawnkit.editing.buildFromJson
import dev.spawnkit.editing.capabilityProblems
import dev.spawnkit.editing.encodeArguments
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.Target
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Storage for the project menu; parity cases never open or save files. */
private class ParityFiles : ProjectFiles {
    private val directory = Files.createTempDirectory(Files.createDirectories(Path.of("build")), "parity-")
    override val drafts = DesktopDraftStore(directory.resolve("recovery"))
    override val picker =
        object : ProjectFilePicker {
            override fun open(result: (Result<ProjectFile?>) -> Unit) = result(Result.success(null))

            override fun create(
                suggestedName: String,
                result: (Result<ProjectFile?>) -> Unit,
            ) = result(Result.success(null))
        }
}

/**
 * Agentic parity (ADR-0009, issue #69): for every entry of the engine's operation catalog, doing it by hand in the
 * builder and calling the derived agent tool with JSON give the same workspace, the same validation and the same undo.
 * A catalog entry without a case here fails the test.
 */
@OptIn(ExperimentalTestApi::class)
class OperationParityTest {
    /** One capability: the starting game, the creator's taps, and the agent tool's arguments. */
    private class Case(
        val setup: EditorState.() -> Unit = {},
        val ui: ComposeUiTest.(EditorState) -> Unit,
        val tool: EditorState.() -> Map<String, Any?>,
    )

    private fun EditorState.id(name: String): String = scene.objects.first { it.name == name }.id

    private fun EditorState.sceneNamed(name: String): String = project.scenes.first { it.name == name }.id

    private fun EditorState.target(name: String): Map<String, Any?> = mapOf("scene" to sceneId, "object" to id(name))

    private fun EditorState.select(name: String) {
        selection = id(name)
    }

    private fun EditorState.coinRules(vararg rules: Rule) {
        setRuleItems(id("Coin"), rules.map { RuleItem.Blocks(it) })
        select("Coin")
    }

    private fun ComposeUiTest.tap(tag: String) {
        val node = onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    /** A 3 × 2 PNG the fake picker returns. */
    private val logo: ByteArray =
        java.io
            .ByteArrayOutputStream()
            .also { out ->
                val image = java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                image.setRGB(0, 0, 0xFFE91E63.toInt())
                javax.imageio.ImageIO.write(image, "png", out)
            }.toByteArray()

    private val touching = Rule(Condition.Touching("player"))
    private val scoring = Rule(Condition.Touching("player"), actions = listOf(Action.ChangeNumber(Target.Tagged("score"), 1)))
    private val tapped = Rule(Condition.Tapped, actions = listOf(Action.RemoveSelf))
    private val tagged = Picked("tagged", mapOf("tag" to "score"))

    private val cases: Map<String, Case> =
        mapOf(
            "rename_project" to
                Case(
                    ui = {
                        tap("project-menu")
                        tap("project-rename")
                        onNodeWithTag("project-name").performTextReplacement("Coin Rush")
                        tap("project-name-done")
                    },
                    tool = { mapOf("name" to "Coin Rush") },
                ),
            "set_ads" to
                Case(
                    ui = {
                        tap("project-menu")
                        tap("project-release")
                        tap("ads-interstitial")
                    },
                    tool = { mapOf("banner" to false, "interstitial" to true, "every" to 3L) },
                ),
            "add_scene" to Case(ui = { tap("scene-add") }, tool = { mapOf("name" to "Scene ${project.scenes.size + 1}") }),
            "rename_scene" to
                Case(
                    ui = {
                        tap("scene-rename")
                        onNodeWithTag("scene-name").performTextReplacement("Forest")
                        tap("scene-name-done")
                    },
                    tool = { mapOf("scene" to sceneId, "name" to "Forest") },
                ),
            "remove_scene" to
                Case(
                    { selectScene(sceneNamed("Bonus")) },
                    { tap("scene-delete") },
                    { mapOf("scene" to sceneNamed("Bonus")) },
                ),
            "move_scene" to
                Case(
                    { selectScene(sceneNamed("Bonus")) },
                    { tap("scene-start") },
                    { mapOf("scene" to sceneNamed("Bonus"), "to" to 1L) },
                ),
            "add_object" to
                Case(
                    ui = { tap("add:Coin") },
                    tool = {
                        val coin = DefaultPresets.first { it.name == "Coin" }
                        mapOf("scene" to sceneId, "name" to "Coin 2", "components" to coin.components(camera.centerX, camera.centerY))
                    },
                ),
            "remove_object" to Case({ select("Coin") }, { tap("delete") }, { target("Coin") }),
            "duplicate_object" to Case({ select("Coin") }, { tap("duplicate") }, { target("Coin") }),
            "add_image" to
                Case(
                    ui = { tap("image-import") },
                    tool = { mapOf("name" to "Logo", "data" to ProjectImages.encode(logo)) },
                ),
            "rename_image" to
                Case(
                    { importImage("Logo", logo) },
                    {
                        tap("image-rename:Logo")
                        onNodeWithTag("image-name").performTextReplacement("Banner")
                        tap("image-name-done")
                    },
                    { mapOf("image" to project.images.single().id, "name" to "Banner") },
                ),
            "remove_image" to
                Case(
                    { importImage("Logo", logo) },
                    { tap("image-remove:Logo") },
                    { mapOf("image" to project.images.single().id) },
                ),
            "move_into" to
                Case(
                    { select("Coin") },
                    {
                        tap("move-into")
                        tap("move-into:Player")
                    },
                    { target("Coin") + ("parent" to id("Player")) },
                ),
            "rename_object" to
                Case(
                    { select("Coin") },
                    {
                        onNodeWithTag("field:name").performTextReplacement("Gold")
                        onNodeWithTag("field:name").performImeAction()
                        waitForIdle()
                    },
                    { target("Coin") + ("name" to "Gold") },
                ),
            "add_component" to
                Case(
                    { select("Player") },
                    { tap("add-component:gameplay:number") },
                    { target("Player") + ("component" to "gameplay:number") },
                ),
            "remove_component" to
                Case(
                    { select("Coin") },
                    { tap("remove-component:gameplay:tag") },
                    { target("Coin") + ("component" to "gameplay:tag") },
                ),
            "set_property" to
                Case(
                    { select("Coin") },
                    { state -> tap("inc:${state.id("Coin")}/graphics:transform/x") },
                    {
                        val x = scene.find(id("Coin"))!!.component("graphics:transform")!!.values["x"] as Double
                        target("Coin") + mapOf("component" to "graphics:transform", "property" to "x", "value" to x + 0.5)
                    },
                ),
            "add_rule" to
                Case(
                    { select("Coin") },
                    {
                        tap("panel:Rules")
                        tap("add-rule")
                        tap("kind:Touching")
                        tap("tag:player")
                        tap("block-done")
                    },
                    { target("Coin") + ("when" to Picked("Touching", mapOf("tag" to "player"))) },
                ),
            "add_block" to
                Case(
                    { coinRules(touching) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:add-do")
                        tap("kind:ChangeNumber")
                        tap("target:score")
                        tap("block-done")
                    },
                    { target("Coin") + mapOf("rule" to 1L, "block" to Picked("ChangeNumber", mapOf("target" to tagged))) },
                ),
            "change_block" to
                Case(
                    { coinRules(scoring) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:do:0")
                        repeat(4) { tap("inc:amount") }
                        tap("block-done")
                    },
                    // "Make coins worth 5"
                    {
                        target("Coin") +
                            mapOf(
                                "rule" to 1L,
                                "part" to "do",
                                "position" to 1L,
                                "block" to Picked("ChangeNumber", mapOf("target" to tagged, "amount" to 5L)),
                            )
                    },
                ),
            "remove_block" to
                Case(
                    { coinRules(scoring.copy(actions = scoring.actions + Action.RemoveSelf)) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:do:1")
                        tap("block-remove")
                    },
                    { target("Coin") + mapOf("rule" to 1L, "part" to "do", "position" to 2L) },
                ),
            "move_rule" to
                Case(
                    { coinRules(tapped, scoring) },
                    {
                        tap("panel:Rules")
                        tap("rule:1:more")
                        tap("rule:1:up")
                    },
                    { target("Coin") + mapOf("rule" to 2L, "to" to 1L) },
                ),
            "duplicate_rule" to
                Case(
                    { coinRules(scoring) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:more")
                        tap("rule:0:duplicate")
                    },
                    { target("Coin") + ("rule" to 1L) },
                ),
            "remove_rule" to
                Case(
                    { coinRules(tapped, scoring) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:delete")
                    },
                    { target("Coin") + ("rule" to 1L) },
                ),
        )

    /** What both paths must agree on after the change. */
    private data class Outcome(
        val project: Project,
        val undoLabel: String?,
        val ruleProblems: Map<String, List<String>>,
        val capabilityProblems: List<String>,
        val afterUndo: Project,
    )

    private fun session(case: Case): ProjectSession {
        val session = ProjectSession(ParityFiles())
        val state = session.state
        for (name in listOf("Score", "Coin", "Player")) state.add(DefaultPresets.first { it.name == name })
        state.apply(EditOperation.AddScene("Bonus"))
        case.setup(state)
        return session
    }

    private fun outcome(
        state: EditorState,
        before: Project,
        label: String,
    ): Outcome {
        val project = state.project
        val undoLabel = state.editor.undoLabel
        val world = state.editor.buildWorld(state.sceneId)
        val problems = world.diagnostics.mapValues { (_, list) -> list.map { it.message } }
        val capabilities = project.capabilityProblems(state.engine.runtime)
        state.undo()
        assertEquals(before, state.project, "$label: one undo restores the starting game")
        return Outcome(project, undoLabel, problems, capabilities, state.project)
    }

    @Test
    fun everyCatalogOperationGivesTheSameResultByHandAndByAgentTool() {
        val catalog = EditorState().engine.operations
        assertEquals(catalog.operations.map { it.id }.toSet(), cases.keys, "Every catalog operation needs a parity case")
        for ((id, case) in cases) checkParity(id, id, case)
    }

    /** The C14 blocks, each added to a rule by hand in the generated block dialog and by the add_block tool. */
    private val blockCases: Map<String, Case> =
        mapOf(
            "SetRandomNumber" to
                Case(
                    { coinRules(touching) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:add-do")
                        tap("kind:SetRandomNumber")
                        tap("target:score")
                        tap("inc:max")
                        tap("block-done")
                    },
                    {
                        target("Coin") +
                            mapOf("rule" to 1L, "block" to Picked("SetRandomNumber", mapOf("target" to tagged, "min" to 1L, "max" to 7L)))
                    },
                ),
            "AddNumber" to
                Case(
                    { coinRules(touching) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:add-do")
                        tap("kind:AddNumber")
                        tap("target:score")
                        tap("from:coin")
                        tap("block-done")
                    },
                    {
                        target("Coin") +
                            mapOf(
                                "rule" to 1L,
                                "block" to
                                    Picked("AddNumber", mapOf("target" to tagged, "from" to Picked("tagged", mapOf("tag" to "coin")))),
                            )
                    },
                ),
            "Spawn" to
                Case(
                    { coinRules(touching) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:add-do")
                        tap("kind:Spawn")
                        tap("tag:coin")
                        tap("direction:Down")
                        repeat(3) { tap("inc:speed") }
                        tap("block-done")
                    },
                    {
                        target("Coin") +
                            mapOf(
                                "rule" to 1L,
                                "block" to
                                    Picked(
                                        "Spawn",
                                        mapOf("tag" to "coin", "place" to Picked("self"), "direction" to "Down", "speed" to 3.0),
                                    ),
                            )
                    },
                ),
            "LeftArea" to
                Case(
                    { coinRules(tapped) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:add-if")
                        tap("kind:LeftArea")
                        tap("block-done")
                    },
                    { target("Coin") + mapOf("rule" to 1L, "block" to Picked("LeftArea", mapOf("width" to 40.0, "height" to 24.0))) },
                ),
            "OnGround" to
                Case(
                    { coinRules(tapped) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:add-if")
                        tap("kind:OnGround")
                        tap("block-done")
                    },
                    { target("Coin") + mapOf("rule" to 1L, "block" to Picked("OnGround")) },
                ),
            "Jump" to
                Case(
                    { coinRules(touching) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:add-do")
                        tap("kind:Jump")
                        tap("inc:speed")
                        tap("block-done")
                    },
                    { target("Coin") + mapOf("rule" to 1L, "block" to Picked("Jump", mapOf("speed" to 16.0))) },
                ),
            "SetSpeed" to
                Case(
                    { coinRules(touching) },
                    {
                        tap("panel:Rules")
                        tap("rule:0:add-do")
                        tap("kind:SetSpeed")
                        tap("direction:Right")
                        tap("block-done")
                    },
                    {
                        target("Coin") +
                            mapOf("rule" to 1L, "block" to Picked("SetSpeed", mapOf("direction" to "Right", "speed" to 20.0)))
                    },
                ),
        )

    @Test
    fun theGameBlocksGiveTheSameResultByHandAndByAgentTool() {
        for ((block, case) in blockCases) checkParity("add_block", block, case)
    }

    @Test
    fun theGameComponentsGiveTheSameResultByHandAndByAgentTool() {
        checkParity(
            "set_property",
            "Keep across scenes",
            Case(
                { select("Score") },
                { tap("field:${it.id("Score")}/gameplay:number/keep") },
                { target("Score") + mapOf("component" to "gameplay:number", "property" to "keep", "value" to true) },
            ),
        )
        checkParity(
            "add_component",
            "Template",
            Case(
                { select("Coin") },
                { tap("add-component:gameplay:template") },
                { target("Coin") + ("component" to "gameplay:template") },
            ),
        )
        checkParity(
            "set_property",
            "Steady movement",
            Case(
                {
                    select("Player")
                    apply(EditOperation.AddComponent(sceneId, id("Player"), "gameplay:motion"))
                },
                { tap("inc:${it.id("Player")}/gameplay:motion/vx") },
                { target("Player") + mapOf("component" to "gameplay:motion", "property" to "vx", "value" to 0.5) },
            ),
        )
    }

    @Test
    fun choosingASpritesImageGivesTheSameResultByHandAndByAgentTool() {
        val picture =
            Case(
                {
                    importImage("Logo", logo)
                    apply(EditOperation.AddObject(sceneId, "Picture", components = listOf(ComponentDoc("graphics:sprite"))))
                    select("Picture")
                },
                { tap("image:${it.id("Picture")}/graphics:sprite/asset:Logo") },
                {
                    target("Picture") +
                        mapOf("component" to "graphics:sprite", "property" to "asset", "value" to project.images.single().id)
                },
            )
        checkParity("set_property", "Sprite image", picture)
        checkParity(
            "set_property",
            "Pixelated",
            Case(
                {
                    apply(EditOperation.AddObject(sceneId, "Picture", components = listOf(ComponentDoc("graphics:sprite"))))
                    select("Picture")
                },
                { tap("field:${it.id("Picture")}/graphics:sprite/pixelated") },
                { target("Picture") + mapOf("component" to "graphics:sprite", "property" to "pixelated", "value" to true) },
            ),
        )
    }

    @Test
    fun thePhysicsComponentsGiveTheSameResultByHandAndByAgentTool() {
        checkParity(
            "add_component",
            "Body",
            Case(
                { select("Player") },
                { tap("add-component:gameplay:body") },
                { target("Player") + ("component" to "gameplay:body") },
            ),
        )
        checkParity(
            "set_property",
            "Bounce",
            Case(
                {
                    select("Player")
                    apply(EditOperation.AddComponent(sceneId, id("Player"), "gameplay:body"))
                },
                { tap("inc:${it.id("Player")}/gameplay:body/bounce") },
                { target("Player") + mapOf("component" to "gameplay:body", "property" to "bounce", "value" to 0.5) },
            ),
        )
        checkParity(
            "set_property",
            "Solid",
            Case(
                {
                    select("Coin")
                    apply(EditOperation.AddComponent(sceneId, id("Coin"), "gameplay:solid"))
                },
                { tap("field:${it.id("Coin")}/gameplay:solid/solid") },
                { target("Coin") + mapOf("component" to "gameplay:solid", "property" to "solid", "value" to false) },
            ),
        )
        checkParity(
            "set_property",
            "Follow up and down",
            Case(
                {
                    select("Score")
                    apply(EditOperation.AddComponent(sceneId, id("Score"), "gameplay:follow"))
                },
                { tap("field:${it.id("Score")}/gameplay:follow/down") },
                { target("Score") + mapOf("component" to "gameplay:follow", "property" to "down", "value" to false) },
            ),
        )
    }

    private fun checkParity(
        id: String,
        label: String,
        case: Case,
    ) {
        val byHand =
            run {
                val session = session(case)
                val state = session.state
                val before = state.project
                var result: Outcome? = null
                runDesktopComposeUiTest(width = 1440, height = 900) {
                    setContent {
                        CompositionLocalProvider(LocalImagePicker provides ImagePicker { it(Result.success(PickedImage("Logo", logo))) }) {
                            MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state, projectSession = session) }
                        }
                    }
                    waitForIdle()
                    case.ui(this, state)
                    waitForIdle()
                    result = outcome(state, before, "$label by hand")
                }
                checkNotNull(result)
            }
        val byAgent =
            run {
                val state = session(case).state
                val before = state.project
                val spec = checkNotNull(state.engine.operations[id])
                val input = encodeArguments(spec.parameters, case.tool(state))
                val applied = state.apply(state.engine.operations.buildFromJson(id, input, state.editor.catalogContext()))
                assertIs<OperationResult.Applied>(applied, "$label by agent: $applied")
                outcome(state, before, "$label by agent")
            }
        assertEquals(byHand.project, byAgent.project, "$label: same workspace")
        assertEquals(byHand.ruleProblems, byAgent.ruleProblems, "$label: same validation")
        assertEquals(byHand.capabilityProblems, byAgent.capabilityProblems, "$label: same capability check")
        assertEquals(byHand.undoLabel, byAgent.undoLabel, "$label: same undo step")
        assertEquals(byHand.afterUndo, byAgent.afterUndo, "$label: same project after undo")
        check(byHand.project != byHand.afterUndo) { "$label: the case must change the game" }
    }
}
