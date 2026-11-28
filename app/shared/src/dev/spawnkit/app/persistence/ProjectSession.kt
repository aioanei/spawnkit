package dev.spawnkit.app.persistence

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.ProjectCodec
import dev.spawnkit.editing.ProjectJournal
import dev.spawnkit.editing.RecoveryResult
import dev.spawnkit.presets.GamePreset

/** The pending destructive navigation, confirmed before the current workspace is replaced. */
enum class ProjectAction { New, Open }

/**
 * What the assistant builds once a new blank game is open: the prompt-first New game (C13b). [prompt] is the creator's
 * description; with [replay], the assistant plays the labelled whole-game replay instead of calling a model.
 */
class BuildRequest(
    val prompt: String,
    val replay: Boolean = false,
)

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

    /** The New game picker is open (C10). */
    var choosing by mutableStateOf(false)
        private set

    /** What [ProjectAction.New] starts from: a preset, or `null` for a blank game. */
    private var newFrom: GamePreset? = null

    /** What to build on the new blank game, once it is open. */
    private var newRequest: BuildRequest? = null

    /** A build request for the game just started with [describe]; the builder takes it with [takeBuildRequest]. */
    var buildRequest by mutableStateOf<BuildRequest?>(null)
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

    /** Opens the New game picker. */
    fun chooseNew() {
        if (busy || state.play != null || recovery != null) return
        choosing = true
    }

    /** Closes the picker without changing anything. */
    fun closeChooser() {
        choosing = false
    }

    /**
     * Starts a new game from [preset], or a blank one when `null`. Unsaved changes are asked about first. A preset loads
     * as a fresh copy with no file, so Save asks where to keep it. Choosing a preset is a project-file action like New and
     * Open, outside the editing catalog (C10).
     */
    fun startNew(preset: GamePreset?) {
        choosing = false
        newFrom = preset
        newRequest = null
        request(ProjectAction.New)
    }

    /**
     * Prompt-first New game (C13b): starts a blank game like [startNew], asking about unsaved changes first, then hands
     * [request] to the assistant through [buildRequest]. Cancelling leaves the current game and builds nothing.
     */
    fun describe(request: BuildRequest) {
        if (request.prompt.isBlank()) return
        choosing = false
        newFrom = null
        newRequest = request
        request(ProjectAction.New)
    }

    /** Takes the pending [buildRequest], so it is handled once. */
    fun takeBuildRequest(): BuildRequest? = buildRequest.also { buildRequest = null }

    fun request(action: ProjectAction) {
        if (busy || state.play != null || recovery != null) return
        if (dirty) pending = action else perform(action)
    }

    fun cancelNavigation() {
        pending = null
        newFrom = null
        newRequest = null
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
                val preset = newFrom
                newFrom = null
                val project =
                    try {
                        preset?.project() ?: Project.blank()
                    } catch (_: Exception) {
                        state.message = "${preset?.name} could not be opened; your game is kept"
                        return
                    }
                state.loadProject(project)
                saved = state.project
                target = null
                autosaved = null
                autosave()
                if (preset != null) status = "Started from ${preset.name} · save a file to keep your own copy"
                buildRequest = newRequest.takeIf { preset == null }
                newRequest = null
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
