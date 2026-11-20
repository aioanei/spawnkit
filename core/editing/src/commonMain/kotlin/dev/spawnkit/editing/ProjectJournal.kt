package dev.spawnkit.editing

/** Local recovery storage. A successful [write] is durable; a failed write must leave the old value intact. */
public interface DraftStore {
    /** Reads [key], or `null` when it has never been written. Storage failures may throw. */
    public fun read(key: String): String?

    /** Atomically replaces [key], or throws without replacing its previous value. */
    public fun write(
        key: String,
        text: String,
    )
}

/** The startup recovery candidate, never loaded or discarded without the creator choosing. */
public sealed interface RecoveryResult {
    /** No draft exists. */
    public data object Empty : RecoveryResult

    /** A valid draft. [fromPrevious] explains fallback after damage to the newest draft. */
    public data class Found(
        /** Project the creator can restore. */
        public val project: Project,
        /** Whether this is the previous valid draft rather than the newest one. */
        public val fromPrevious: Boolean,
    ) : RecoveryResult

    /** Existing data could not be read; it stays in storage for later recovery/export. */
    public data class Unreadable(
        /** Plain-language explanation. */
        public val message: String,
    ) : RecoveryResult
}

/**
 * Two-generation recovery journal. Encoding and backup finish before replacing the newest draft. Unreadable current
 * data is archived before it is replaced. Loading is read-only, and saving never writes invalid/nonportable projects.
 */
public class ProjectJournal(
    private val store: DraftStore,
) {
    /** Inspects recovery without changing storage. Read failures are returned as [RecoveryResult.Unreadable]. */
    public fun load(): RecoveryResult {
        val current = runCatching { store.read(CURRENT) }
        val project = current.getOrNull()?.let { runCatching { ProjectCodec.decode(it) }.getOrNull() }
        if (project != null) return RecoveryResult.Found(project, false)
        val previous = runCatching { store.read(PREVIOUS) }
        val backup = previous.getOrNull()?.let { runCatching { ProjectCodec.decode(it) }.getOrNull() }
        return when {
            backup != null -> {
                RecoveryResult.Found(backup, true)
            }

            current.isSuccess && previous.isSuccess && current.getOrNull() == null && previous.getOrNull() == null -> {
                RecoveryResult.Empty
            }

            current.isFailure || previous.isFailure -> {
                RecoveryResult.Unreadable("Recovery storage could not be read. Open a saved project file or try again")
            }

            else -> {
                RecoveryResult.Unreadable("The recovery draft is unreadable. It has been kept; you can open a saved project file")
            }
        }
    }

    /** Saves the current workspace. Failures propagate so the UI can explain them and offer file export. */
    public fun save(project: Project) {
        val text = ProjectCodec.encode(project)
        val current = store.read(CURRENT)
        if (current == text) return
        if (current != null) {
            val valid = runCatching { ProjectCodec.decode(current) }.isSuccess
            store.write(if (valid) PREVIOUS else DAMAGED, current)
        }
        store.write(CURRENT, text)
    }

    /** Stable storage keys; adapters store these separately and atomically. */
    public companion object {
        /** Newest complete draft. */
        public const val CURRENT: String = "draft"

        /** Previous valid draft. */
        public const val PREVIOUS: String = "previous"

        /** Last unreadable draft, retained before replacement. */
        public const val DAMAGED: String = "damaged"
    }
}
