# Project files and recovery — C5

[Issue #56](https://github.com/aioanei/spawnkit/issues/56) delivers the persistence slice of R5. The workspace and
runtime remain executable APIs independent of storage. `core/editing` owns the project-file boundary; the legacy
platformer codec in `core/model` remains a separate format.

## In the builder

Open **Project** in the toolbar on either a phone or desktop:

- **New game** starts a blank workspace.
- **Open project file…** imports a `.spawnkit` file through the system picker.
- **Save** writes to the selected file, or asks for a location the first time.
- **Save a copy…** chooses another location and writes a portable project file.
- **Rename game…** changes the game name as one undoable editor operation.

New and Open ask before replacing unsaved changes. Save and continue, Discard changes, and Cancel are explicit.
Cancelling a picker, opening a bad file, and a failed write keep the current project and its undo history. Opening a
valid file starts a new undo history. Play uses an isolated world and never writes play state into the project.

Changes, including undo/redo, autosave after 500 ms of idle time. Lifecycle stop and disposal also flush the current
workspace. At startup, **Restore draft** resumes local work; **Start new** is explicit. If the newest draft is damaged,
the previous valid draft is offered and the damaged contents are retained separately. Autosave failures say to save a
project file. Abrupt termination inside the debounce interval may lose edits that have not yet flushed.

Android uses the Storage Access Framework; desktop uses a Swing file chooser. Locations and permission handles stay
in the host session, outside project files. Restoring a draft asks for a file location on the next Save. No cloud
account, model key or credentials are involved.

## File format

`ProjectCodec` reads and writes schema version **1**, identified by `format: "spawnkit-project"`. JSON is the initial
storage codec, not the engine contract. A portable file contains the game name and ID counter, scenes/objects in
authoring order, stable IDs and parents, component IDs and authored values, and rule handlers with source labels and
node IDs. Every current handler expression/statement kind is represented.

Whole numbers and decimals have separate tags, preserving signed 64-bit integers and doubles, including negative
zero. Booleans, strings and opaque JSON property data also round-trip. Missing components need not be installed to
save their values. Host function/property/event/effect names remain open strings. Incomplete advanced rules are kept
and checked by Play; the codec does not replace program type checking.

Runtime entity handles cannot be portable literals: saving them fails with a suggestion to use this object, tags or
queries. Entity-valued expressions and typed lists remain supported. Workspace rules are handlers; per-object
function/state authoring remains planned rather than being invented by the format.

Files are limited to 4,194,304 UTF-16 characters and 128 nested JSON containers. Platform readers enforce the character
limit while reading. Object ancestry is bounded to 128 levels before recursive editor presentation. Unsupported
versions, fields, instructions and invalid structures fail before replacing the
editor. Newer data is never silently ignored. Future breaking schema changes require an explicit migration; the
initial version has no predecessor. Legacy `GameProject` files are not silently reinterpreted.

`Editor.saveSnapshot()` keeps the counter above IDs retired by undo. Reopening a snapshot cannot reuse them. Undo
history and transient selection/camera/play state are not part of the portable file.

## Missing capabilities

An opened project keeps unavailable components and unsupported property values. Its preview omits unavailable
components and uses defaults for unsupported values; this projection never changes the project. The inspector names
unavailable components. Play is blocked with an object-specific explanation until the matching capability is installed
or the affected setting is corrected. `Project.capabilityProblems(runtime)` supplies the same check to headless callers.

## Recovery boundary

`DraftStore` supplies atomic per-key storage. `ProjectJournal` uses three keys:

| Key | Contents |
|---|---|
| `draft` | Newest complete project snapshot |
| `previous` | Previous valid draft, written before replacing `draft` |
| `damaged` | Last unreadable draft, archived before replacement |

Encoding finishes before storage changes. A backup failure prevents replacement of the current draft; a failed atomic
replacement retains its old contents. Recovery inspection is read-only. Starting a new/restored session archives
unreadable current data before replacing it.

| Platform | Local recovery | Project files |
|---|---|---|
| Desktop | `~/.spawnkit/recovery/`, synced temporary file and atomic rename | Swing chooser, atomic replacement |
| Android | App-private `files/recovery/`, Android `AtomicFile` | Document picker/provider; an internal draft precedes external writes |
| Browser JS/Wasm | Origin-local `localStorage`, subject to browser quota | File import/export remains planned |
| iOS | App-local preferences adapter | Document picker remains planned; compilation checked in CI |

Desktop filesystems must support atomic rename; failures are reported. Android providers control external writes, so
a failed provider may leave a partial external file; the internal autosave remains available. Local recovery is not
an off-device backup. Quota and storage errors preserve the open workspace. iOS sudden-termination durability has not
been device-validated.

## Evidence

`ProjectPersistenceTest` covers all portable handler node shapes, typed values, missing capabilities, malformed/future
files, runtime-handle rejection, stable IDs after undo/restart, write failure, fallback and damaged-data retention.

`ProjectUiTest` uses actual temporary desktop files and recovery storage. Test pickers choose files deterministically.
Phone and desktop layouts save, reopen and play a score rule; cancellation, bad files, write failures, missing
capabilities, restart recovery, and undo/redo autosaves are exercised. This does not claim automated desktop OS-dialog
interaction.

Per-resource directories, assets, dependency locks, legacy import, cloud sync and extension migrations remain in R5.
The full novice usability gate and physical-device checks remain separate acceptance evidence.
