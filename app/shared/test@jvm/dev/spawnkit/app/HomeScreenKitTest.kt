package dev.spawnkit.app

import dev.spawnkit.app.editor.DefaultPresets
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.app.editor.TitleLayout
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.player.PlaySession
import dev.spawnkit.player.playSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The home screen kit (C15): Button and Text pieces, grouping and title screen layouts. */
class HomeScreenKitTest {
    private fun EditorState.piece(name: String) = add(DefaultPresets.first { it.name == name })

    private fun EditorState.named(name: String) = scene.objects.first { it.name == name }

    private fun EditorState.sceneNamed(name: String) = project.scenes.first { it.name == name }.id

    private fun PlaySession.tapAt(
        x: Double,
        y: Double,
    ) {
        pointerDown(1, x, y)
        pointerUp(1)
        scene.world.submitInput(nextInput())
        scene.world.step()
        afterTick()
        // One more moment, so the next tap is a new press.
        scene.world.submitInput(nextInput())
        scene.world.step()
        afterTick()
    }

    @Test
    fun aButtonIsAGroupWithALabelThatGoesToTheNextScene() {
        val state = EditorState()
        state.apply(EditOperation.AddScene("Level 2"))
        val level2 = state.sceneNamed("Level 2")
        state.selectScene(
            state.project.scenes
                .first()
                .id,
        )

        state.piece("Button")

        val button = state.named("Button")
        assertEquals(button.id, state.selection)
        assertEquals(button.id, state.named("Button label").parent)
        assertEquals(
            listOf(RuleItem.Blocks(Rule(Condition.Tapped, actions = listOf(Action.GoToScene(level2))))),
            state.ruleItems(button.id),
        )
        assertEquals("Add Button", state.editor.undoLabel)
        state.undo()
        assertEquals(emptyList(), state.scene.objects, "one undo removes the button and its label")
    }

    @Test
    fun aButtonInAOneSceneGameHasNoRuleYet() {
        val state = EditorState()
        state.piece("Button")

        assertEquals(emptyList(), state.ruleItems(state.named("Button").id))
    }

    @Test
    fun piecesCanBeAddedAgainAfterAnUndo() {
        val state = EditorState()
        state.piece("Button")
        state.undo()
        state.piece("Button")
        state.piece("Text")

        assertEquals(listOf("Button", "Button label", "Text"), state.scene.objects.map { it.name })
        assertEquals(state.named("Button").id, state.named("Button label").parent)
    }

    @Test
    fun aTextCanBeMovedIntoAButtonAndOut() {
        val state = EditorState()
        state.piece("Button")
        state.piece("Text")
        val text = state.named("Text").id

        assertIs<OperationResult.Applied>(state.moveInto(text, state.named("Button").id))
        assertEquals(state.named("Button").id, state.named("Text").parent)
        assertIs<OperationResult.Applied>(state.moveInto(text, null))
        assertNull(state.named("Text").parent)
    }

    @Test
    fun theFullLayoutLinksTitleLevelsShopAndHowItWorks() {
        val state = EditorState()
        state.apply(EditOperation.AddScene("Level 2"))
        state.addTitleScreen(TitleLayout.Full)

        assertEquals(listOf("Title", "Main scene", "Level 2", "Levels", "Shop", "How it works"), state.project.scenes.map { it.name })
        assertEquals("Title", state.scene.name)
        assertEquals(
            listOf(
                "Background",
                "Title text",
                "Play button",
                "Play label",
                "Levels button",
                "Levels label",
                "Shop button",
                "Shop label",
                "How it works button",
                "How it works label",
            ),
            state.scene.objects.map { it.name },
        )
        val title = state.sceneId
        val session = state.engine.playSession(state.project, title)

        session.tapAt(0.0, 1.3)
        assertEquals(state.sceneNamed("Levels"), session.sceneId, "Levels opens the level list")
        session.tapAt(0.0, -0.5)
        assertEquals(state.sceneNamed("Level 2"), session.sceneId, "the second level button opens Level 2")

        val again = state.engine.playSession(state.project, state.sceneNamed("Levels"))
        again.tapAt(0.0, 3.4)
        assertEquals(title, again.sceneId, "Back returns to the title")
        again.tapAt(0.0, 2.6)
        assertEquals(state.sceneNamed("Shop"), again.sceneId)
        again.tapAt(0.0, 3.4)
        again.tapAt(0.0, 3.9)
        assertEquals(state.sceneNamed("How it works"), again.sceneId)
        again.tapAt(0.0, 3.4)
        again.tapAt(0.0, -0.2)
        assertEquals(state.project.scenes[1].id, again.sceneId, "Play starts the game")
        assertNull(again.message)

        state.undo()
        assertEquals(listOf("Main scene", "Level 2"), state.project.scenes.map { it.name }, "one undo removes every new scene")
    }

    @Test
    fun levelButtonsFitAPhoneHeldUpright() {
        for (levels in listOf(1, 6, 9)) {
            val state = EditorState()
            repeat(levels - 1) { state.apply(EditOperation.AddScene("Level ${it + 2}")) }
            state.addTitleScreen(TitleLayout.WithLevels)
            state.selectScene(state.sceneNamed("Levels"))
            val buttons = state.scene.objects.filter { it.parent == null && it.name.endsWith(" button") && it.name != "Back button" }

            assertEquals(levels, buttons.size)
            for (button in buttons) {
                val x = button.component("graphics:transform")!!.values["x"] as Double
                val width = button.component("graphics:shape")!!.values["width"] as Double
                // About 8.6 units of the scene are visible across a phone held upright.
                assertTrue(x - width / 2 >= -4.3 && x + width / 2 <= 4.3, "$levels levels: ${button.name} at $x is cut off")
            }
        }
    }

    @Test
    fun theLevelsLayoutHasNoShop() {
        val state = EditorState()
        state.addTitleScreen(TitleLayout.WithLevels)

        assertEquals(listOf("Title", "Main scene", "Levels"), state.project.scenes.map { it.name })
    }
}
