package dev.spawnkit.app.persistence

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.spawnkit.editing.DraftStore
import dev.spawnkit.editing.ProjectCodec
import java.io.File
import java.io.Reader

private class AndroidProjectFile(
    private val context: Context,
    private val uri: Uri,
) : ProjectFile {
    override val name: String get() =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "Project"

    override fun read(): String = requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use(::boundedText)

    override fun write(text: String) {
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(text) }
    }
}

private class AndroidDraftStore(
    private val context: Context,
) : DraftStore {
    private fun file(key: String): AtomicFile {
        require(key in setOf("draft", "previous", "damaged"))
        return AtomicFile(File(context.filesDir, "recovery/$key.json"))
    }

    override fun read(key: String): String? {
        val file = file(key)
        return try {
            file.openRead().bufferedReader().use(::boundedText)
        } catch (missing: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw missing else null
        }
    }

    override fun write(
        key: String,
        text: String,
    ) {
        val file = file(key)
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (problem: Exception) {
            file.failWrite(output)
            throw problem
        }
    }
}

@Composable
actual fun rememberProjectFiles(): ProjectFiles {
    val context = LocalContext.current.applicationContext
    var opening by remember { mutableStateOf<((Result<ProjectFile?>) -> Unit)?>(null) }
    var creating by remember { mutableStateOf<((Result<ProjectFile?>) -> Unit)?>(null) }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = opening
            opening = null
            callback?.invoke(Result.success(uri?.let { AndroidProjectFile(context, it) }))
        }
    val create =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val callback = creating
            creating = null
            callback?.invoke(Result.success(uri?.let { AndroidProjectFile(context, it) }))
        }
    return remember(context, open, create) {
        object : ProjectFiles {
            override val drafts: DraftStore = AndroidDraftStore(context)
            override val picker: ProjectFilePicker =
                object : ProjectFilePicker {
                    override fun open(result: (Result<ProjectFile?>) -> Unit) {
                        opening = result
                        try {
                            open.launch(arrayOf("*/*"))
                        } catch (problem: Exception) {
                            opening = null
                            result(Result.failure(problem))
                        }
                    }

                    override fun create(
                        suggestedName: String,
                        result: (Result<ProjectFile?>) -> Unit,
                    ) {
                        creating = result
                        try {
                            create.launch(suggestedName)
                        } catch (problem: Exception) {
                            creating = null
                            result(Result.failure(problem))
                        }
                    }
                }
        }
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
