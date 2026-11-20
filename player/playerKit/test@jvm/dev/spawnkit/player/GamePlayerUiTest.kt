package dev.spawnkit.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.GamePackage
import dev.spawnkit.editing.GamePackageCodec
import dev.spawnkit.editing.ObjectDoc
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.SceneDoc
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.program.Expr
import dev.spawnkit.program.Handler
import dev.spawnkit.program.SourceRef
import dev.spawnkit.program.Stmt
import dev.spawnkit.program.Trigger
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GamePlayerUiTest {
    private val demo =
        Project(
            "Hello player",
            listOf(
                SceneDoc(
                    "scene-1",
                    "Main scene",
                    listOf(
                        ObjectDoc(
                            "object-2",
                            "Box",
                            components =
                                listOf(
                                    ComponentDoc("graphics:transform", mapOf("x" to -1.5, "y" to 0.0)),
                                    ComponentDoc("graphics:shape", mapOf("width" to 2.0, "height" to 2.0, "color" to 0xFF4CAF50)),
                                ),
                        ),
                        ObjectDoc(
                            "object-3",
                            "Title",
                            components =
                                listOf(
                                    ComponentDoc("graphics:transform", mapOf("x" to 0.0, "y" to -3.0)),
                                    ComponentDoc("graphics:text", mapOf("text" to "Made with SpawnKit")),
                                ),
                        ),
                    ),
                ),
            ),
            nextId = 4,
        )

    @Test
    fun aPackagedGamePlaysWithTouchControls() =
        runComposeUiTest {
            val pkg = GamePackage.of(demo)
            // Written for the web player smoke check (see docs/release.md); build/ is ignored.
            File("build/sample-game").apply { mkdirs() }.resolve("game.spawnkit.json").writeText(GamePackageCodec.encode(pkg))

            // The fixed-step play loop never idles; drive the clock like the builder play tests do.
            mainClock.autoAdvance = false
            setContent { GamePlayer(pkg, touchControls = true) }
            mainClock.advanceTimeBy(100)

            onNodeWithTag("game-player").assertIsDisplayed()
            onNodeWithTag("control:${StandardActions.JUMP}").assertIsDisplayed()
            assertEquals(pkg, GamePackageCodec.decode(File("build/sample-game/game.spawnkit.json").readText()))
        }

    @Test
    fun rulesThatCannotRunAreExplainedWhilePlaying() =
        runComposeUiTest {
            val source = SourceRef("broken-rule", "A rule using an unknown value")
            val broken = Handler(Trigger.EveryTick, listOf(Stmt.Evaluate(Expr.Variable("missing", source), source)), source)
            val scene = demo.scenes.single()
            val withBrokenRule =
                demo.copy(scenes = listOf(scene.copy(objects = scene.objects.map { if (it.name == "Box") it.copy(rules = listOf(broken)) else it })))
            mainClock.autoAdvance = false
            setContent { GamePlayer(GamePackage.of(withBrokenRule), touchControls = false) }
            mainClock.advanceTimeBy(100)

            onNodeWithTag("player-problem").assertIsDisplayed()
            onNodeWithText("Box has rules that can't run, so it plays without them").assertIsDisplayed()
        }

    @Test
    fun gamesNeedingMissingCapabilitiesAreExplainedInsteadOfRun() =
        runComposeUiTest {
            val needsExtension =
                demo.copy(
                    scenes =
                        listOf(
                            SceneDoc(
                                "scene-1",
                                "Main scene",
                                listOf(ObjectDoc("object-2", "Pet", components = listOf(ComponentDoc("pets:dog")))),
                            ),
                        ),
                )

            setContent { GamePlayer(GamePackage.of(needsExtension), touchControls = false) }

            onNodeWithText("Hello player can't run in this player").assertIsDisplayed()
            onNodeWithText("Pet needs 'pets:dog', which is not installed", substring = true).assertIsDisplayed()
        }
}
