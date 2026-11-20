package dev.spawnkit.editing

import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.runtime.RuntimeBuilder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GamePackageTest {
    private val project =
        Project(
            name = "Coin run",
            scenes =
                listOf(
                    SceneDoc(
                        "scene-1",
                        "Main scene",
                        listOf(
                            ObjectDoc(
                                "object-2",
                                "Hero",
                                null,
                                listOf(ComponentDoc("graphics:transform", mapOf("x" to 2.5)), ComponentDoc("graphics:shape")),
                            ),
                            ObjectDoc("object-3", "Jetpack", "object-2", listOf(ComponentDoc("extension:jetpack"))),
                        ),
                    ),
                    SceneDoc("scene-4", "Bonus"),
                ),
            nextId = 5,
        )

    @Test
    fun roundTripKeepsTheProjectTitleStartSceneAndRequiredCapabilities() {
        val pkg = GamePackage.of(project, title = "Coin Run Deluxe", startScene = "scene-4")
        assertEquals(listOf("extension:jetpack", "graphics:shape", "graphics:transform"), pkg.requiredComponents)

        val decoded = GamePackageCodec.decode(GamePackageCodec.encode(pkg))

        assertEquals(pkg, decoded)
        assertEquals(project, decoded.project)
        assertEquals("scene-4", decoded.startScene)
    }

    @Test
    fun defaultsToTheProjectNameAndFirstScene() {
        val pkg = GamePackage.of(project)
        assertEquals("Coin run", pkg.title)
        assertEquals("scene-1", pkg.startScene)
    }

    @Test
    fun invalidPackagesAreRejectedBeforeEncoding() {
        assertFailsWith<IllegalArgumentException> { GamePackage.of(project, title = " ") }
        assertFailsWith<IllegalArgumentException> { GamePackage.of(project, title = "x".repeat(GamePackage.MAX_TITLE_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { GamePackage.of(project, startScene = "scene-missing") }
        assertFailsWith<IllegalArgumentException> { GamePackage.of(Project("Empty", emptyList())) }
    }

    @Test
    fun foreignFutureTamperedOrOversizedPackagesFailWithExplanations() {
        val text = GamePackageCodec.encode(GamePackage.of(project))

        val project = assertFailsWith<IllegalArgumentException> { GamePackageCodec.decode(ProjectCodec.encode(this.project)) }
        assertContains(project.message.orEmpty(), "SpawnKit game package")

        val future =
            assertFailsWith<IllegalArgumentException> {
                GamePackageCodec.decode(
                    text.replace("\"packageVersion\":1", "\"packageVersion\":2"),
                )
            }
        assertContains(future.message.orEmpty(), "newer SpawnKit player")

        val tampered = text.replace("\"requiredComponents\":[\"extension:jetpack\",", "\"requiredComponents\":[")
        val mismatch = assertFailsWith<IllegalArgumentException> { GamePackageCodec.decode(tampered) }
        assertContains(mismatch.message.orEmpty(), "damaged")

        val broken = assertFailsWith<IllegalArgumentException> { GamePackageCodec.decode("{\"format\":") }
        assertEquals("This game cannot be played: the package is incomplete or damaged", broken.message)

        assertFailsWith<IllegalArgumentException> {
            GamePackageCodec.decode(
                text.replace("\"startScene\":\"scene-1\"", "\"startScene\":\"nope\""),
            )
        }
        assertFailsWith<IllegalArgumentException> { GamePackageCodec.decode("{") }
        assertFailsWith<IllegalArgumentException> { GamePackageCodec.decode(" ".repeat(GamePackageCodec.MAX_PACKAGE_CHARS + 1)) }
    }

    @Test
    fun missingCapabilitiesAreExplainedAgainstThePlayersRuntime() {
        val builder = RuntimeBuilder()
        Graphics2D(builder)
        val runtime = builder.build()

        val pkg = GamePackage.of(project)

        assertEquals(listOf("extension:jetpack"), pkg.missingComponents(runtime))
        assertTrue(
            GamePackage
                .of(
                    project.copy(
                        scenes =
                            project.scenes.take(1).map {
                                it.copy(objects = it.objects.take(1))
                            },
                    ),
                ).missingComponents(runtime)
                .isEmpty(),
        )
    }
}
