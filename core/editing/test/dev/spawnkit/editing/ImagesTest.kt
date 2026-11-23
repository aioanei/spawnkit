package dev.spawnkit.editing

import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.runtime.RuntimeBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Images inside projects (ADR-0012, C15b): checks, operations, the catalog and the workspace file. */
class ImagesTest {
    private val builder = RuntimeBuilder()
    private val graphics = Graphics2D(builder)
    private val runtime = builder.build()
    private val editor = Editor(runtime)
    private val scene =
        editor.project.scenes
            .first()
            .id
    private val logo = ProjectImages.encode(TestPng.make(4, 3))

    private fun import(
        name: String = "Logo",
        data: String = logo,
    ): String = assertIs<OperationResult.Applied>(editor.apply(EditOperation.AddImage(name, data))).createdIds.single()

    private fun rejection(operation: EditOperation): String = assertIs<OperationResult.Rejected>(editor.apply(operation)).message

    private fun sprite(asset: String): OperationResult =
        editor.apply(
            EditOperation.AddObject(scene, "Picture", components = listOf(ComponentDoc("graphics:sprite", mapOf("asset" to asset)))),
        )

    @Test
    fun sha256MatchesTheStandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        val long = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", Sha256.hex(long))
    }

    @Test
    fun pngFilesAreCheckedAndMeasured() {
        val info = ProjectImages.check(TestPng.make(4, 3))
        assertEquals(4, info.width)
        assertEquals(3, info.height)

        val notPng =
            assertFailsWith<IllegalArgumentException> { ProjectImages.check("GIF89a and some more bytes here....".encodeToByteArray()) }
        assertTrue(notPng.message!!.contains("Only PNG"))
        val huge =
            TestPng.make(1, 1).copyOf().also {
                it[16] = 0
                it[17] = 0
                it[18] = 0x10
                it[19] = 0
            }
        assertTrue(assertFailsWith<IllegalArgumentException> { ProjectImages.check(huge) }.message!!.contains("2048"))
        val big = ByteArray(ProjectImages.MAX_IMAGE_BYTES + 1)
        assertTrue(assertFailsWith<IllegalArgumentException> { ProjectImages.check(big) }.message!!.contains("512 KB"))
        assertTrue(assertFailsWith<IllegalArgumentException> { ProjectImages.check("not base64!") }.message!!.contains("damaged"))
    }

    @Test
    fun importedImagesGetIdsAndUndoRemovesThem() {
        val id = import()
        val image = editor.project.image(id)!!
        assertEquals("Logo", image.name)
        assertEquals(ProjectImages.check(logo).hash, image.hash)
        assertEquals("Import image Logo", editor.undoLabel)

        editor.undo()
        assertNull(editor.project.image(id))
        val again = import()
        assertTrue(again != id, "IDs are never reused after an undo")
    }

    @Test
    fun theSamePictureCannotBeImportedTwice() {
        import()
        assertEquals("This picture is already in the game as Logo", rejection(EditOperation.AddImage("Copy", logo)))
        assertTrue(rejection(EditOperation.AddImage("Broken", "AAAA")).contains("PNG"))
    }

    @Test
    fun spritesMayOnlyNameImagesThatExist() {
        assertTrue((sprite("img-99") as OperationResult.Rejected).message.contains("there is no image 'img-99'"))
        val id = import()
        assertIs<OperationResult.Applied>(sprite(id))
        assertIs<OperationResult.Applied>(sprite(""))
    }

    @Test
    fun anImageInUseCannotBeRemoved() {
        val id = import()
        assertIs<OperationResult.Applied>(editor.apply(EditOperation.RenameImage(id, "Title logo")))
        assertEquals("Title logo", editor.project.image(id)!!.name)
        sprite(id)

        assertEquals("Title logo is used by Picture; change those first", rejection(EditOperation.RemoveImage(id)))
        editor.undo()
        assertIs<OperationResult.Applied>(editor.apply(EditOperation.RemoveImage(id)))
        assertEquals(emptyList(), editor.project.images)
    }

    @Test
    fun imageOperationsAreInTheCatalog() {
        val catalog = EditingOperations.catalog
        val added =
            assertIs<BuildResult.Ready>(catalog.build("add_image", mapOf("name" to "Hero", "data" to logo), editor.catalogContext()))
        val id = assertIs<OperationResult.Applied>(editor.apply(added.operation)).createdIds.single()
        val renamed = catalog.build("rename_image", mapOf("image" to id, "name" to "Knight"), editor.catalogContext())
        assertIs<OperationResult.Applied>(editor.apply(assertIs<BuildResult.Ready>(renamed).operation))
        assertEquals("Rename image Hero", editor.undoLabel)
        assertEquals(
            BuildResult.Rejected("There is no image 'img-404'"),
            catalog.build("remove_image", mapOf("image" to "img-404"), editor.catalogContext()),
        )
    }

    @Test
    fun imagesSurviveTheWorkspaceFile() {
        val id = import()
        sprite(id)
        val project = editor.project
        val text = ProjectCodec.encode(project)

        assertTrue(text.contains("\"schemaVersion\":2"))
        assertEquals(project, ProjectCodec.decode(text))
    }

    @Test
    fun schemaOneFilesOpenWithNoImages() {
        val current = ProjectCodec.encode(editor.project)
        val schemaOne = current.replace("\"schemaVersion\":2", "\"schemaVersion\":1").replace(",\"images\":[]", "")
        assertTrue(!schemaOne.contains("images"), "the old file has no images list")

        val opened = ProjectCodec.decode(schemaOne)
        assertEquals(editor.project, opened)
        assertEquals(emptyList(), opened.images)
    }

    @Test
    fun damagedImagesFailToOpen() {
        val id = import()
        val text = ProjectCodec.encode(editor.project)
        val hash = editor.project.image(id)!!.hash

        val wrongHash = text.replace(hash, "0".repeat(64))
        assertTrue(assertFailsWith<IllegalArgumentException> { ProjectCodec.decode(wrongHash) }.message!!.contains("Logo is damaged"))
        val other = ProjectImages.encode(TestPng.make(2, 2))
        assertFailsWith<IllegalArgumentException> { ProjectCodec.decode(text.replace(logo, other)) }
    }
}
