# SpawnKit progress

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
