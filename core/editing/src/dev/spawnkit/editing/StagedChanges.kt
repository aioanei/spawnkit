package dev.spawnkit.editing

/**
 * Proposed changes kept apart from the creator's workspace until the creator decides (ADR-0005, ADR-0009). Agents and
 * other tools apply ordinary operations here; each is validated exactly as in [Editor], but the creator's project,
 * history and revision stay untouched. [accept] commits every staged step as one undoable step, and only when the
 * workspace is still at the revision the changes were staged against. [discard] drops them.
 *
 * Not thread-safe; use it from one thread.
 */
public class StagedChanges(
    private val target: Editor,
) {
    /** Revision of [target] that these changes were staged against. */
    public val baseRevision: Long = target.revision

    /** The creator's project when staging started. */
    public val base: Project = target.project

    // The snapshot carries IDs retired by undo, so staged IDs match the IDs the target will mint on accept.
    private val scratch = Editor(target.runtime, target.saveSnapshot(), historyLimit = MAX_STAGED_STEPS, programs = target.programs)
    private val steps = mutableListOf<EditOperation>()

    /** Whether the changes were accepted or discarded; no further operations are possible afterwards. */
    public var closed: Boolean = false
        private set

    /** The project with every staged step applied. */
    public val project: Project get() = scratch.project

    /** Staged steps, in order. */
    public val operations: List<EditOperation> get() = steps.toList()

    /** Labels of the staged steps, for the review screen. */
    public val labels: List<String> get() = steps.map { it.label }

    /** Validates and stages [operation]. A rejection stages nothing. */
    public fun apply(operation: EditOperation): OperationResult {
        check(!closed) { "These changes were already accepted or discarded" }
        if (steps.size >= MAX_STAGED_STEPS) return OperationResult.Rejected("One proposal can have at most $MAX_STAGED_STEPS steps")
        val before = scratch.revision
        val result = scratch.apply(operation)
        if (result is OperationResult.Applied && scratch.revision != before) steps += operation
        return result
    }

    /** Stages a catalog-built operation; a [BuildResult.Rejected] stages nothing. */
    public fun apply(result: BuildResult): OperationResult =
        when (result) {
            is BuildResult.Ready -> apply(result.operation)
            is BuildResult.Rejected -> OperationResult.Rejected(result.message)
        }

    /** Catalog context against the staged project, so agents refer to objects they created earlier. */
    public fun catalogContext(): CatalogContext = CatalogContext(project, target.runtime)

    /** Removes the last staged step. Returns `false` when nothing is staged. */
    public fun undoLast(): Boolean {
        check(!closed) { "These changes were already accepted or discarded" }
        if (steps.isEmpty()) return false
        scratch.undo()
        steps.removeAt(steps.lastIndex)
        return true
    }

    /** A fresh world of scene [sceneId] with the staged changes, for previews and headless test runs. */
    public fun buildWorld(sceneId: String): SceneWorld = scratch.buildWorld(sceneId)

    /**
     * Commits every staged step to the creator's workspace as one undoable step labelled [label]. Refused, with no
     * change, when the workspace changed since staging started or when the result would differ from the preview.
     */
    public fun accept(label: String): AcceptResult {
        check(!closed) { "These changes were already accepted or discarded" }
        if (steps.isEmpty()) return AcceptResult.Refused("There is nothing to accept")
        if (target.revision != baseRevision) {
            return AcceptResult.Refused(
                "The game changed while these changes were prepared. Ask again to prepare them for the current game.",
            )
        }
        val before = target.project
        when (val result = target.apply(EditOperation.Batch(steps.toList(), label))) {
            is OperationResult.Rejected -> {
                return AcceptResult.Refused(result.message)
            }

            is OperationResult.Applied -> {
                if (target.project.copy(nextId = 0) != project.copy(nextId = 0)) {
                    target.undo()
                    check(target.project == before) { "Undoing an inconsistent accept must restore the workspace" }
                    return AcceptResult.Refused("These changes no longer match their preview, so nothing was changed")
                }
                closed = true
                return AcceptResult.Accepted(result.createdIds)
            }
        }
    }

    /** Drops every staged step. The creator's workspace is unchanged. */
    public fun discard() {
        closed = true
    }
}

/** Outcome of [StagedChanges.accept]. */
public sealed interface AcceptResult {
    /** Committed as one undoable step; [createdIds] lists objects and scenes created. */
    public data class Accepted(
        /** Created IDs in creation order. */
        public val createdIds: List<String>,
    ) : AcceptResult

    /** Nothing changed, with a plain-language reason. */
    public data class Refused(
        /** Why. */
        public val message: String,
    ) : AcceptResult
}

/** Most steps one proposal may stage. */
public const val MAX_STAGED_STEPS: Int = 500
