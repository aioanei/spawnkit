package dev.spawnkit.app.persistence

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.ProjectCodec
import dev.spawnkit.editing.ProjectJournal
import dev.spawnkit.editing.RecoveryResult

/** The pending destructive navigation, confirmed before the current workspace is replaced. */
enum class ProjectAction { New, Open }

/**
 * Coordinates files, the editor and local recovery. File decoding finishes before any editor change. Cancellation,
 * codec failures and failed writes leave the workspace and its undo history intact.
 */
class ProjectSession(
    val files: ProjectFiles,
    val state: EditorState = EditorState(),
) {
    private val journal = ProjectJournal(files.drafts)
    private var saved: Project? = state.project
    private var target: ProjectFile? = null
    private var autosaved: Project? = state.project

    var recovery by mutableStateOf<RecoveryResult?>(journal.load().takeUnless { it == RecoveryResult.Empty })
        private set
    var pending by mutableStateOf<ProjectAction?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("Changes are autosaved on this device")
        private set
    val dirty: Boolean get() = state.project != saved

    /** Called after a short idle period, on lifecycle stop and on disposal. Recovery is never silently overwritten. */
    fun autosave() {
        if (recovery != null || state.project == autosaved) return
        try {
            journal.save(state.editor.saveSnapshot())
            autosaved = state.project
            status = if (dirty) "Autosaved on this device · save a file to keep a portable copy" else "Autosaved on this device"
        } catch (_: Exception) {
            status = "Autosave failed · save a project file to keep your work"
        }
    }

    fun restoreRecovery() {
        val found = recovery as? RecoveryResult.Found ?: return
        state.loadProject(found.project)
        saved = null
        autosaved = if (found.fromPrevious) null else found.project
        recovery = null
        status =
            if (found.fromPrevious) {
                "Restored the previous valid draft; the damaged draft is kept"
            } else {
                "Restored your draft · save a file to keep a portable copy"
            }
        autosave()
    }

    /** Explicitly starts afresh; the journal backs up existing data before the first new autosave. */
    fun startWithoutRecovery() {
        recovery = null
        state.loadProject(Project.blank())
        saved = state.project
        target = null
        autosaved = null
        autosave()
    }

    fun request(action: ProjectAction) {
        if (busy || state.play != null || recovery != null) return
        if (dirty) pending = action else perform(action)
    }

    fun cancelNavigation() {
        pending = null
    }

    fun discardAndContinue() {
        val action = pending ?: return
        pending = null
        perform(action)
    }

    fun saveAndContinue() {
        val action = pending ?: return
        save {
            if (!dirty) {
                pending = null
                perform(action)
            }
        }
    }

    fun save(
        copy: Boolean = false,
        after: () -> Unit = {},
    ) {
        if (busy || state.play != null || recovery != null) return
        val snapshot = state.project
        val text =
            try {
                ProjectCodec.encode(state.editor.saveSnapshot())
            } catch (problem: Exception) {
                state.message = problem.message ?: "This project cannot be saved yet"
                return
            }
        // Keep a recoverable copy even if an external provider fails midway through writing its file.
        autosave()

        fun write(file: ProjectFile) {
            try {
                file.write(text)
                target = file
                saved = snapshot
                state.message = null
                status = "Saved ${file.name}"
                after()
            } catch (_: Exception) {
                state.message = "The project file could not be saved. Your work is still open; choose Save a copy or another location"
            }
        }
        val existing = target
        if (existing != null && !copy) {
            write(existing)
        } else {
            val picker = files.picker ?: return
            busy = true
            picker.create(suggestedName(snapshot.name)) { result ->
                busy = false
                result.fold(
                    onSuccess = { file -> if (file != null) write(file) },
                    onFailure = { state.message = "The file picker could not be opened. Your work is still here; try again" },
                )
            }
        }
    }

    private fun perform(action: ProjectAction) {
        when (action) {
            ProjectAction.New -> {
                state.loadProject(Project.blank())
                saved = state.project
                target = null
                autosaved = null
                autosave()
            }

            ProjectAction.Open -> {
                val picker = files.picker ?: return
                busy = true
                picker.open { result ->
                    busy = false
                    result.fold(
                        onSuccess = { file -> if (file != null) open(file) },
                        onFailure = { state.message = "The file picker could not be opened. Your current game is still here" },
                    )
                }
            }
        }
    }

    private fun open(file: ProjectFile) {
        val project =
            try {
                ProjectCodec.decode(file.read())
            } catch (problem: Exception) {
                val detail = if (problem is IllegalArgumentException) problem.message.orEmpty() else "Check the file and try again"
                state.message = "The file could not be opened; your game is kept. $detail"
                return
            }
        state.loadProject(project)
        saved = project
        target = file
        autosaved = null
        autosave()
        status = "Opened ${file.name}"
    }

    private fun suggestedName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + ".spawnkit"
}
