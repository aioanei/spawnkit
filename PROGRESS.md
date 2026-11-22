# SpawnKit progress

## Current checkpoint — C10 preset games (2026-11-22)

- **Branch.** `feat/75-preset-games`, stacked on C9 (PR #80). Plan:
  [C10 preset games](docs/plans/2026-11-22-c10-preset-games.md).
  [Issue #75](https://github.com/aioanei/spawnkit/issues/75).
- **Added.**
  - Project → **New game…** opens a picker: Blank, Coin Dash, Tap Targets and Maze Run, each with a picture and a
    pitch. Phones get one column, wider screens two.
  - Presets are normal project files in `presets/`, each with a title screen, embedded by the new `core/presets`
    module. The `embedded-templates` generator gained `directory` and `objectName` settings in both builds.
  - The presets were made with the builder's own calls, in recipes that a test checks the files against.
  - A preset loads as a copy with no file, after the unsaved-changes question, so Save never overwrites another
    file.
- **Evidence.**
  - `PresetGamesTest` 8: catalog, no problems, Play on each title, and scripted players that win Coin Dash, Tap
    Targets and Maze Run, run out the Tap Targets timer and restart the maze on a wall. Green on JVM, Android host,
    JS, Wasm and mingwX64.
  - `PresetRecipesTest` 1 (a changed recipe fails it), `NewGamePickerTest` 6, including phone and desktop pickers.
- **Next.** C11 accounts ([#76](https://github.com/aioanei/spawnkit/issues/76)), which needs the public host, a
  Google OAuth client and SMTP from the owner.

## Previous checkpoint — C9 title screens (2026-11-22)

- **Branch.** `feat/74-title-screens`, stacked on C7c (PR #79). Plan:
  [C9 title screens](docs/plans/2026-11-22-c9-title-screens.md).
  [Issue #74](https://github.com/aioanei/spawnkit/issues/74).
- **Added.**
  - Scene operations `RemoveScene` and `MoveScene`, with catalog entries `add_scene`, `rename_scene`,
    `remove_scene` and `move_scene`. Position 1 is the start scene, so the format is unchanged.
  - A scene list in the builder: in the Scenes section (expanded) and the Scene tab (phones).
  - A "Go to scene" block over a `goToScene` program effect. Players switch to a fresh world after the tick
    (`StandardEngine.playSession`).
  - "Add title screen" as one undoable `Batch`.
  - Releases default to the first scene.
- **Evidence.**
  - New tests: `SceneOperationsTest` 3, `GoToSceneTest` 3, `PlaySessionScenesTest` 3, `SceneListUiTest` 3,
    `TitleScreenTest` 2.
  - `OperationParityTest` now covers all 19 entries.
  - On the API 36 emulator, real taps added a title screen and played it. Tapping its Play button on the canvas
    reached the level (`docs/media/android-title-*.png`).
- **Cut.** Duplicate scene, from the cut line. Numbers do not carry across scenes yet.
- **Next.** C10 preset games ([#75](https://github.com/aioanei/spawnkit/issues/75)).

## Previous checkpoint — C7c assistant UI (2026-11-22)

- **Branch.** `feat/69-assistant-ui`, stacked on the docs PR #78. Plan:
  [C7c assistant UI](docs/plans/2026-11-22-c7c-assistant-ui.md).
- **Added.**
  - The Assistant tab (expanded) and AI tab (phones): review, accept and undo, reject, progress, cancel, cost, and
    questions.
  - Ask AI on Rules and Properties.
  - The labelled keyless scripted demo (`DemoModel`).
  - Keys in a platform `SecretStore`: Android Keystore, Windows DPAPI through JNA 5.19.1, the macOS keychain and
    the Linux Secret Service.
  - A publish confirmation.

  See [AI Studio](docs/ai-studio.md#assistant-ui-c7c).
- **Evidence.**
  - New tests: `DemoModelTest` 5, `AssistantStateTest` 9, `DesktopSecretStoreTest` 4, `AssistantUiTest` 5, and
    `ReleaseUiTest` +1.
  - On the API 36 emulator, real taps built Score + collect and arrow-key movement through the demo. Each was
    accepted after review, and play reached "Score: 1" (`docs/media/android-assistant-*.png`).
- **Not live.** No live model was called: no key was available, and recording a live session needs the owner's
  consent.
- **Next.** C9 title screens ([#74](https://github.com/aioanei/spawnkit/issues/74)).

## Previous checkpoint — plan additions C9–C12 (2026-11-22)

- **Merged.** On the owner's instruction, #67 (C6c–C6d), #70 (ADR-0009) and #72 (C7b, which includes #71, C7a) are
  merged into `main`. Before merging, the combination passed:
  - Gradle `spotlessCheck allJvmAndWebTests localNativeTests`;
  - Toolchain `check ktlint`, `test -p jvm -p android` (586 passed) and `-p mingwX64` (252 passed).
- **Planned.** The owner added four features before C8 ([issue #73](https://github.com/aioanei/spawnkit/issues/73)):
  - [#74](https://github.com/aioanei/spawnkit/issues/74) C9: title screens.
  - [#75](https://github.com/aioanei/spawnkit/issues/75) C10: preset games.
  - [#76](https://github.com/aioanei/spawnkit/issues/76) C11: accounts with email and Google sign-in, owned games,
    sync, player accounts and a public Arcade.
  - [#77](https://github.com/aioanei/spawnkit/issues/77) C12: test ads with real counts.

  See the [design](docs/design/2026-11-22-title-screens-presets-accounts-ads.md) and
  [ADR-0010](docs/adr/0010-accounts-on-ktor.md).
- **Next.** C7c, then C9 → C10 → C11 → C12, then C8 (5–7 Dec reserved).

## Previous checkpoint — C7b AI Studio core (2026-11-21)

- **Branch.** `feat/69-ai-studio-core`, stacked on C7a (`feat/69-operation-catalog`, PR #71). The owner asked for work
  to continue while they were away.
- **Added.**
  - `StagedChanges` in `core/editing`.
  - A new `ai` module (client platforms; Gradle convention `spawnkit.kmp.client`) with `AgentToolbox`, `RuleTrace`,
    `AgentSession`, recording and labelled replay, and `KoogModel` on Koog 1.3.0.
  - The JS and Wasm yarn locks gain `ws` 8.20.1 for Ktor's JS engine.
  - See [AI Studio core](docs/ai-studio.md).
- **Evidence.** `StagedChangesTest` (3), `AgentSessionTest` (4) and `KoogModelTest` (2). Score + collect is built
  from prompts and equals the manual rules, then plays to "Score: 1". A non-firing rule is explained from a real
  trace. Coins are made worth 5. A clarifying question works. Cancel, refusals and stale proposals change nothing.
  `ai` passes on JVM, Android host, JS and Wasm (Node).
- **Gates (2026-11-22).** Gradle `spotlessCheck allJvmAndWebTests localNativeTests` is `BUILD SUCCESSFUL`, with no
  failing test reports: `ai` 6/6 on four targets, `editing` 34/34 and `gameplay` 20/20 on five. Toolchain:
  `check ktlint` is clean, and `test -p jvm -p android` and `-p mingwX64` pass. The Toolchain's interleaved native
  output drops some result lines, so counts come from the Gradle reports.
- **Not live.** No live model was called: tests and demos use hand-written fixtures labelled as scripted. Recording
  a live session needs a key and the owner's consent.
- **Next.** C7c: the assistant UI in compact and expanded layouts, "Ask AI", secure key storage and a publish
  confirmation, with Android emulator evidence.

## Previous checkpoint — C7a operation and rule block catalogs (2026-11-21)

- **Branch.** `feat/69-operation-catalog`, from `main` while CI ran on PR #67 (C6c–C6d) and PR #70 (ADR-0009 docs).
  [Issue #69](https://github.com/aioanei/spawnkit/issues/69), first requirement of C7.
- **Added.** Parameter metadata and an operation catalog in `core/editing`. Builder controls and JSON-Schema agent
  tools are derived from the same entries. `RuleBlockCatalog` covers every block, and `RuleOperations` adds,
  changes, moves, duplicates and removes rules and blocks (`core/gameplay`, which now depends on `core/editing`).
  The block dialog is generated from the catalog, and rule cards gain Move up, Move down and Duplicate. See the
  [operation catalog](docs/operation-catalog.md).
- **Parity.** `OperationParityTest` does each of the 15 catalog entries with real builder taps and with the derived
  JSON tool. Both give the same workspace, validation, capability check and undo. `AddScene`, `RenameScene` and
  `ReparentObject` have no builder control, so they stay out of the agent catalog.
- **Evidence.** Toolchain: `check ktlint` is clean. `test -p jvm -p android` passes 555 tests with 0 failed, and
  `-p mingwX64` passes 248. Gradle: `spotlessCheck allJvmAndWebTests localNativeTests` is `BUILD SUCCESSFUL`. Of
  those, editing ran 155 and gameplay 100 across JVM, Android host, JS, Wasm and mingwX64, and the builder ran 17.
- **Next.** C7b: the `ai` module with staged changesets (`StagedChanges`), the agent toolbox (derived tools, project
  inspection, headless rule traces, clarifying questions), session recording and labelled replay, and the Koog 1.3.0
  adapter. Then C7c: the assistant UI in compact and expanded layouts, plus a publish confirmation.

## Previous checkpoint — C6 Release Manager and Arcade (2026-11-20)

- Continued on `feat/64-builder-export-arcade` from the reviewed game package and web player in PR #66.
- Android and desktop creators can export a standalone ZIP or publish a release from **Project → Release game…**.
  Both destinations validate the game before writing. Ktor validates uploads again, persists them and serves each
  release at its own play URL. The creator and Arcade ship the same Toolchain-built player.
- On the API 36 validation emulator, the system save picker wrote a 10,930,353-byte ZIP. The extracted game
  ran in headless Chrome and keyboard movement collected a coin, reaching **Score: 1**. The same Android workspace
  published to the packaged Ktor server and played through its returned URL. The offline emulator reached the
  loopback server through a temporary adb tunnel. See the [release guide and evidence](docs/release.md).
- **Review (PR #67).** A code review led to these fixes:
  - The Arcade's upload limit is now in UTF-8 bytes (3 × the package character limit), so non-ASCII games are not
    refused. A package that outgrows the limit on re-encoding gets a 413 instead of a 500.
  - One damaged store file no longer hides the other releases.
  - Exports and the Arcade ship the optimized release player: its Wasm is 8.2 MB instead of 22.1 MB, and the bundle
    ZIP is 6.1 MB.
  - Android export writes in "wt" mode, which cloud providers accept. It clears a cancelled picker request, removes
    orphaned empty files, and the Activity no longer restarts on rotation.
  - Loopback hosts are matched case-insensitively.
  - Re-verified on the API 36 emulator: the rules, projects and release smokes pass. The exported ZIP is now
    7,357,435 bytes and plays to "Score: 1" in Chrome. Toolchain: 545 tests, 0 failed; the APK, desktop JAR and
    server JAR ship an identical player. Gradle: `spotlessCheck` and the server, builder and app targets pass.
- **Validation before commit (continued session).**
  - `./kotlin package -m androidApp` first failed: AGP's release lint rejected the network security config because
    `includeSubdomains` was implicit. Making it explicit keeps plain HTTP limited to the exact loopback hosts.
  - Toolchain: ktlint is clean; `test -p jvm -p android` passes 543 tests with 0 failures; `editing` passes on
    mingwX64; apps build. The server JAR, desktop JAR and release AAB package. The APK, desktop JAR and server JAR
    carry the byte-identical player.
  - Gradle: `spotlessCheck` passes. `allJvmAndWebTests localNativeTests` plus the app builds pass (663 tasks, 0
    failures).
- The compact and expanded Release Manager were exercised in the builder's JVM UI suite. Web and iOS creators
  display the available release platforms. Compiled store builds remain in K5/K6. C7 is the next contest slice.

## Previous checkpoint — C6 game package and player (2026-11-20)

- **Branch.** `feat/64-release-arcade`, stacked on the K1 branch (`chore/58-kotlin-toolchain`, PR #65), because the
  web player will be a Kotlin Toolchain `wasm-js/app`. [Issue #64](https://github.com/aioanei/spawnkit/issues/64).
- **Added (C6a).** `GamePackage` and `GamePackageCodec` in `core/editing`: a versioned `spawnkit-game` envelope
  around the unchanged project document, with a title, a start scene and derived required components. Tampered,
  foreign, newer, malformed and oversized packages fail with player-facing messages. `missingComponents(runtime)`
  lets players refuse games they cannot run. Documented in [persistence](docs/project-persistence.md).
- **Evidence.** `GamePackageTest` (6 tests); `editing` passes 26/26 on JVM, Android host and mingwX64 (Toolchain)
  and on JS and Wasm (Gradle Node). The new ktlint plugin caught over-long test lines, and `ktlintFormat` fixed them.
  `spotlessCheck` passes.
- **Added (C6b).**
  - `player/playerKit` holds the builder's play session, loop, surface, touch controls and play keys, moved
    unchanged; the builder now uses it. `GamePlayer(pkg)` refuses games with missing capabilities.
  - `player/webPlayer` (Toolchain-only `wasm-js/app`) plays `game.spawnkit.json` from beside its page.
  - Docs: [release](docs/release.md).
- **Evidence (C6b).**
  - `GamePlayerUiTest` 3/3, including a banner when rules stop or cannot run. The builder's 16 UI tests still pass on the moved code.
  - In headless Chrome, the Toolchain-built web player plays the sample package, and shows an explanation when the
    file is missing (`docs/media/web-player-*.png`).
- **Review.** On 2026-11-20 the owner asked for both PRs to be reviewed and merged, then for work to stop. Code
  reviews of #65 and #66 were addressed in `fix(build): address K1 review findings` and
  `fix(release): address C6 review findings`. The pre-existing template-renderer issues are recorded on #65 as
  follow-ups.
- **Next steps.**
  - C6c: Release Manager screen with web export (player bundle plus package) on Android and desktop.
  - C6d: Ktor Arcade publish/list/get with limits and `/play/{slug}`.

## Previous checkpoint — K1 Kotlin Toolchain adoption (2026-11-20)

- **Decision.** The owner made the Kotlin Toolchain mandatory, for SpawnKit's own build and as the Release Manager's
  build engine. [ADR-0008](docs/adr/0008-kotlin-toolchain.md), [issue #58](https://github.com/aioanei/spawnkit/issues/58),
  branch `chore/58-kotlin-toolchain`. C5 is merged (PR #57).
- **Added.**
  - The Kotlin Toolchain 0.14.1 wrappers, checksum-verified.
  - `project.yaml` and a `module.yaml` for every module except the benchmark harness, with templates in `toolchain/`.
  - Local plugins `embedded-templates` and `ktlint`.
  - Sources moved to the Toolchain layout; Gradle reads it through `ToolchainLayout.kt`.
  - A `kotlin-toolchain` CI job and an iOS Toolchain step.
  - The core `kotlin-tooling-kotlin-toolchain` skill was added to the vendored skill set. Skills and the kotlinlang MCP
    server are configured for Claude Code and Codex.
  - Docs: [migration status](docs/kotlin-toolchain.md), the
    [Release Manager build architecture](docs/design/2026-11-20-release-manager-kotlin-toolchain.md), and plan track K.
  - Issues #59–#63 (K2–K6) and #64 (C6) were created; epic #11 was updated.
- **Evidence.**
  - Toolchain: `test -p jvm -p android` passes 517/517 with no failures. `-p mingwX64` passes 231/231.
    `check ktlint` is clean, and a planted violation fails it. Apps build; server, desktop JAR and Android AAB package.
  - Counts equal Gradle's on JVM (231 core, 19 render, 16 shared, 1 server), Android host (231 + 17) and mingwX64
    (231).
  - CI on PR #65: `build`, `kotlin-toolchain` and `ios-simulator` all pass. The Toolchain ran 517 JVM/Android, 231
    linuxX64, 248 Wasm browser and 248 iOS simulator tests.
  - Runtime: the Toolchain APK played rules to "Score: 1" on the API 36 emulator. The desktop JAR and the Wasm bundle
    render the builder. The server JAR answers `/health`.
  - Gradle on the moved sources: `spotlessCheck` passed. Separately, `allJvmAndWebTests localNativeTests` plus
    Android, desktop and JS/Wasm compilation passed (`BUILD SUCCESSFUL`, 624 tasks).
- **Found and fixed.**
  - Replacing source directories in Gradle dropped Compose's generated resource sources; directories are now added.
  - Python edits on Windows default to cp1252 and CRLF; repaired, and later edits use explicit UTF-8.
  - The Toolchain's Wasm test provisioning ran `pnpm add playwright` against a Node project in the user's home
    directory. Its `package.json`, lockfiles and `node_modules` were restored from the user's npm lockfile. This is
    documented as known issue 1; do not run Wasm tests locally on this machine.
  - A Gradle daemon died from system memory exhaustion with the emulator running; it passed after idle daemons were
    stopped.
- **Next step.** Owner review of PR #65. Then continue C6 ([#64](https://github.com/aioanei/spawnkit/issues/64)) with the Toolchain-built web player; K2 follows
  once the Toolchain job is green on `main`.

## Previous checkpoint — C5 project persistence (2026-11-20)

- **Merged.** The owner authorized integrating C4b and C4c: PR #54 merged as `09d3bea`; PR #55 was retargeted to
  `main`, verified green, then merged as `4a6c874`. Issues #52 and #53 are closed.
- **Branch.** `feat/56-project-persistence`, from updated main; [issue #56](https://github.com/aioanei/spawnkit/issues/56).
- **Added.** Versioned workspace/rule codec, two-generation recovery journal, retained-ID save snapshots, missing
  capability checks and non-destructive previews. Project menu, native Android/desktop file pickers, autosave,
  explicit recovery and unsaved-change dialogs; basic browser/iOS recovery adapters.
- **Validation.** Six persistence common tests and six persistence UI tests pass. The complete local gate passed:
  `spotlessApply`, then separately `spotlessCheck allJvmAndWebTests localNativeTests` plus Android APK, desktop and
  JS/Wasm app compilation (`BUILD SUCCESSFUL`, 615 tasks, 2m 11s). The cross-target gate caught negative zero being
  normalized on JS; the codec now retains its explicit token. Phone QA caught a squeezed Play label; the compact
  toolbar now has an object-actions menu and a 360 dp / 130% text regression test.
- **Android evidence.** The API 36 emulator saved through the system document picker, started a blank game, reopened
  the saved file and played its rules to "Score: 1". After adding Circle and force-stopping the process, Restore draft
  brought back all four authored objects. Captures are in `docs/media`. The smoke harness now waits for populated
  accessibility trees and prefers visible Scene-tab text over the viewport's matching description.
- **PR.** [#57](https://github.com/aioanei/spawnkit/pull/57); Linux/iOS CI checks are the authority for remote status.
  See [persistence](docs/project-persistence.md) for the workflow, test evidence and explicit platform limitations.
- **Stop point.** The owner requested stopping after this C5 PR is finished. C6 (Release and Arcade) remains planned;
  do not start it during this task. The preserved player-shell checkpoint remains separate.

## Previous checkpoint — C4c When / If / Do rule editor (2026-11-20)

- **C4b.** [PR #54](https://github.com/aioanei/spawnkit/pull/54) has green `build` and iOS checks. The automatic
  permission classifier refused the merge command twice, so it waits for the owner to merge or allow it.
- **Branch.** `feat/53-rule-editor` ([issue #53](https://github.com/aioanei/spawnkit/issues/53)), stacked on
  `feat/52-workspace-rules`.
- **Added.**
  - `core/gameplay`: `RuleBlocks`, with conditions, actions, compile and exact-recompile decompile, advanced rules
    kept unchanged, block locations and descriptions. Also corrected tick semantics: the first tick is 1.
  - `app/shared`: a Rules tab (phone) and panel (desktop), the block dialog with chips and steppers, problems on
    blocks, and Player/Coin/Score presets.
  - Fixed: on phones the toolbar's undo/redo enabled state went stale after undo; the toolbar now reads `revision`.
  - Resume review: added plain-text actions, bounded the timer picker to the supported one-day maximum, disabled
    steppers at their limits, and made diagnostic lookup tolerate oversized block IDs.
- **Evidence.**
  - `RuleBlocksTest` (7) and `RuleEditorUiTest` (3): the phone-size test builds Score + collect with taps only and
    plays it. Text actions execute and round-trip; the phone UI edits text and respects the timer limit.
  - A fresh desktop screenshot shows block rules alongside an advanced rule; the UI test preserves the advanced rule.
  - Resume gates passed: `spotlessApply`, then separately `spotlessCheck allJvmAndWebTests localNativeTests`, plus
    Android APK, desktop, Wasm UI and JS renderer compilation (`BUILD SUCCESSFUL`, 609 tasks, 2m 30s). Linux native
    tests are skipped on Windows; remote Linux and iOS checks remain required.
  - The Android API 36 emulator, driven by `tools/android-rules-smoke.py`, built the same game with real taps and
    showed "Score: 1" while playing.
- **Next step.** Owner review and integration of C4b and C4c (C4c stays based on C4b until #54 merges), with green
  remote build and iOS checks. Then C5, project persistence: save, open, autosave and recovery through a versioned
  codec. Rules (program handlers) need serialization there.

## Current checkpoint — C4b rules in the workspace and playable input (2026-11-20)

- **Merged.** PR #51 (C4a gameplay facilities) as `24613af`, after green `build` and iOS checks; issue #49 is closed.
- **Planning.** The rest of C4 is split into [issue #52](https://github.com/aioanei/spawnkit/issues/52) (C4b,
  executable rules and input) and [issue #53](https://github.com/aioanei/spawnkit/issues/53) (C4c, the
  When / If / Do editor).
- **Branch.** `feat/52-workspace-rules`.
- **Added.**
  - `core/editing`: `ObjectDoc.rules` (program handlers) and `SetRules`. `Editor(programs = ...)` and
    `buildSceneWorld` attach prepared rules, with content-versioned program IDs and `SceneWorld.diagnostics`.
  - `app/shared`: the builder's editor runs rules through `StandardEngine.programs`. Play input comes from the
    keyboard, from world-space taps (quick taps latched to one tick), and from on-screen controls on compact layouts.
    Rule faults and problems are explained in the status bar.
  - Docs: editing and builder.
- **Next step.** Gates, PR, review, merge. Then C4c: a block catalog that round-trips with handlers, a Rules
  section for the selected object, diagnostics on blocks, and a phone-size UI test building Score + collect from taps
  only.

## Current checkpoint — C4a rule-ready gameplay facilities (2026-11-20)

- **Merged.** PR #50 (C3 builder shell) as `cba603f`, after a pre-PR self-review that added inline validation
  messages, keyboard shortcuts and a horizontal-scroll fix; issue #47 is closed. At the owner's request, PR #25
  (architecture reassessment) as `07ae1d6`, after merging main into it: main's current plan and guidance were kept,
  and its "waiting for review" notes were reworded as reviewed.
- **Branch and issue.** Branch `feat/49-gameplay-facilities`; [issue #49](https://github.com/aioanei/spawnkit/issues/49).
  An earlier untested WIP commit for this issue was dropped and rebuilt test-first.
- **Added.**
  - `core/program`: the `Destroy` statement (atomic, end-of-tick, removes children, faults on stale targets) and
    `HostCallContext.world`.
  - `core/runtime`: `InputView.previousPointers`; pointers are documented as world units.
  - `core/graphics` and `render`: `Text2D`, drawn by the shared renderer, with a pixel test.
  - New module `core/gameplay`: `Tag`, `NumberValue`, seeded random, `touching`, `tapped`, `firstTagged`,
    `countTagged`, `textOf`, `random`, `tick`, standard input actions and `StandardEngine`. The builder now uses
    `StandardEngine`.
  - Docs: [gameplay](docs/gameplay.md), plus updates to programs, input, 2D graphics and builder docs.
- **Next step.** Gates, PR, review, merge. Then C4b: attach rules to workspace objects (unique program IDs per
  object), submit keyboard and world-space pointer input from the play view, and build the touch-friendly
  When / If / Do editor on top of `StandardEngine`'s catalog.

## Current checkpoint — C3 builder shell (2026-11-20)

- **C2 is done.** PR #48 merged as `955d589`; the C3 branch already sits on that merge.
- **Branch and issue.** Branch `feat/47-builder-shell`; [issue #47](https://github.com/aioanei/spawnkit/issues/47).
- **Builder.** The real builder lives in `app/shared`: `EditorState`, `Viewport` (touch and mouse gestures, drag
  move as one undo step, pan, pinch and wheel zoom), the descriptor-generated `Inspector`, `EditorScreen` (compact and
  expanded layouts, palette, hierarchy, toolbar, status) and a 60 Hz play loop.
- **Verification.** Compose UI tests pass at phone and desktop sizes, with a viewport pixel check. The Android debug
  APK was installed and driven on the API 36 emulator with real taps and a swipe. Screenshots are in `docs/media`.
  Emulator testing found and fixed density scaling, grid density and selection visibility. Docs:
  [builder](docs/builder.md).
- **Pre-PR review fixes.** Inline validation messages under rejected inspector fields (an issue requirement that only
  reached the status bar), keyboard shortcuts on a focused scene (undo/redo, duplicate, delete, nudge, Escape), and
  horizontal-only scrolls no longer zoom in. Each has a UI test.
- **Next step.** Open the C3 PR, review and merge. Then C4: visual rules. Install
  `ProgramFeature` in `BuilderEngine` with descriptor and input bindings and gameplay host functions, add a
  `ComponentDoc`-like behavior attachment for programs in the workspace, and build a touch-friendly When/If/Do editor.

## Current checkpoint — C2 editing core (2026-11-20)

- **C1 is done.** PR #45 merged after an independent review; zoom guard, bounded grid and a real layer-order pixel
  check were fixed before merge.
- **Branch and issue.** Branch `feat/46-editing-workspace`; [issue #46](https://github.com/aioanei/spawnkit/issues/46).
  It adds module `core/editing`:
  - immutable Project/SceneDoc/ObjectDoc/ComponentDoc;
  - typed validated `EditOperation`s;
  - `Editor`, with snapshot undo/redo, revisions and listeners;
  - `buildSceneWorld`, with identity mapping for selection and play isolation.
- **Tests.** 7 common tests pass on JVM, Android host, JS, Wasm and mingwX64. Docs: [editing](docs/editing.md).
- **Next step.** PR, review, merge. C3 (builder shell) is already implemented on `feat/47-builder-shell`, stacked on
  this branch: Compose UI tests at phone and desktop sizes pass and the Android debug APK builds. Android emulator
  verification is in progress.

## Current checkpoint — C1 2D presentation (2026-11-20)

- **Vision reconciliation done.** PR #43 merged after an independent review, with its fixes applied before merge.
  Issue #42 is closed.
- **Branch and issue.** Branch `feat/44-graphics2d`; [issue #44](https://github.com/aioanei/spawnkit/issues/44).
- **Added.**
  - New module `core/graphics`: Transform2D/Shape2D/Sprite2D/Camera2D with descriptors and codecs; Affine2D;
    render lists in layer and scene order; hit testing; view transforms.
  - `ValueKind.Color`.
  - `render/RuntimeCanvas`: a shared Compose renderer with a JVM Skia pixel test and screenshot
    `docs/media/runtime-canvas.png`.
- **Verified locally.** The renderer compiles for Android, JS and Wasm. Docs: [2D graphics](docs/graphics-2d.md).
- **Next step.** Gates, PR, review, merge. Then C2: `core/editing`, with the workspace (project, scenes, objects
  from component descriptors and seeds, rules as programs), typed operations, undo/redo and play-world
  instantiation. Then C3, the responsive builder on Android and desktop.

## Current checkpoint — product vision reconciliation and contest track (2026-11-20)

- **Merged before this work.** PR #41 (R2f scene templates) merged as `cfa16a3`, after green CI and an independent
  AI-subagent review whose fixes landed before merge. R1 and R2a–R2f are complete.
- **Owner clarification.** The owner clarified the full product vision; the three non-negotiables are a genuine
  engine, a full builder on Android and desktop, and release from the builder.
- **Branch.** `docs/42-vision-gap-analysis`.
- **Added.**
  - [Gap analysis](docs/design/2026-11-20-product-vision-gap-analysis.md): classifications, with contest rules
    rechecked 2026-11-20.
  - [ADR-0007](docs/adr/0007-mobile-builder-and-in-builder-release.md).
  - The plan's
    [contest delivery track](docs/plans/2026-11-20-engine-foundation-revision.md#contest-delivery-track) C1–C8.
  - Updated agent guidance and plan index.
- **Correction.** The earlier "≥ 2 platforms" contest rule was an inference; the official rules state none.
- **Key facts.**
  - The creator app is still a shell; no editor, AI or server functionality exists yet.
  - The engine direction is retained without rewrites.
  - Earlier desktop/web-only creator scoping and Gradle-built per-game players are superseded.
- **Next step.** C1, 2D presentation facilities in the general runtime: transform, shape and sprite drawables,
  layers, camera, and a Compose renderer for any runtime world shared by editor and player. Then C2 workspace and
  operations, and C3 the responsive builder on Android and desktop.

## Current checkpoint — R2f scene templates and instance identity (2026-11-20, autonomous overnight session)

- **R2e is done.** PR #39 merged as `175b432`. Review fixes: atomic registration, and foreign inputs fault the rule.
- **Branch and issue.** Branch `feat/40-scene-templates`; [issue #40](https://github.com/aioanei/spawnkit/issues/40).
  Adds `SceneTemplate`/`TemplateObject`/`ComponentSeed`, `World.instantiate`, `WorldCommands.instantiate`,
  `SceneInstance` and `World.identity`, with a checkpointed counter and identities.
- **Next step.** Gates, PR, review, merge. R2 then has only small remaining items: asset resource references and
  scenario stepping helpers. Then R3: extract the legacy renderer's viewport/camera/input-cancellation/
  interpolation into generic observations, and add transforms and drawable/layer APIs over `core/runtime`.

## Previous checkpoint — R2e input functions for rules (2026-11-20, autonomous overnight session)

- **R2d is done.** PR #37 merged as `6d6e8fe`. Review fixes: defensive frame copies, and checkpointing of
  pointer-only input.
- **Branch and issue.** Branch `feat/38-program-input`; [issue #38](https://github.com/aioanei/spawnkit/issues/38).
  Adds `ProgramHostBuilder.inputs` (isDown/wasPressed/wasReleased/axis by ID) and `HostCallContext.input`.
- **Next step.** Gates, PR, review, merge. Then scene-instance identity (authoring IDs mapped to runtime handles),
  or R3 presentation facilities (transforms, camera, drawables) extracted from the legacy renderer.

## Previous checkpoint — R2d deterministic input (2026-11-20, autonomous overnight session)

- **R2c is done.** PR #35 merged as `4bb8295`. The review found nothing blocking; descriptor bindings now register
  atomically.
- **Branch and issue.** Branch `feat/36-input`; [issue #36](https://github.com/aioanei/spawnkit/issues/36).
- **Added.** `RuntimeBuilder.action`/`axis`, `InputFrame`/`Pointer`, `World.submitInput` and `World.input`
  (isDown/wasPressed/wasReleased/axis/pointers). Checkpointed current/previous/pending frames, with a hash section
  only when inputs are registered. Recorded replay matches the live hash on all targets. Docs:
  [input](docs/runtime-input.md).
- **Next step.** Gates, PR, review, merge. Then:
  - scene-instance identity (authoring IDs mapped to runtime handles);
  - program input host functions;
  - R3 presentation facilities (transforms, camera, drawables), extracted from the legacy renderer.

## Previous checkpoint — R2c descriptor-derived program bindings (2026-11-20, autonomous overnight session)

- **R2b is done.** PR #33 merged as `e2ac0f9`. The review found nothing blocking; hierarchy position validation now
  runs before any change.
- **Branch and issue.** Branch `feat/34-descriptor-bindings`;
  [issue #34](https://github.com/aioanei/spawnkit/issues/34).
- **Added.** `ProgramHostBuilder.properties(descriptor, prefix)` maps whole number, decimal, yes/no, text and choice
  properties to typed program properties. Writes are validated when they execute, with the new
  `FaultKind.InvalidValue` and rollback. `PropertyDescriptor.with` is now public.
- **Next step.** Gates, PR, review, merge. Then continue R2 with:
  - input actions/axes/pointers as runtime resources, with a deterministic per-tick input frame;
  - scene-instance identity (authoring IDs mapped to runtime handles).

## Previous checkpoint — R2b hierarchy, resources and seeded random (2026-11-20, autonomous overnight session)

- **R2a is done.** PR #31 merged as `c4cde07`. The independent review found nothing blocking; edit gating, factory
  validation and cross-target decimal input were fixed before merge.
- **Branch and issue.** Branch `feat/32-hierarchy-resources`;
  [issue #32](https://github.com/aioanei/spawnkit/issues/32).
- **Added.**
  - World hierarchy: `setParent`, `parent`, `children`, `destroyTree`, deferred commands, and detach-on-destroy.
  - `RuntimeBuilder.resource` and `World.resource`.
  - SplitMix64 `SeededRandom` with a codec.
  - Snapshot and hash sections that appear only when non-empty, so existing goldens are unchanged.
- **Tests.** All targets pass locally. Docs: [hierarchy](docs/runtime-hierarchy.md).
- **Next step.** Gates, PR, review, merge. Then continue R2 with:
  - function/event descriptors, deriving `ProgramHost` bindings from component descriptors;
  - input actions/axes as runtime resources;
  - scene-instance identity (authoring IDs mapped to runtime handles).

## Previous checkpoint — R2a capability descriptors (2026-11-20, autonomous overnight session)

- **R1 is complete.** PR #29 (R1d executor) merged as `1d8edab` after green CI. An independent AI-subagent review
  found two blocking issues: a type hole letting a `ClassCastException` escape, and unbounded text growth. Both
  were fixed and re-verified, along with should-fix items. Issue #28 is closed.
- **Branch and issue.** Branch `feat/30-capability-descriptors`;
  [issue #30](https://github.com/aioanei/spawnkit/issues/30).
- **Added in `core/runtime`.**
  - `RuntimeBuilder.describe`, with value kinds (whole number, decimal, yes/no, text, choice, object reference),
    constraints, defaults, visibility and examples.
  - `Runtime.catalog` and `descriptor()`.
  - Inspector functions: `inspect`, typed and ID-based `edit` returning undo values, `addComponent` and
    `referenceCandidates`.
  - `samples/runtimeProbe` describes `Counter` and edits it through the generic model.
- **Docs.** [descriptors](docs/runtime-descriptors.md).
- **Next step.** Run the gates, open the PR, review and merge. Then continue R2 with hierarchy (parent/children
  with cycle checks and ordered children), seeded RNG and timers as runtime resources, and function/event
  descriptors. Deriving `ProgramHost` property bindings from component descriptors is a follow-up.

## Previous checkpoint — R1d portable program executor (2026-11-20, autonomous overnight session)

- **R1c is done.** PR #27 merged as `9732f1b` after green `build`/`ios-simulator`. An independent AI-subagent review
  found nothing blocking; its fixes are in `573d649` and `a2718f9`. Issue #26 is closed. PR #25 (reassessment)
  stays open for owner review.
- **Branch and issue.** Branch `feat/28-program-executor` from main `9732f1b`;
  [issue #28](https://github.com/aioanei/spawnkit/issues/28).
- **Screening.** Every embedded candidate fails required targets. quickjs-kt 1.0.18 and Chasm 2.5.0 publish no
  js/wasmJs variants. korlibs Luak 4.0.0-alpha-2 has no wasmJs and has been unmaintained since 2023. minigdx Luak
  covers jvm/js only.
- **New module `core/program`.** Typed program tree, type checker with source diagnostics, resumable bytecode VM
  and `ProgramFeature` runtime integration. It covers deterministic limits, atomic segments, checkpointed waits and
  effect IDs that replay deterministically.
- **Checkpoints.** `a9bbf05` (executor and tests), `1ab0eee` (platform-independent fingerprints).
- **Tests.** 30 tests pass on each of JVM, Android host, JS, Wasm and mingwX64. The cross-target golden is fuel
  3044, hash `-1990361367860152568`. Decision note: [runtime programs](docs/runtime-programs.md).
- **Next step.** Run the full gates, open the PR, get an independent review, then merge on green CI. After that,
  R1 is complete except for focused rendering/collision choices, which the plan allows to stay open until R3.
  Next is R2: general world, resources and execution metadata. Start with typed property/function/event
  descriptors (authoring metadata) and hierarchy/resource references in `core/runtime`.

## Previous checkpoint — R1c runtime storage decision (2026-11-20, autonomous overnight session)

- **Session context.** The owner asked for autonomous overnight continuation of the approved plan. They authorized
  merging the agent's own PRs once required CI passes, after independent review, without bypassing branch rules.
- **PR #25.** The uncommitted architecture reassessment was committed as `docs/19-engine-architecture-reassessment`
  and opened as [PR #25](https://github.com/aioanei/spawnkit/pull/25). It stays open for owner review and is not
  merged autonomously. Its status lines still say "proposed for review".
- **Scope of this work.** It follows the R1 storage item already approved in the merged PR #20 plan.
- **Branch and issue.** Branch `feat/26-runtime-storage` from main `ff86bd0`;
  [issue #26](https://github.com/aioanei/spawnkit/issues/26).
- **Fleks screening.** Fleks 2.16 was rejected at target screening. Its published Maven artifacts cover jvm, wasmJs,
  linuxX64, mingwX64 and macOS, but have no JS variant (last JS release 2.12) and no iOS variant in any version.
  It was not integrated or benchmarked.
- **Equivalence fixture.** A seeded churn scenario in `samples/runtimeProbe` uses public APIs only. Its goldens were
  recorded from the original map store at checkpoint `9a49597`: 298 entities, hash `-1536454527644900448`. Query
  order feeds private state, so the goldens also pin ordering.
- **Selected store.** Sparse-set `ComponentColumn`s indexed by component ordinal, smallest-column-driven ordered
  queries, cached live handles, and presized restore/ordered capture (`bbc5c23`, follow-up perf commit). The public
  API and checkpoint format are unchanged, and the baseline goldens pass on the new store.
- **Benchmarks.** The `benchmarks/runtimeStorage` executable measures the public API on JVM, Node JS, Node Wasm and
  mingwX64. Queries are 4.4–23× faster and steady/churn ticks 1.6–7.4× faster. Snapshot/restore are 1.0–4.5× faster,
  with two GC/JIT-noisy rows recorded as inconclusive; JVM snapshot allocation is slightly lower. Raw outputs and tables:
  [runtime storage](docs/runtime-storage.md), [evidence](docs/evidence/runtime-storage/).
- **Evidence gaps.** No Android/iOS device benchmarks, no linuxX64 run on this host, a single unisolated machine,
  and allocation measured on JVM only.
- **Next step.** Run the full gates, push, open the PR, get an independent review and merge only on green CI. Then
  start the R1 program/host-effect comparison as its own issue. Screen Lua, QuickJS and guest Wasm against required
  targets first, then build the common Kotlin interpreter fixture.

## Previous checkpoint — R1b native checkpoints and replay (2026-11-20)

- The owner authorized merging PR #22 and granted standing authorization for future completed PRs after required checks pass. PR #22 merged at `b4e90ed`; branched from clean main as `feat/23-runtime-checkpoints`. [Issue #23](https://github.com/aioanei/spawnkit/issues/23) tracks this focused R1 increment.
- Implemented opaque in-memory snapshots for healthy idle worlds, optional component state codecs, explicit `CheckpointSystem` capture/restore of private native fields, complete current allocator/tick state, live/stale same-world entity remapping and fresh independent restores. Unsupported attached state/system contracts fail explicitly.
- Snapshot value collections are copied on capture and per restore. Contexts expire/release world references; capture blocks writes through its world API. Traversal limits reject cyclic/over-budget values. Native hooks remain trusted/pure by contract; this is not automatic serialization of arbitrary native/external state or portable-program rollback.
- Added a canonical FNV-1a state fingerprint and a separately compiled consumer mid-run replay test. The single golden `-4909871966990312915L` was derived from JVM six-tick Counter(5), including private update count=6; other targets must match it unchanged.
- Tests first failed for the missing API. After implementation, a nullable collection assertion needed a type-inference correction; remaining JVM failure was the intentionally unset golden. The recorded JVM golden and all 33 runtime/consumer tests then passed. Final `spotlessApply` passed (53 s), followed separately by `spotlessCheck allJvmAndWebTests localNativeTests` (`BUILD SUCCESSFUL`, 1m8s; 343 tasks). All 33 tests pass on JVM, Android host, JS Node, Wasm Node and mingwX64: 165 executions, zero failures/errors, one unchanged golden.
- Source checkpoint `a85e087` contains the state contracts/checkpoints/hash/replay tests. Full-branch documentation verification passed (8 Markdown files, 49 local links/anchors); `git diff --check` passed. Linux/iOS execution remains CI evidence, not claimed from Windows.
- Checkpoint `f3e428d` documents the API/evidence and remaining scope. [PR #24](https://github.com/aioanei/spawnkit/pull/24) contains this increment; its latest Linux/iOS checks are the source of remote status. Merge only after those checks pass, under the owner's standing authorization, without bypassing repository rules.
- No dependency or persistence format was added. Legacy model/engine/renderer and platformer golden are unchanged. Remaining R1 storage/program/effect comparisons and richer capabilities remain open; no visual editor or novice study is claimed.

## Previous checkpoint — R1a headless runtime implementation (2026-11-20)

- The owner explicitly authorized merging PR #20 and continuing implementation. PR #20 merged at `eae2d3f`; branched from clean main as `feat/21-runtime-foundation`. [Issue #21](https://github.com/aioanei/spawnkit/issues/21) tracks this first R1 evidence increment.
- R0 reconciled: issues #6–#13 preserve their original bodies and now record revised dependencies; #6/#7 remain open. Obsolete M1–M6 due dates were removed; the external contest showcase date was retained. Player shells remain at `b2bf830` on their separate branch.
- Added `core/runtime`, independent of model/legacy engine/Compose, and a separately compiled `samples/runtimeProbe`. Empty worlds execute without JSON/player assumptions; open typed components, generation-checked world handles, stable queries, phased/dependency-ordered systems and per-tick deferred structural changes are implemented.
- Native failure handling is explicit: fail-stop world, pending queue discarded, prior native writes/commits retained for inspection. This is trusted Kotlin execution, not the future sandboxed/atomic portable-program layer. Friendly name/help/category metadata exists; no editor UI or complete metadata system is claimed.
- Tests were written first and failed for the missing API, then passed after implementation. Checkpoint `27ffb8e` contains the runtime, separate consumer and 16 common tests. Final `spotlessApply` passed (38 s), followed separately by `spotlessCheck allJvmAndWebTests localNativeTests` (`BUILD SUCCESSFUL`, 53 s; 343 tasks). All 16 new tests pass on JVM, Android host, Node JS, Node Wasm and mingwX64: 80 test executions with zero failures/errors. Linux execution and iOS simulator results remain CI evidence; Linux tests are skipped on Windows.
- Full branch documentation links/anchors and `git diff --check` passed. No production dependency was added. Primary Fleks 2.16 sources were inspected, including disabled iOS declarations and Wasm-only web conventions; actual adapter/artifact compatibility and benchmark selection remain open, not inferred from those declarations.
- Checkpoints: `27ffb8e` implements the runtime/consumer/tests; `38566fd` records execution contracts and R0 reconciliation. [PR #22](https://github.com/aioanei/spawnkit/pull/22) contains this increment; its checks are the source of Linux/iOS CI status. It has not been merged. Continue remaining R1 evidence after this focused increment, not the old player-shell task.
- Existing platformer/runtime/model/renderer source remains unchanged. R1 storage/backend comparison, state capture/restore/hash and program/interpreter proofs remain unfinished; this increment does not select a production storage backend or complete R1.

## Previous checkpoint — engine ownership and guided creation plan revision (2026-11-20)

- The owner confirmed that SpawnKit is our own engine, removed time pressure as an architectural constraint, requested creation without programming knowledge and selected guided visual creation. The later “Please implement the plan” authorizes the documentation revision specified in the reviewed plan.
- Updated the existing design, R0–R9 implementation sequence and ADR-0006; synchronized plan indexes, agent instructions, README, implemented-architecture status and historical-document supersession notes. This task adds no engine/editor/runtime implementation or dependencies.
- Accepted boundaries: SpawnKit owns its runtime, gameplay APIs, behavior execution, editor capabilities and SDK. Focused supporting libraries remain eligible; adopting a complete third-party engine as the foundation is excluded. R1 still selects implementations from actual compatibility evidence.
- Default creation path: functional blank project or optional example → drag objects → configure friendly behaviors → assemble visual When / If / Do rules → Play/inspect/Stop/undo → guided export/publishing. AI is optional; ordinary creation/local play needs no code, JSON editing, technical IDs, model credentials or cloud/payment setup.
- Added extension-generated visual controls from authoring descriptors, progressive disclosure, optional onboarding, contextual help, previews, accessibility, autosave/recovery and actionable object/behavior errors. All authoring paths share the same executable model.
- Added the novice release gate: at least four of five first-time non-programmers independently complete creation/modification, correction of a seeded mistake, undo, save/reopen and launch, with built-in guidance but no instructor or AI assistance. Repeat exercises across different game mechanics and an extension behavior. This is planned evidence, not a completed user study.
- Removed the active contest-driven deadline/cut-lines and one-day evaluation limit. Capability, correctness and usability gates determine readiness; the contest remains a showcase opportunity. All approved product features and mandatory KMP/Compose/Koog/Ktor technologies remain.
- Preserved unfinished player shells at `b2bf830` on `feat/6-player-shells`; no migration or restart occurred. R0 acceptance/document reconciliation is recorded; remote issue/milestone reconciliation and the clean implementation baseline check remain outstanding before R1.
- Verified the GitHub destination against `origin`: `aioanei/spawnkit` is public and the connected owner has admin access. This evidence and the explicit issue/PR workflow instruction resolved the earlier automatic approval-review rejection; [documentation issue #19](https://github.com/aioanei/spawnkit/issues/19) was created from the reviewed requirements. Branch renamed to `docs/19-own-engine-no-code-plan`.
- Checkpoint `ed83767` implements the no-code/ownership revision. [PR #20](https://github.com/aioanei/spawnkit/pull/20) contains this documentation work and the preceding architecture research, closes #19 when merged, and preserves #6/#7 as unfinished work. Its checks are the source of current remote CI status; no merge is performed by this documentation task.
- Local checks passed for the full branch diff against main: 15 Markdown files, 52 relative links/anchors resolved, zero failures; `git diff --check` passed. Separate `spotlessApply` passed (52 s), then `spotlessCheck allJvmAndWebTests` passed (`BUILD SUCCESSFUL`, 37 s; 247 actionable tasks, cached/up-to-date results reused where valid). No `core/*` source changed, so the Windows native gate was not required for this documentation task.

## Historical checkpoint — architecture proposal before owner refinements (2026-11-20)

- The owner requested a general-purpose, extensible KMP 2D engine with an integrated visual editor, custom gameplay and developer extensions. Persistence must not define the engine's capabilities. Implementation is paused until the revised plan is reviewed.
- Reviewed main at `5353e2a`, merged PR #18, no open PRs, open issues and remote checks. PR #18 and post-merge Linux/iOS checks passed (runs `44626157006`, `44626157027`). No unfinished renderer changes remained.
- Began the formerly approved Task 11 / issue #6, then preserved all work when the architecture changed: local checkpoint `b2bf830`, branch `feat/6-player-shells`. Do not discard or merge it as a generic engine contract. It contains shared adventure navigation, four player shells, build/CI wiring and tests.
- Before the pause, six `PlayerStateTest` JVM tests and `spotlessApply` passed. The larger test/platform build run was interrupted with no final success result; it is not counted as passed. Desktop/browser/Android player acceptance and iOS linking remain unfinished.
- Android validation: original API-36 AVD refused startup with low C: space. A separate validation AVD under `D:/SpawnKit-validation/avd/` booted, then stopped; no player acceptance was performed. Existing user AVD data was preserved.
- Proposal branch `docs/6-engine-architecture` starts from main and contains documentation only. [Design and research](docs/design/2026-11-20-general-purpose-engine.md), [revised sequence](docs/plans/2026-11-20-engine-foundation-revision.md) and [ADR-0006](docs/adr/0006-general-purpose-engine-and-editor.md) are proposed, not accepted.
- Research compared scene/object composition, pure ECS, a scene/component authoring model over a scheduled runtime, and existing-engine integration. Recommendation includes real editor capabilities, portable programmable behaviors, a compiled KMP extension SDK and explicit generality/extension proof gates. No new library, runtime or persistence implementation was added.
- All approved product features and mandatory KMP/Compose/Koog/Ktor technologies remain. Old daily estimates need re-estimation after the foundation spike; no replacement deadline or implicit feature cancellation was invented.
- Automatic approval review rejected creating the architecture-review GitHub issue because it would transmit project architecture/repository metadata externally without sufficiently explicit payload/destination authorization. No issue, push or PR was created for this revision. Prepare the review artifacts locally, then ask the owner to authorize publication to `aioanei/spawnkit`.
- Proposal verification: 15 changed/new Markdown files checked, 52 relative links/anchors resolved, zero failures; `git diff --check` passed. Two labelled AI technical reviews informed the state-capture, effect-failure/replay and bounded-evaluation contracts. No runtime source, build configuration or dependency changed on this branch.
- Prepared publication text is available locally in ignored `build/architecture-issue.md` and `build/architecture-review-pr.md`; neither has been transmitted.
- Next action: owner review of the concrete proposal and remote-publication authorization. Do not resume implementation, old Tasks 11–12, or GitHub backlog restructuring automatically.

## Preserved player-shell checkpoint — Task 11 / issue #6

- At the start of that work, the tree was clean on main; existing local reports contained 400 test executions with no failed/error suites. These describe the earlier baseline, not the unfinished player build.
- Preserve `b2bf830` for selective reuse after architecture approval; revised R6 explains the adaptation boundary.

## Latest checkpoint — renderer browser validation

- Resumed the existing Task 10 / issue #5 implementation and PR #18; no replacement plan or game/runtime implementation was generated.
- Real Wasm browser smoke check now passes all six groups: bundled font/world, keyboard movement/coin/release, jumping, actual tab blur/refocus, simultaneous touch movement/jump with cancellation, and tall/ultra-wide captures.
- The observed stuck-key regression is fixed through host-window and lifecycle input cleanup. The engine and Game Document are unchanged.
- Captures were inspected: readable Coins/Deaths HUD, live coin-counter updates, full viewport visible without cropping. Evidence: [media README](docs/media/README.md) and [machine-readable report](docs/media/renderer-smoke-report.json).
- Physical-device touch checks are still assigned to Task 11 / issue #6. Browser-emulated multitouch is labelled as such.
- `./gradlew spotlessApply` passed. The separate `./gradlew spotlessCheck allJvmAndWebTests :render:compileKotlinJs :render:compileKotlinWasmJs :render:compileAndroidMain` gate passed (`BUILD SUCCESSFUL`, 46 s); 17 renderer tests pass on JVM and Android host.
- Checkpoint commits: `9455b0f` records the handoff/plan index; `8a418df` contains the focus fix, reproducible browser check and visual evidence.
- Task 10 acceptance is demonstrated locally. [PR #18](https://github.com/aioanei/spawnkit/pull/18) records the latest remote checks and merge status; require its latest Linux/iOS checks to pass before the already-authorized merge.
- The next implementation task remains Task 11 / issue #6 from the approved plan, after PR #18 is integrated. No new task was started while renderer validation was unfinished.

## Starting checkpoint — 2026-11-19

- Active branch: `feat/5-gameview`; resumed from `f7a8887` with a clean working tree.
- Approved work: Task 10 of the [existing M0–M1 plan](docs/plans/2026-11-19-m0-m1-bootstrap-and-engine.md), GitHub issue #5.
- Open PR: [#18 — Compose GameView](https://github.com/aioanei/spawnkit/pull/18), draft, targeting `main`.
- Preserve the existing renderer, input handling, interpolation, bundled font, and tests. Finish its outstanding validation before starting Task 11 / issue #6.
- The owner has explicitly authorized merging PRs when ready. Required checks and acceptance criteria still apply.

## Completed work

- Bootstrap, conventions, CI, architecture and ADRs are present.
- PRs [#14](https://github.com/aioanei/spawnkit/pull/14), [#15](https://github.com/aioanei/spawnkit/pull/15), [#16](https://github.com/aioanei/spawnkit/pull/16), and [#17](https://github.com/aioanei/spawnkit/pull/17) are merged: Game Document, Platformer Adventure template, deterministic runtime, fixed-step clock and cross-target golden test. Issues #1–#4 are closed.
- Post-merge `main` CI passed: [Linux build](https://github.com/aioanei/spawnkit/actions/runs/44620243422) and [iOS simulator/framework](https://github.com/aioanei/spawnkit/actions/runs/44620243457).
- Renderer implementation in `f7a8887`: viewport/camera, keyboard and touch collection, cancellation cleanup, interpolation outside the simulation, respawn snapping, HUD and OFL-licensed Lilita One.

## Verified at the previous checkpoint

- `./gradlew spotlessApply`, then separately `./gradlew spotlessCheck allJvmAndWebTests localNativeTests :render:compileKotlinJs :render:compileKotlinWasmJs :render:compileAndroidMain` passed on Windows.
- 17 renderer tests passed on JVM and Android host. PR #18 [Linux build](https://github.com/aioanei/spawnkit/actions/runs/44618217247) and [iOS check](https://github.com/aioanei/spawnkit/actions/runs/44618217319) passed.
- A temporary Wasm preview compiled and bundled; the bundled font was included in its resources and matched its official Google Fonts source byte for byte.
- Preview launcher files are currently under ignored `build/render-preview/`. They are validation scaffolding, not a shipped player or creator feature.

## Outstanding work at the starting checkpoint

- Verify that HUD text actually renders on Wasm, exercise keyboard/focus and touch-button behavior, and capture UI evidence for PR #18.
- Previous browser inspection was stopped by Computer Use because it could not verify Chrome's current URL. Compilation alone does not demonstrate visual correctness.
- Record new findings and commands here, fix any defects exposed by validation, and commit checkpoints on the existing branch.
- Keep PR #18 as a draft until its remaining acceptance checks are demonstrated. Then follow the established checks, PR, and authorized merge workflow.

## Resumed validation findings

- Added the missing handoff files in checkpoint `9455b0f`, with `PLANS.md` pointing to the existing approved plan.
- The browser connector remains unavailable. An isolated headless Chrome process rendered the real Wasm preview successfully; the screenshot shows readable Coins/Deaths HUD text and the real platformer scene.
- Added a reproducible, dependency-free Node 22 browser check under `tools/render-smoke/`, plus a test-only preview entry point that reports read-only engine state. This does not change the creator's shipped entry point.
- Keyboard movement, actual coin collection, release and jumping passed. A real tab switch while holding movement exposed a stuck-key regression: internal Compose focus changes alone do not clear input when the host window loses focus.
- The regression check verifies that the document genuinely loses focus, then returns before checking simulation ticks because background tabs can suspend animation frames. It deliberately withholds key-up until after the stuck-key assertion.
- Source inspection of the exact Compose/Wasm dependency showed that its window-focus property is always true, while browser blur/visibility changes update its lifecycle. The fix observes both host window focus and lifecycle pause/stop/destroy, clearing input without changing engine state.
- `render` now explicitly depends on the already-catalogued JetBrains lifecycle runtime Compose library: Apache-2.0, maintained with Compose Multiplatform, and available on all UI targets. It provides the shared lifecycle owner needed for this browser/mobile input cleanup.
- The browser regression and full local gates now pass. The browser owns DOM canvas focus, so the integration test enters the canvas through a real tap rather than treating headless-window activation as a canvas focus event.

## Following tasks in the approved plan

- Task 11 / issue #6: exported-game shells for desktop, web, Android and iOS.
- Task 12 / issue #7: GitHub Pages deployment.
- Later milestones remain as defined in the approved design and GitHub issues; no replacement implementation plan has been created.
