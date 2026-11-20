package dev.spawnkit.app.persistence

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.spawnkit.editing.DraftStore
import dev.spawnkit.editing.ProjectCodec
import java.io.FileOutputStream
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Desktop project file. Replacement uses a synced sibling temporary file and an atomic rename. */
class DesktopProjectFile(
    private val path: Path,
) : ProjectFile {
    override val name: String get() = path.fileName.toString()

    override fun read(): String = Files.newBufferedReader(path).use(::boundedText)

    override fun write(text: String) {
        atomicWrite(path, text)
    }
}

/** A private desktop recovery directory; keys are fixed by the journal and cannot contain paths. */
class DesktopDraftStore(
    private val directory: Path,
) : DraftStore {
    private fun path(key: String): Path {
        require(key in setOf("draft", "previous", "damaged"))
        return directory.resolve("$key.json")
    }

    override fun read(key: String): String? =
        path(key).let { if (Files.exists(it)) Files.newBufferedReader(it).use(::boundedText) else null }

    override fun write(
        key: String,
        text: String,
    ) {
        atomicWrite(path(key), text)
    }
}

@Composable
actual fun rememberProjectFiles(): ProjectFiles =
    remember {
        object : ProjectFiles {
            override val drafts: DraftStore = DesktopDraftStore(Path.of(System.getProperty("user.home"), ".spawnkit", "recovery"))
            override val picker: ProjectFilePicker =
                object : ProjectFilePicker {
                    private fun chooser() =
                        JFileChooser().apply {
                            fileFilter = FileNameExtensionFilter("SpawnKit projects", "spawnkit", "json")
                        }

                    override fun open(result: (Result<ProjectFile?>) -> Unit) {
                        result(
                            runCatching {
                                val dialog = chooser()
                                if (dialog.showOpenDialog(null) ==
                                    JFileChooser.APPROVE_OPTION
                                ) {
                                    DesktopProjectFile(dialog.selectedFile.toPath())
                                } else {
                                    null
                                }
                            },
                        )
                    }

                    override fun create(
                        suggestedName: String,
                        result: (Result<ProjectFile?>) -> Unit,
                    ) {
                        result(
                            runCatching {
                                val dialog = chooser().apply { selectedFile = java.io.File(suggestedName) }
                                if (dialog.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    val path = dialog.selectedFile.toPath()
                                    if (Files.exists(path) &&
                                        javax.swing.JOptionPane.showConfirmDialog(
                                            null,
                                            "Replace ${path.fileName}?",
                                            "Save project",
                                            javax.swing.JOptionPane.YES_NO_OPTION,
                                        ) !=
                                        javax.swing.JOptionPane.YES_OPTION
                                    ) {
                                        null
                                    } else {
                                        DesktopProjectFile(path)
                                    }
                                } else {
                                    null
                                }
                            },
                        )
                    }
                }
        }
    }

private fun atomicWrite(
    path: Path,
    text: String,
) {
    val absolute = path.toAbsolutePath()
    Files.createDirectories(absolute.parent)
    val temporary = Files.createTempFile(absolute.parent, ".spawnkit-", ".tmp")
    try {
        FileOutputStream(temporary.toFile()).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temporary, absolute, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun boundedText(reader: Reader): String {
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        require(result.length + count <= ProjectCodec.MAX_FILE_CHARS) { "This project file is too large" }
        result.append(buffer, 0, count)
    }
    return result.toString()
}
