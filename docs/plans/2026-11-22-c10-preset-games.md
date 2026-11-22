# C10 Preset Games Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Project → New game opens a picker with Blank plus three playable preset games (Coin Dash, Tap Targets, Maze
Run), each a normal project file with a title screen, embedded in the app.

**Architecture:**
- The `embedded-templates` generator gains `directory` and `objectName` settings, in the Toolchain plugin and in the
  Gradle task. A new KMP module `core/presets` embeds `presets/<id>/project.json` as `EmbeddedPresets` and lists them
  in `GamePresets`.
- The presets are authored with the builder's own `EditorState` calls (object presets, catalog operations,
  `RuleOperations`, `addTitleScreen`) in a JVM recipe test that writes the files on request and otherwise checks
  them.
- `ProjectSession` gets a picker step before New. Loading a preset is a project-file action like New and Open.

**Tech Stack:** Kotlin Multiplatform, Kotlin Toolchain + Gradle, Compose Multiplatform, kotlin.test.

**Spec:** [design §5](../design/2026-11-22-title-screens-presets-accounts-ads.md#5-c10--preset-games), issue #75.

## Global Constraints

- Presets use only today's blocks plus "Go to scene": Move, Change/Set number, Set text, Show number, Remove self,
  Go to scene; When blocks Always, Game starts, Every N seconds, Touching, Tapped, Key held/pressed, Number is,
  Count is.
- Each preset has a C9 title screen as its start scene.
- Starting from a preset is outside the editing catalog (like New and Open); no agent tool and no parity case.
- Choosing a preset goes through the unsaved-changes dialog. The loaded copy has no file yet, so Save asks where to
  save; autosave keeps it.
- Both builds stay green: Gradle `spotlessCheck allJvmAndWebTests localNativeTests`, Toolchain `check ktlint`,
  `test -p jvm -p android`, `test -p mingwX64`.
- No new third-party dependencies.

## Review Focus

1. Unsaved changes, then picking a preset, then Cancel: the current game stays, with its undo history.
2. Save right after loading a preset must ask for a new file, never overwrite the file opened before.
3. Undo right after loading a preset does nothing (the previous game is not one undo away).
4. The picker at phone width (360 dp): every choice reachable by scrolling; the previews do not overflow.
5. A preset whose file does not decode (future format bug) shows a message and keeps the current game.

---

### Task 1: Configurable generator and the `core/presets` module

**Files:**
- Modify: `plugins/embedded-templates/src/{Settings,GenerateEmbeddedTemplates,EmbeddedTemplatesRenderer}.kt`,
  `plugins/embedded-templates/plugin.yaml`, `plugins/embedded-templates/test/EmbeddedTemplatesRendererTest.kt`
- Modify: `build-logic/convention/src/main/kotlin/{GenerateEmbeddedTemplatesTask,SpawnkitTemplatesPlugin}.kt`
- Create: `core/presets/{module.yaml,build.gradle.kts}`, `presets/README.md`
- Modify: `project.yaml`, `settings.gradle.kts`, `AGENTS.md` (module table)

**Interfaces:**
- Produces: `renderEmbeddedTemplates(packageName, templates, objectName = "EmbeddedTemplates")`; plugin settings
  `directory` (default `templates`) and `objectName`; Gradle extension `spawnkitTemplates { directory; packageName;
  objectName }`; generated `dev.spawnkit.presets.EmbeddedPresets.all: Map<String, String>`.

- [ ] **Step 1: Failing renderer test** `rendersTheConfiguredObjectName`: rendering with `objectName = "EmbeddedPresets"`
  contains `public object EmbeddedPresets {`.
- [ ] **Step 2: Run** `./kotlin.bat test -m embedded-templates`. Expected: compile failure (no parameter).
- [ ] **Step 3: Implement** the parameter, the `directory`/`objectName` settings (`templatesDir:
  ${project.rootDir}/${pluginSettings.directory}`, file named `<objectName>.kt`) and the same in the Gradle task and
  plugin extension, with the old values as defaults.
- [ ] **Step 4: Run** the plugin tests, then build `core/presets` in both builds. Expected: PASS, and
  `EmbeddedPresets.kt` generated with an empty map.
- [ ] **Step 5: Commit** `build: embed preset projects in core/presets (C10)`.

### Task 2: Three playable presets

**Files:**
- Create: `core/presets/src/dev/spawnkit/presets/GamePresets.kt`, `core/presets/test/dev/spawnkit/presets/PresetGamesTest.kt`
- Create: `app/shared/test@jvm/dev/spawnkit/app/presets/PresetRecipes.kt`, `.../PresetRecipesTest.kt`
- Create: `presets/{coin-dash,tap-targets,maze-run}/project.json` (written by the recipe test)
- Modify: `app/shared/module.yaml`, `app/shared/build.gradle.kts` (depend on `core/presets`)

**Interfaces:**
- Produces: `class GamePreset(id: String, name: String, pitch: String) { fun project(): Project }`;
  `object GamePresets { val all: List<GamePreset> }` in order Coin Dash, Tap Targets, Maze Run.
- Scenes: Coin Dash = Title, Level, You win. Tap Targets = Title, Play, You win, Time's up. Maze Run = Title, Maze,
  You escaped. Tags: `player`, `coin`, `score`, `target`, `timer`, `wall`, `exit`, `play-button`, edge walls
  `edge-left|right|top|bottom`.

- [ ] **Step 1: Failing play tests** in `PresetGamesTest` (all targets), driving worlds with
  `buildSceneWorld` + `StandardEngine.takeSceneRequest`:
  - `catalogMatchesTheEmbeddedFiles` (ids equal, names equal the project names);
  - `everyPresetHasNoProblems` (no capability problems, no diagnostics in any scene, every Go to scene target exists);
  - `tappingPlayOnEachTitleStartsTheGame` (tap the `play-button` centre → the second scene);
  - `coinDashBotCollectsEveryCoinAndWins`, `tapTargetsBotTapsEveryTargetAndWins`,
    `tapTargetsTimeRunsOutWithoutTaps` (→ "Time's up" after the countdown), `mazeRunBotEscapes`,
    `mazeWallsRestartTheMaze` (holding Up from the start restarts the maze scene).
- [ ] **Step 2: Run** `./kotlin.bat test -m presets -p jvm`. Expected: FAIL (no presets embedded).
- [ ] **Step 3: Write the recipes** with `EditorState` and `PresetRecipesTest.checkedInPresetsMatchTheirRecipes`: with
  `SPAWNKIT_WRITE_PRESETS=1` it writes `ProjectCodec.encode(editor.saveSnapshot())` to `presets/<id>/project.json`,
  otherwise it asserts the embedded project equals the recipe's.
- [ ] **Step 4: Write the files** (`SPAWNKIT_WRITE_PRESETS=1`), then run the presets tests on JVM and mingwX64 and
  the recipe test. Expected: PASS.
- [ ] **Step 5: Commit** `feat(presets): Coin Dash, Tap Targets and Maze Run (C10)`.

### Task 3: New game picker

**Files:**
- Create: `app/shared/src/dev/spawnkit/app/persistence/NewGamePicker.kt`
- Modify: `ProjectSession.kt`, `ProjectControls.kt`, `app/shared/test@jvm/.../ProjectSessionTest.kt` (or the
  existing session test), new `NewGamePickerUiTest.kt`

**Interfaces:**
- Produces: `ProjectSession.choosing: Boolean`, `chooseNew()`, `closeChooser()`, `startNew(preset: GamePreset?)`;
  tags `new-game-picker`, `new-blank`, `new-preset-<id>`, `new-cancel`.

- [ ] **Step 1: Failing tests:**
  - session: `newFromAPresetLoadsACopyWithoutAFile`, `newFromAPresetAsksAboutUnsavedChangesAndCancelKeepsTheGame`,
    `undoAfterLoadingAPresetDoesNothing`, `anUndecodablePresetKeepsTheGame` (via an injected preset);
  - UI: `pickerOffersBlankAndThreePresets` at 1280×800 and 360×740, and choosing Coin Dash opens its title scene.
- [ ] **Step 2: Run** the app tests. Expected: FAIL.
- [ ] **Step 3: Implement** the picker: a dialog listing Blank and the presets, each with a preview drawn from the
  preset's level scene with the existing render list, the name and the pitch; "New game" in the menu opens it.
- [ ] **Step 4: Run** the app tests and the Gradle gates. Expected: PASS.
- [ ] **Step 5: Evidence and docs.** Android emulator screenshots (picker, a preset playing); `docs/editing.md`,
  design note on the agent exemption, `PROGRESS.md`, `AGENTS.md`. Commit `feat(app): New game picker with presets (C10)`.
