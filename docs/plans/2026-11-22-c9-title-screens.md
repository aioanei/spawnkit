# C9 Title Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every game can open on a title screen that the creator makes in the builder from ordinary scenes, objects
and rules. Players tap Play and reach the first level.

**Architecture:**
- Scenes get catalog operations: add, rename, remove, and move. Moving a scene to position 1 makes it the start
  scene, because `Project.scenes` already treats the first scene as the entry.
- A "Go to scene" action block compiles to a program **effect request** (`goToScene`, with the scene ID as text).
- After each tick, `PlaySession` reads and acknowledges these requests. It then switches to a new world built from
  the target scene.
- An "Add title screen" helper applies one `Batch` of ordinary operations.

**Tech stack:** Kotlin Multiplatform, Compose Multiplatform; `core/editing`, `core/gameplay`, `core/program`
effects, `player/playerKit`, `app/shared`.

**Spec:** [design §4](../design/2026-11-22-title-screens-presets-accounts-ads.md#4-c9--game-title-screens),
[issue #74](https://github.com/aioanei/spawnkit/issues/74), [ADR-0009](../adr/0009-agentic-parity.md).

## Global Constraints

- Every builder control has a catalog entry, a derived agent tool and an `OperationParityTest` case (ADR-0009).
- There is no title-screen type in the engine. The helper uses ordinary operations, and the creator edits the result
  with the normal inspector and rules.
- Compact and expanded layouts, with touch targets ≥ 48 dp. `core/*` uses explicit API and KDoc, and is deterministic.
- Numbers do not carry across scenes (stated limitation).

## Rulings taken while planning

- The start scene is the first scene (the existing model). "Set as start scene" is `move_scene` to position 1, so no
  codec change or migration is needed. *Cost if wrong:* the project order doubles as the start choice.
- `DuplicateScene` is cut (design cut line, item 5). Reorder stays, because the start scene depends on it.
- A "Go to scene" whose target was removed is shown as a rule problem in the builder. If such a request happens
  during play, it is ignored and a play message is shown. Removing a scene is not blocked.

## Review Focus

1. **Removing the scene that is being edited, or the start scene.** The builder switches to the first remaining
   scene. Removing the last scene is refused. Tested in Task 1 and Task 4.
2. **Tapping Play twice in one tick, or two rules requesting different scenes.** The last request wins, and the
   switch happens once, at the end of the tick. Tested in Task 2.
3. **Undo after "Add title screen".** One undo removes the scene, its objects, its rules and the reorder. Tested in
   Task 5.
4. **Exported games and the web player start at the first scene.** Tested in Task 3.
5. **A rule that goes to the scene it is already in, used to restart.** The world is rebuilt fresh. Tested in Task 3.

---

### Task 1: Scene operations and catalog entries (`core/editing`)

**Files:** `core/editing/src/dev/spawnkit/editing/Operations.kt`, `OperationCatalog.kt`; test
`core/editing/test/dev/spawnkit/editing/SceneOperationsTest.kt`.

**Produces:**
- `EditOperation.RemoveScene(sceneId: String, label = "Delete scene")`. Removing the last scene is rejected with
  `A game needs at least one scene`.
- `EditOperation.MoveScene(sceneId: String, toIndex: Int, label = "Move scene")`. `toIndex` is 0-based and must be
  in range.
- Catalog entries:
  - `add_scene(name)`;
  - `rename_scene(scene, name)`;
  - `remove_scene(scene)`;
  - `move_scene(scene, to)`: `to` is a whole number from 1 to 100, 1 = start scene. The label is
    `Make <name> the start scene` when `to == 1`.

- [ ] Write the failing tests:
  - `scenesAreAddedRenamedMovedAndRemovedWithUndo`;
  - `theLastSceneCannotBeRemovedAndMovesStayInRange`;
  - `catalogBuildsSceneOperationsFromJson`.
- [ ] Run: `./kotlin.bat test -m editing -p jvm`. Expected: FAIL.
- [ ] Implement. Run the tests: PASS.
- [ ] Commit `feat(editing): scene operations and catalog entries (C9)`.

### Task 2: "Go to scene" block and the scene-request effect (`core/gameplay`)

**Files:** `Gameplay.kt` (register `effect("goToScene", TextType)`), `RuleBlocks.kt`, `RuleBlockCatalog.kt`,
`StandardEngine.kt`; test `core/gameplay/test/dev/spawnkit/gameplay/GoToSceneTest.kt`.

**Produces:**
- `Action.GoToScene(sceneId: String)`. It compiles to `Stmt.RequestEffect(GameplayRules.GO_TO_SCENE, text literal)`
  and decompiles back.
- A catalog block `GoToScene` with parameter `scene: ParameterType.Scene`. Its label is `Go to scene <name>`.
- `StandardEngine.takeSceneRequest(world: World): String?`: returns the last pending `goToScene` scene ID and
  acknowledges the delivered requests.

- [ ] Write the failing tests:
  - `goToSceneRoundTripsThroughProgramsAndTheCatalog`;
  - `aTapRequestsTheSceneOnceAndTheLastRequestWins`: a world with a tapped Play button, then a tap, a step, and
    `takeSceneRequest` returns the level ID, then `null`.
- [ ] Run: `./kotlin.bat test -m gameplay -p jvm` (FAIL), implement, run (PASS).
- [ ] Commit `feat(gameplay): Go to scene block over a scene-request effect (C9)`.

### Task 3: Scene switching in players (`player/playerKit`, builder Play)

**Files:**
- `PlaySession.kt`:
  - add the constructor parameters `sceneRequest: (World) -> String? = { null }` and
    `buildScene: (String) -> SceneWorld? = { null }`;
  - make `scene` an observable `var`;
  - add `fun afterTick()`, which swaps the world, or sets `message` to
    `A rule asked for a scene that no longer exists.`.
- `PlaySurface.kt`: call `afterTick()` after each step.
- `GamePlayer.kt` and `EditorState.startPlay`: pass both lambdas. Builder Play starts at the scene being edited.
  Exports start at the package's start scene.
- `ReleaseManager`: the start scene defaults to the project's first scene.
- Test: `player/playerKit/test@jvm/.../PlaySessionScenesTest.kt`:
  - `tappingPlayOnTheTitleReachesLevelOne`;
  - `goingToTheSameSceneRestartsIt`;
  - `aMissingSceneKeepsPlayingWithAMessage`.
- [ ] Write the tests, see them fail, implement, see them pass (`./kotlin.bat test -m playerKit -p jvm`), then
  commit `feat(player): switch scenes on Go to scene (C9)`.

### Task 4: Scene list in the builder, with parity

**Files:**
- `EditorScene.kt` (new, `app/shared/.../editor/`): `SceneList(state)` with:
  - scene rows (`scene:<name>`, tap to edit that scene, with a "Start" badge on the first);
  - Add scene (`scene-add`);
  - Rename (`scene-rename`, a dialog with field `scene-name` and `scene-name-done`);
  - Move up (`scene-up`) and Move down (`scene-down`);
  - Make start scene (`scene-start`);
  - Delete (`scene-delete`, undoable, no dialog).

  It sits above the object hierarchy in the expanded layout's "Scene" section and in the compact "Scene" tab.
- `EditorState`: `selectScene(id)`. After a removal, the scene being edited falls back to the first scene.
- `EditorState.ruleProblems`: a GoToScene with a missing target gives
  `Go to scene: that scene was removed. Choose another scene.`.
- Tests:
  - `OperationParityTest`: cases for `add_scene`, `rename_scene`, `remove_scene` and `move_scene`.
  - `SceneListUiTest`, phone and desktop: `phoneAddsRenamesAndSwitchesScenes` and
    `desktopMakesAStartSceneAndDeletesWithUndo`.
- [ ] Run (FAIL), implement, run `./kotlin.bat test -m shared -p jvm` (PASS), then commit
  `feat(editor): scene list with start scene (C9)`.

### Task 5: "Add title screen" helper

**Files:**
- `EditorState.addTitleScreen()` applies one `EditOperation.Batch`:
  1. `AddScene("Title")`.
  2. Text `"My game"` at (0, -2), size 1.5.
  3. A background rectangle 16×9 behind everything.
  4. A Play button: a rectangle 4×1.5 tagged `play-button`, with a child text `"Play"`.
  5. The rule "When tapped → Go to scene <first scene before the change>".
  6. `MoveScene(title, 0)`.

  Batch IDs follow `Project.nextId` (`scene-N`, `obj-N`).
- The button is `title-add` in the scene list. It is a builder helper, and its steps are the cataloged operations.
- Tests:
  - `TitleScreenTest` (jvm): one undo removes everything; playing from the start shows the title; tapping the button
    reaches the level.
  - A UI test `titleScreenIsAddedAndPlayable`.
- [ ] Run (FAIL), implement, run (PASS), then commit `feat(editor): Add title screen helper (C9)`.

### Task 6: Evidence, docs and gates

- [ ] Android emulator: extend a smoke script (`tools/android-title-smoke.py`). It adds a title screen, plays, taps
  Play and screenshots the level. Copy the screenshots to `docs/media/title-*.png`.
- [ ] Docs:
  - `docs/editing.md` (scene operations);
  - `docs/gameplay.md` (Go to scene);
  - `docs/operation-catalog.md`;
  - `PROGRESS.md`, `AGENTS.md`.
- [ ] Gates:
  - Gradle `spotlessApply`, then `spotlessCheck allJvmAndWebTests localNativeTests`;
  - Toolchain `check ktlint`, `test -p jvm -p android`, `-p mingwX64`.
- [ ] Commit, push, and open a PR (`Closes #74`) stacked on #79.
