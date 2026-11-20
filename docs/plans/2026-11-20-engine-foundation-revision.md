# SpawnKit — revised engine foundation and delivery plan

Status: **accepted requirements; R0 complete, R1 evidence in progress**, 2026-11-20. The owner confirmed engine ownership, guided visual creation and milestone-based delivery, then authorized merging plan PR #20 and continuing implementation. The initial R1a lifecycle/scheduling increment does not complete R1 or the visual editor.

Design: [general-purpose engine revision](../design/2026-11-20-general-purpose-engine.md). Decision record: [ADR-0006](../adr/0006-general-purpose-engine-and-editor.md).

This revises the approved plan in response to the owner's explicit architecture change. It preserves completed work and all product commitments. It replaces the remaining ordering of [M0–M1](2026-11-19-m0-m1-bootstrap-and-engine.md); it does not restart the repository. Checkbox items below record acceptance gates, not claims of shipped functionality.

## 1. Starting checkpoint and constraints

- Plan's starting mainline: `5353e2a`, including merged PR #18 and its passing Linux/iOS checks. Implementation later branched from `eae2d3f`, the owner-authorized merge of plan PR #20.
- Existing runtime/model/template/renderer code is the platformer compatibility baseline. Its test evidence remains valid for that baseline.
- Paused Task 11 / issue #6: local branch `feat/6-player-shells`, checkpoint `b2bf830`. Six JVM player-state tests and `spotlessApply` passed. The full gate was interrupted and platform acceptance remains unfinished. Preserve this work; adapt it at R6 instead of discarding it or treating it as a generic engine API.
- Documentation branch: `docs/19-own-engine-no-code-plan`, based on main, containing documentation changes only; tracked by [issue #19](https://github.com/aioanei/spawnkit/issues/19).
- Kotlin Multiplatform, Compose Multiplatform, Koog and Ktor remain mandatory. Retain current pinned build/toolchain versions unless a separately justified compatibility change is necessary.
- Keep all current core targets: JVM, Android, JS, Wasm, iOS arm64/simulator, mingwX64 and linuxX64; Compose UI omits native desktop targets. Preserve headless engine execution.
- R0 reconciliation is complete. Continue R1 evidence under the established issue/PR workflow; the R1a code remains provisional. No specific library or production backend is approved without the complete gate's evidence.
- Engine ownership and creation without programming knowledge are equal requirements. Guided visual creation is the default, with optional examples and AI assistance. The contest deadline is not an architectural or product-scope constraint.

## 2. Accepted architectural and creation requirements

1. Scene/component authoring over an open, explicitly scheduled runtime, with storage kept behind APIs; no mandatory player or genre loop.
2. Common portable behavior programs with real computation and registered engine calls; event sheets and later node graphs share semantics.
3. A KMP developer SDK for components, behaviors, systems and editor integrations; compile trusted native packages into target builds initially.
4. One actual editor operation/transaction surface for UI, Koog and extensions; isolated play instances and real-engine validation.
5. Persistence adapters and dependency-locked packages; existing JSON supported as a codec/import format, not the engine contract.
6. Generality proved with multiple distinct games and a separate extension consumer before calling the foundation complete.
7. SpawnKit owns its runtime, gameplay APIs, behavior execution, editor capabilities and extension SDK. Supporting libraries implement focused facilities; another complete engine cannot become its foundation.
8. Creators start with a functional blank project or optional example, drag objects into scenes, add friendly behavior forms and build When / If / Do rules visually. AI, coding, technical IDs and file-format editing are not prerequisites.
9. Capability descriptors supply friendly names/help, defaults, units, constraints, reference pickers, examples and visibility levels. Generate usable property/behavior controls for built-in and extension capabilities.
10. Progress follows technical and novice-usability acceptance gates. Estimates inform sequencing; neither contest pressure nor development speed justifies narrowing engine capability or postponing basic usability.

No library/backend choice is asserted without the compatibility evidence in R1. Approval of these boundaries does not approve a specific physics library, storage backend or language runtime.

## 3. Incremental implementation sequence

Each R-step becomes a focused issue or small issue group with objective, requirements, acceptance criteria and an appropriate milestone. Preserve dependency order. A failed gate is diagnosed; do not restore the old platformer constraints to meet a date.

### R0 — review and reconcile the architecture

- [x] Record the owner's engine ownership, no-programming creation and milestone-based delivery requirements, including the selected guided visual workflow and authorization to implement this documentation revision.
- [x] Accept ADR-0006's architectural/creation boundaries and reconcile current design, plan and agent guidance without rewriting historical rationale. Backend selection remains R1 work.
- [x] Reconcile existing GitHub issues/milestones: #6–#13 now record R0–R9 dependencies and retain their original objectives; #6/#7 remain open. #21 tracks R1a. M1–M6 obsolete delivery dates were removed; the contest showcase date remains external to architecture decisions.
- [x] Preserve checkpoint branches, legacy template fixtures and test evidence. PR #20 was merged with explicit owner authorization; implementation branched from clean main at `eae2d3f`. Player checkpoint `b2bf830` remains separate.

Exit: the team has one current implementation direction; no agent can accidentally resume the superseded Tasks 11–12 sequence.

### R1 — prove runtime, program and hosting boundaries

First increment: [issue #21](https://github.com/aioanei/spawnkit/issues/21), [headless runtime evidence](../runtime-foundation.md).
It introduces provisional lifecycle/scheduling APIs and an independently compiled consumer; the whole R1 gate
remains open until storage, state-capture and program comparisons are demonstrated.

R1b / [issue #23](https://github.com/aioanei/spawnkit/issues/23) adds [declared native checkpoints/replay](../runtime-checkpoints.md):
component codecs, explicit private system state, allocator capture, fresh-world entity remapping and a single
cross-target fingerprint golden. Storage/program comparison and effect/budget proofs still keep R1 open.

Use bounded, disposable spikes on an approved feature branch. Produce a decision note with actual commands/results; do not create an alternative production runtime alongside the selected one.

Bound the evaluation by explicit evidence rather than elapsed days. Reject candidates first on required target/headless support, engine ownership, open registration, state capture/order and execution interruption; only survivors need integration/performance measurements. Use tiny common fixtures, not multiple production-ready prototypes. End with one selected implementation path and explicit evidence gaps. Do not shorten capability or usability requirements to meet a contest date.

- [ ] Define minimal entity/component lifecycle, type registration, phase ordering, snapshot and engine-operation interfaces, independent of files and Compose.
- [x] Compare a simple component store and Fleks using one common contract: dynamic create/destroy/attach/remove, stable iteration, external types, replay/snapshot and representative entity counts. Publish license/maintenance/dependency/toolchain findings before adoption. Done in R1c / [issue #26](https://github.com/aioanei/spawnkit/issues/26): Fleks 2.16 rejected at target screening (no published JS/iOS artifacts); SpawnKit-owned sparse-set store selected with baseline-golden equivalence and cross-target benchmarks — see [runtime storage](../runtime-storage.md).
- [ ] Evaluate focused rendering, collision or media facilities only where useful, including individually usable KorGE facilities if appropriate. Retain SpawnKit's world, execution semantics, behavior runtime, editor APIs and extension SDK; adoption of a complete third-party engine is excluded.
- [x] Compare a small typed program interpreter with credible embeddable-language options. Require functions, state, branching/iteration, typed host calls, budget interruption, source diagnostics, supported targets and deterministic behavior. Prove atomic segment commit/rollback, explicit yield state and deterministic host-call charges. Use one executor for all no-code frontends; no JVM-only scripting fallback disguised as multiplatform support. Done in R1d / [issue #28](https://github.com/aioanei/spawnkit/issues/28): QuickJS, Chasm and Luak fail required-target screening; the common-Kotlin executor in `core/program` is selected and proven — see [portable programs](../runtime-programs.md).
- [ ] Demonstrate a player-free world created directly through APIs and a component supplied by a separate consumer module. File loading is absent from this proof.
- [ ] Demonstrate the proposed path on JVM, JS/Wasm Node and Native; verify Android/iOS compilation through the established CI where necessary.

Exit: select and record the storage/program/backend implementation; name unsupported evidence gaps. Re-estimate the following work from measured results. Do not build the editor against an unproven closed schema.

### R2 — general world, resources and execution

Suggested boundary: evolve `core/engine` incrementally while retaining an explicit legacy entry path. No empty placeholder modules.

- [ ] Generation-checked handles, entity/component lifecycle, optional hierarchy, resource references and scene-instance identity.
- [ ] Open component/behavior factories; typed property/function/event metadata using explicit common Kotlin registration.
- [ ] Separate executable signatures from authoring presentation metadata: names, explanations, categories, defaults, units, constraints, reference pickers, examples, contextual help and basic/advanced visibility. No serializer- or genre-specific inspector switch. Progress: R2a / [issue #30](https://github.com/aioanei/spawnkit/issues/30) covers component property descriptors, a catalog, a generic inspector model and validated edits ([descriptors](../runtime-descriptors.md)); function/event descriptors and program-binding derivation remain.
- [ ] Ordered single-threaded scheduler with fixed-tick and presentation boundaries; event/structural command ordering; independent world instances.
- [ ] General input actions/axes/pointers, seeded RNG, timers, runtime inspection and scenario stepping.
- [ ] Snapshot/hash coverage for all new deterministic state; missing capabilities/dependencies return diagnostics.

Tests: empty world; no player; multiple controlled entities; create/destroy during callbacks; stale handles; cycle detection for parent/order dependencies; independent instances; stable registration/query/event order; deterministic repeat runs across targets. Include mid-run capture/restore/replay for an external behavior with private mutable state. Keep existing engine tests green through their compatibility adapter.

Exit: a game can create and execute arbitrary registered component/behavior assemblies without document decoding or modifying engine source.

### R3 — general 2D presentation and collision facilities

- [ ] Extract existing viewport, camera, input cancellation, fixed-step presentation and interpolation into generic runtime observations.
- [ ] Explicit camera selection; transforms; drawable/sprite and layer APIs; assets/font/animation resources; game-UI property/event binding.
- [ ] General collision shapes, filtering, overlap/raycast/contact APIs and configurable kinematic motion. Preserve existing collision algorithms where applicable.
- [ ] Document the initial axis-aligned physics capability and extension seam for advanced physics. Keep demo-specific controller and rendering semantics outside these APIs.
- [ ] Clear unit/coordinate contracts; preserve existing demo scale through its adapter.

Tests: scene without player/tile map; independent cameras; create/destroy renderable entities; contact/query semantics; rotated/scaled visual transforms; tall/wide viewport and focus/touch regressions. Do not claim unimplemented rotated rigid-body physics.

Exit: general 2D scenes render and interact without genre-specific tags or engine-wide gravity rules.

### R4 — portable custom behavior and first editor operations

Build one executable programming foundation and an early guided creation loop together. Beginner usability is validated before expanding editor complexity.

- [ ] Typed program representation, user state/records/collections, functions/parameters/returns, conditions, iteration, events, timers and engine calls.
- [ ] Type checking/preparation, execution budgets, continuation state and source-to-runtime diagnostics; failures cannot hang the host.
- [ ] An actual workspace object model and editor transactions for entity/component/property/behavior edits, with preconditions, undo/redo and rollback.
- [ ] A small Compose hierarchy/inspector and event/function editing surface using those operations. Play instantiates a separate world; step/inspect/stop is functional.
- [ ] Functional blank project, drag-and-drop object placement, visible selection, friendly behavior forms with defaults and visual When / If / Do rule controls. No mandatory template, code, expression string, technical ID or AI key.
- [ ] Complete the loop: place an object → add/edit a behavior → assemble/change a rule → Play → inspect the result → Stop → undo. For example, visually create a Score counter and a contact rule that increases it and removes an object.
- [ ] Scaffold ready-made behaviors through the same public engine/program APIs as advanced editing and extensions. Basic and advanced views must preserve and edit the same project; no separate beginner mechanics schema/runtime.
- [ ] Implement a mechanic not present in the platformer using user-authored functions and queries, without adding a new engine opcode for that mechanic.

Tests: functions/loops/collections, custom event payloads, invalid typing, recursion/loop/allocation/event exhaustion, snapshot/replay of suspended logic, segment rollback, transaction failure rollback, undo equivalence and play-state isolation. Wall-clock watchdog aborts are inconclusive, not deterministic faults. Test correlation/deduplication and ordered replay of external service results. UI verification includes edit → Play → inspect → Stop → undo.

Exit: the guided creation loop works without programming knowledge and can compose an unfamiliar mechanic. Record early usability observations and address blocking confusion before expanding editor complexity. A later graph UI builds on the same semantics.

### R5 — editor depth, persistence and third-party SDK proof

- [ ] Retain the approved hierarchy/assets/viewport/inspector/rules/issues/console layout, design system, keyboard access, device preview, selection, gizmos, pan/zoom, tile painting, copy/paste and asset import.
- [ ] Reusable scene/prefab authoring, composition and explicit instance state; event-sheet editing over the program runtime; debugger traces and extension-provided inspection.
- [ ] Optional skippable/resumable onboarding, contextual instructions/examples, immediate feedback and previews. Errors identify the affected object/behavior, explain the problem and offer a corrective action; technical diagnostics are optional.
- [ ] Keyboard navigation, visible focus, readable contrast and scalable text; progressive disclosure of advanced graphs, reusable functions, types and settings.
- [ ] Directory/repository persistence interfaces, per-resource codecs, stable IDs, autosave/recovery, import/export and dependency locks. Browser storage is explicitly bounded; later OPFS/cloud sync remains planned.
- [ ] v1 `GameProject` import path and deliberate migrations; no silent reinterpretation or data loss when a package is unavailable.
- [ ] Developer package registration, capabilities, version/target compatibility, resource codecs/migrations and editor hooks.
- [ ] Separate consumer package, using only the public SDK, adds a component, behavior and editor operation. It appears in inspector/program catalog, participates in undo and can run in supported exports. Use first-party code as an external-consumer test, not a claim of an actual third-party marketplace seller.
- [ ] Generate usable property controls and behavior forms for that extension from its descriptors without a custom inspector. Missing packages, failed edits and interrupted sessions preserve work through recovery/undo/autosave.

Exit: extensions do not require changing a sealed core list, central inspector switch or shared serializer; creators can use their capabilities visually. Save/reload, recovery and runtime capabilities are independently tested.

### R6 — migrate demonstrations and resume player shells

- [ ] Move platformer controller/patrol/collection/checkpoint/goal/progression and procedural appearance into a demonstration package using the public SDK.
- [ ] Preserve the legacy baseline and meaningful behavioral regressions. Explain any deliberate semantics changes with focused tests; do not overwrite a golden simply to make migration pass.
- [ ] Build a top-down puzzle/action demonstration with no platformer-controller assumption and Brainrot Tycoon with UI/economy/character evolution. Freeze the engine surface across these proofs; identify any true missing primitive as an architecture finding.
- [ ] Build and modify the demonstrations through the shared visual editor, including visual rules and editable ready-made behaviors. Verify the creation/recovery exercises across two mechanically different games and an extension-provided behavior without programming or AI assistance.
- [ ] Resume `b2bf830` / issue #6 selectively. Reuse platform entry points, fonts and state tests; keep sequential adventure screens in the platformer demo. Generic player hosts a package's entry scene and UI/lifecycle.
- [ ] Verify desktop, Chrome/Wasm and Android emulator with screenshots; link iOS framework and run simulator tests in CI. Preserve physical-device checks as explicit outstanding evidence where unavailable.

Exit: all three distinct demos run on the same engine without genre-specific core edits; generic players can run a non-platformer scene. No mocked/replayed gameplay presented as real.

### R7 — validation and Koog capability integration

- [ ] Expose capability discovery, real workspace inspection, registered editing operations, behavior authoring and runtime test/debug tools through Koog class-based tools.
- [ ] Stage edits against a workspace revision, execute in the real engine, return traces/issues and an undoable diff; accepted transactions commit and rejected/cancelled work is discarded.
- [ ] Explain proposed changes in terms of the user's objects, behaviors and rules, with runnable previews. AI remains optional throughout; users can complete ordinary visual creation and local play with no model credentials.
- [ ] Preserve Level Designer, Character Forge, Game Architect, procedural no-LLM creation, bounded repair, transparent media generation, cost/history/cancel and recording/replay executors.
- [ ] General integrity/type/dependency/budget checks plus registered scenario validators. Preserve reachability and economy validation as domain packages, with honest coverage/inconclusive results for arbitrary programs.
- [ ] Show an agent constructing new behavior and using the external-consumer extension through the same operations as the UI.

Tests: UI/tool operation parity; stale revisions; rollback; cancellation; replay with live validators; capability restrictions and unsupported packages; known bad scenarios; deterministic execution on supported targets. No live model/image calls in CI. Real-key smoke and recording remain manual evidence, with replay labelled.

Exit: the AI interacts with the engine/editor, authors executable behavior and validates execution; it is not merely filling a serialized game schema.

### R8 — publishing, exports and commercial package foundations

- [ ] Resume issue #7 Pages deployment only when generic players and required demos are ready; preserve static arcade and no-key replay accessibility.
- [ ] Ktor publish/list/get and validation use installed/approved package versions, program validation and resource limits. Separate build/execution workers from the public API process before accepting untrusted native packages.
- [ ] Publish/export resolves content, programs and exact runtime dependencies. Default packages reuse players; compiled extensions trigger compatible platform builds. Per-game Android identity, desktop distribution, iOS framework and release/cloud-build workflow remain.
- [ ] Guided export/publishing flows explain destination requirements, validate setup and show progress/actionable failures. External accounts/signing/consent may be required for a destination; preserve progress and do not require them for ordinary local creation or play.
- [ ] Package manifests/locks cover content, portable logic, compiled runtime and editor extensions separately. Preserve licensing, AI provenance, migrations, moderation and compatibility semantics.
- [ ] Define asynchronous monetization/publishing/analytics host-service interfaces with explicit results; no fabricated revenue, purchases or ad completion. Keep test modes labelled.
- [ ] Retain accounts/sync, server-hosted agents/credits, analytics/social arcade, cloud builds/signing, self/managed publishing, marketplace sales/payouts, ads/consent/IAP and reporting in subsequent delivery increments. Do not imply storefront, payments or native hot-loading already exists.

Exit: reproducible exports and a real publishing slice; documented target and package availability; no data-format workaround offered as proof of app-store compliance.

### R9 — hardening and submission

- [ ] Performance/allocation checks on representative scenes and budgets; accessibility and input/focus/device checks; no reflection-only path on Native/web.
- [ ] At least four of five first-time non-programmers independently create and modify a small playable game, correct a seeded rule/object mistake, undo, save/reopen and launch it. Built-in guidance is allowed; instructor intervention and AI assistance are excluded. Record actual results; do not mark an internal walkthrough as a completed user study.
- [ ] Verify creation exercises across two mechanically different games and an extension-provided behavior without coding, JSON editing, technical IDs or API keys. Test supported export flows separately, including explanations and recovery for any destination credentials/signing requirements. No time-to-completion deadline is imposed.
- [ ] Full supported local and CI gates, deterministic conformance, clean-clone run instructions, documentation/SDK examples and shared-code measurement.
- [ ] Clearly separate shipped features, replay modes, prototype limitations and future commercial features in README and demos.
- [ ] Preserve the optional contest screencast/essay/tag/submission workflow and owner actions as a showcase of the available release. Technical and usability gates determine product readiness; no assertion of Unity/Godot feature parity.

## 4. Existing GitHub work mapping

| Existing work | Revised disposition |
|---|---|
| #1–#5 / PRs #14–#18 | Completed legacy foundation, retained as regression evidence |
| #6 player shells | Paused, checkpointed; resume/adapt at R6 |
| #7 Pages | Keep open; dependent on R6/R8 generic player proof |
| #8 editor epic | R4–R5; executable capabilities and general behavior replace document-only editing |
| #9 validation epic | R7; general checks plus domain-specific validators |
| #10 AI epic | R7; same three roles, real capability tools and program authoring |
| #11 distribution epic | R8; target-aware extension builds and package compatibility |
| #12 hardening / #13 submission | R9; re-estimate dates after R1 and R4 |
| Commercial roadmap | Retained; add explicit component/behavior/editor extension distribution, not only asset fragments |

Changes to remote issues are proposed, not performed by this document. Existing merge authorization never substitutes for architecture acceptance, successful checks or completed acceptance criteria.

## 5. Capability and usability gates

The owner explicitly removed time pressure as an architectural constraint. The former contest-driven daily schedule, scope cut-lines and one-day evaluation limit are superseded. The contest remains a showcase opportunity; it cannot change engine ownership, expressiveness, beginner usability or the retained product roadmap. This document does not change or re-verify the contest's external rules/dates.

- After R1: record compatibility evidence and select focused implementations while retaining SpawnKit's engine contracts. Estimates describe measured work, not permission to reduce requirements.
- After R4: prove the guided create/edit/play/undo loop and address beginner confusion before expanding editor complexity.
- After R5–R6: prove extension-generated controls and different visually authored games on the same engine; preserve player and migration evidence.
- Before release at R9: demonstrate both the engine generality proofs and novice usability gate. A passing technical suite alone does not establish that users can create without programming knowledge.
- Native hot-loading, optimization sophistication and advanced graph polish can follow working foundations. Guided creation, recovery, accessibility and real engine extensibility are foundation requirements.

## 6. Workflow and verification

Use issue → branch → incremental tests/checkpoint commits → focused PR → green `build` and non-red iOS → owner-authorized merge. Do not bypass branch rules. New libraries require a recorded license, maintenance and target-support reason. Credit actual authors only.

Before pushing implementation, run formatting separately from tests:

```text
./gradlew spotlessApply
./gradlew spotlessCheck allJvmAndWebTests
./gradlew localNativeTests
```

The last command is required on Windows when `core/*` changes. Add focused platform compilation/framework/UI checks appropriate to each gate. Do not use local `build` or `allTests`. Documentation-only review verifies links, status consistency and whitespace; do not report an interrupted implementation run as a passed gate.

Definition of done: the six architecture proofs and usability release gate in [design §10](../design/2026-11-20-general-purpose-engine.md#10-proof-of-the-architecture) are demonstrated, new APIs documented, no genre assumptions in generic contracts, and existing valid work preserved. This documentation checkpoint does not mark runtime, editor or user-study gates complete.
