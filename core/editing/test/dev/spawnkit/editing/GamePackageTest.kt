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
    fun runtimeCompatibilityIncludesFirstGenerationPackages() {
        val pkg = GamePackage.of(project)
        val json = GamePackageCodec.encode(pkg)
        assertContains(json, "\"runtimeVersion\":1")
        assertEquals(pkg, GamePackageCodec.decode(json.replace("\"runtimeVersion\":1,", "")))
        val problem =
            assertFailsWith<IllegalArgumentException> {
                GamePackageCodec.decode(json.replace("\"runtimeVersion\":1", "\"runtimeVersion\":2"))
            }
        assertContains(problem.message.orEmpty(), "runtime")
        assertFailsWith<IllegalArgumentException> { GamePackageCodec.decode("[".repeat(220) + "]".repeat(220)) }
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
    fun wronglyTypedFieldsNeverLeakLibraryDetailsOrCreatorAdvice() {
        val text = GamePackageCodec.encode(GamePackage.of(project))
        val generic = "This game cannot be played: the package is incomplete or damaged"
        val components = "\"requiredComponents\":[\"extension:jetpack\",\"graphics:shape\",\"graphics:transform\"]"
        assertContains(text, components)
        val cases =
            listOf(
                components to "\"requiredComponents\":{}",
                components to "\"requiredComponents\":[{},{},{}]",
                "\"title\":\"Coin run\"" to "\"title\":null",
                "\"title\":\"Coin run\"" to "\"title\":42",
                "\"startScene\":\"scene-1\"" to "\"startScene\":\"scene-9\"",
                "\"packageVersion\":1" to "\"packageVersion\":\"1\"",
            )
        for ((from, to) in cases) {
            val tampered = text.replace(from, to)
            assertTrue(tampered != text, to)
            val message = assertFailsWith<IllegalArgumentException> { GamePackageCodec.decode(tampered) }.message.orEmpty()
            assertTrue(message.startsWith("This game cannot be played: "), message)
            assertTrue("kotlinx" !in message && "Json" !in message && "choose another" !in message, message)
            assertTrue(message == generic || "damaged" in message || "newer" in message, message)
        }
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
