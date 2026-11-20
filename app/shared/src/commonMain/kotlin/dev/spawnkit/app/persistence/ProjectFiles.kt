package dev.spawnkit.app.persistence

import androidx.compose.runtime.Composable
import dev.spawnkit.editing.DraftStore

/** A user-selected project file. Locations and permissions stay in the host, outside exported project data. */
interface ProjectFile {
    val name: String

    fun read(): String

    fun write(text: String)
}

/** Native pickers return null on cancellation and never read or overwrite a file just because it was selected. */
interface ProjectFilePicker {
    fun open(result: (Result<ProjectFile?>) -> Unit)

    fun create(
        suggestedName: String,
        result: (Result<ProjectFile?>) -> Unit,
    )
}

/** Host services: a private atomic draft store, plus optional system file pickers. */
interface ProjectFiles {
    val drafts: DraftStore
    val picker: ProjectFilePicker?
}

/** Remembers the current platform's real local storage and native pickers. */
@Composable
expect fun rememberProjectFiles(): ProjectFiles
