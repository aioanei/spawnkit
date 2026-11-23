package dev.spawnkit.editing

import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.runtime.RuntimeBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Test ad settings stored in the project (C12a). */
class AdSettingsTest {
    private val builder = RuntimeBuilder().also { Graphics2D(it) }
    private val editor = Editor(builder.build())

    @Test
    fun adsAreOffUntilTheCreatorTurnsThemOn() {
        assertEquals(AdSettings(banner = false, interstitial = false, every = 3), editor.project.ads)
    }

    @Test
    fun settingAdsIsOneUndoStepThroughTheCatalog() {
        val built =
            EditingOperations.catalog.build(
                "set_ads",
                mapOf("banner" to true, "interstitial" to true, "every" to 4L),
                editor.catalogContext(),
            )
        assertIs<OperationResult.Applied>(editor.apply(assertIs<BuildResult.Ready>(built).operation))

        assertEquals(AdSettings(banner = true, interstitial = true, every = 4), editor.project.ads)
        assertEquals("Change test ads", editor.undoLabel)
        editor.undo()
        assertEquals(AdSettings(), editor.project.ads)
    }

    @Test
    fun theGapBetweenInterstitialsIsLimited() {
        val result = editor.apply(EditOperation.SetAds(AdSettings(interstitial = true, every = 0)))
        assertTrue(assertIs<OperationResult.Rejected>(result).message.contains("1 to 20"))
    }

    @Test
    fun adSettingsSurviveTheFileAndOlderFilesOpenWithAdsOff() {
        editor.apply(EditOperation.SetAds(AdSettings(banner = true, every = 5)))
        val text = ProjectCodec.encode(editor.project)
        assertTrue(text.contains("\"schemaVersion\":4"))
        assertEquals(editor.project, ProjectCodec.decode(text))

        val schemaTwo =
            text
                .replace("\"schemaVersion\":4", "\"schemaVersion\":2")
                .replace(Regex(""","ads":\{[^}]*\}"""), "")
        assertEquals(AdSettings(), ProjectCodec.decode(schemaTwo).ads)
        val schemaOne = schemaTwo.replace("\"schemaVersion\":2", "\"schemaVersion\":1").replace(",\"images\":[]", "")
        assertEquals(editor.project.copy(ads = AdSettings()), ProjectCodec.decode(schemaOne))
    }
}
