package dev.spawnkit.app.presets

import dev.spawnkit.editing.ProjectCodec
import dev.spawnkit.presets.EmbeddedPresets
import dev.spawnkit.presets.GamePresets
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The preset files in `presets/` are exactly what their recipes build with the builder's calls (C10). Run with
 * `SPAWNKIT_WRITE_PRESETS=1` to rewrite the files from the recipes instead of checking them.
 */
class PresetRecipesTest {
    @Test
    fun checkedInPresetsMatchTheirRecipes() {
        assertEquals(GamePresets.all.map { it.id }, PresetRecipes.all.keys.toList(), "one recipe per preset, in picker order")
        val write = System.getenv("SPAWNKIT_WRITE_PRESETS") == "1"
        PresetRecipes.all.forEach { (id, recipe) ->
            val project = recipe()
            if (write) {
                val file = repositoryRoot().resolve("presets/$id/project.json")
                file.parentFile.mkdirs()
                file.writeText(ProjectCodec.encode(project))
            } else {
                val embedded = EmbeddedPresets.all[id] ?: fail("presets/$id/project.json is missing. $REWRITE")
                assertEquals(project, ProjectCodec.decode(embedded), "presets/$id/project.json differs from its recipe. $REWRITE")
            }
        }
    }

    private fun repositoryRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "project.yaml").isFile && File(it, "presets").isDirectory }
            ?: error("Run from inside the SpawnKit repository")

    private companion object {
        const val REWRITE = "Rewrite the presets with SPAWNKIT_WRITE_PRESETS=1 (see presets/README.md)."
    }
}
