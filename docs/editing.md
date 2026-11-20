# Editing core — C2 (R4 subset)

Tracked by [issue #46](https://github.com/aioanei/spawnkit/issues/46). This is item C2 of the
[contest delivery track](plans/2026-11-20-engine-foundation-revision.md#contest-delivery-track). The builder UI on
Android and desktop, Koog agents and tests all use this one module (`core/editing`).

## Workspace

A `Project` holds `SceneDoc`s. Each scene holds `ObjectDoc`s with a stable ID, a name, an optional parent and
`ComponentDoc`s (authored property values keyed by descriptor property ID; missing values use the descriptor
default). The model is immutable and meaningful without ever being saved; persistence encodes it at a boundary (C5).
`Project.blank()` is a functional empty project with one scene. Authoring order is sibling order.

## Operations

All changes go through typed `EditOperation`s, validated against the installed descriptors:

- objects: `AddObject` (with components and values, optional position), `RemoveObject` (subtree), `RenameObject`,
  `ReparentObject` (cycle check) and `DuplicateObject` (subtree with fresh IDs, placed after the original);
- components: `AddComponent` (defaults) and `RemoveComponent`;
- properties: `SetProperty`;
- scenes: `AddScene` and `RenameScene`;
- `Batch`: atomic, with one undo step and a label.

A rejection returns a plain-language message and changes nothing. IDs are minted above every ID the project or the
editor session has used, so neither undo nor an imported project with a stale counter can cause an ID to be reused.
`Project.problems()` reports duplicate IDs, missing parents and cycles; the editor refuses to open such a project.
Re-applying an identical value creates no undo step. Values are coerced by the value kind; for example,
an integer typed into a decimal field is accepted. Object-reference properties are not authorable yet.

## Editor

`Editor(runtime, project)`:

- `apply`, `undo` and `redo` with bounded snapshot history and labels;
- a `revision` counter and change listeners;
- `load` to replace the project;
- `validate` to check a single value as a property field types, without applying it;
- `buildWorld(sceneId)` to build a fresh world through an R2f scene template.

`SceneWorld` maps authoring object IDs to entities and back (`ObjectIdentity`). The editor viewport hit-tests in the
built world and selects the authored object. Play uses the same build, then steps the world; later edits never
affect a world that was already built.

## Evidence

`EditorTest` (7 tests) runs on JVM, Android host, JS, Wasm and mingwX64. It covers:

- undo/redo restoring exact states, and redo clearing;
- rejections (bad values, unknown property or component, duplicates, cycles, blank names, missing objects or
  scenes) leaving the project and revision unchanged;
- batch atomicity and labels;
- duplicate ID freshness and subtree removal;
- world building, hierarchy and identity mapping, and selection via hit test;
- play isolation;
- scene operations and listeners.

## Not yet

- Rules and behaviors on objects (C4).
- Persistence (C5).
- Object-reference authoring.
- Prefab editing.
- Copy/paste between projects.
- Grouping of continuous drags into one undo step happens in the UI (C3), by applying one `SetProperty` at drag end.
