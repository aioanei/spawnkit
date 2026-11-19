# SpawnKit — revised engine foundation and delivery plan

Status: **proposed; implementation paused pending owner review**, 2026-11-20.

Design: [general-purpose engine revision](../design/2026-11-20-general-purpose-engine.md). Decision record: [ADR-0006](../adr/0006-general-purpose-engine-and-editor.md).

This revises the approved plan in response to the owner's explicit architecture change. It preserves completed work and all product commitments. It replaces the remaining ordering of [M0–M1](2026-11-19-m0-m1-bootstrap-and-engine.md) after review; it does not restart the repository. Checkbox items below are future acceptance gates, not claims of existing functionality.

## 1. Starting checkpoint and constraints

- Mainline: `5353e2a`, including merged PR #18. Its Linux and iOS PR/post-merge checks passed.
- Existing runtime/model/template/renderer code is the platformer compatibility baseline. Its test evidence remains valid for that baseline.
- Paused Task 11 / issue #6: local branch `feat/6-player-shells`, checkpoint `b2bf830`. Six JVM player-state tests and `spotlessApply` passed. The full gate was interrupted and platform acceptance remains unfinished. Preserve this work; adapt it at R6 instead of discarding it or treating it as a generic engine API.
- Proposal branch: `docs/6-engine-architecture`, based on main, containing documentation changes only.
- Kotlin Multiplatform, Compose Multiplatform, Koog and Ktor remain mandatory. Retain current pinned build/toolchain versions unless a separately justified compatibility change is necessary.
- Keep all current core targets: JVM, Android, JS, Wasm, iOS arm64/simulator, mingwX64 and linuxX64; Compose UI omits native desktop targets. Preserve headless engine execution.
- No gameplay implementation, new dependencies or architectural migration until the owner reviews this proposal. Documentation/research/verification of the proposal can continue.

## 2. Decisions to review

1. Scene/component authoring over an open, explicitly scheduled runtime, with storage kept behind APIs; no mandatory player or genre loop.
2. Common portable behavior programs with real computation and registered engine calls; event sheets and later node graphs share semantics.
3. A KMP developer SDK for components, behaviors, systems and editor integrations; compile trusted native packages into target builds initially.
4. One actual editor operation/transaction surface for UI, Koog and extensions; isolated play instances and real-engine validation.
5. Persistence adapters and dependency-locked packages; existing JSON supported as a codec/import format, not the engine contract.
6. Generality proved with multiple distinct games and a separate extension consumer before calling the foundation complete.

No library/backend choice is asserted without the compatibility evidence in R1. Selecting a specific physics library or language runtime is not required to approve these boundaries.

## 3. Incremental implementation sequence after approval

Each R-step becomes a focused issue or small issue group with objective, requirements, acceptance criteria and an appropriate milestone. Preserve dependency order. A failed gate is diagnosed; do not restore the old platformer constraints to meet a date.

### R0 — review and reconcile the architecture

- [ ] Owner reviews this proposal and records accepted decisions/changes. This is the blocker for implementation.
- [ ] Mark ADR-0006 accepted only after that review; reconcile affected ADRs and agent instructions without rewriting historical rationale.
- [ ] Reconcile existing GitHub issues/milestones: keep #6 and #7 open, mark their implementation as dependent on the new foundation, and link replacement/child issues. Do not silently close work as delivered.
- [ ] Preserve checkpoint branches, legacy template fixtures and test evidence. Confirm a clean baseline before migration.

Exit: the team has one current implementation direction; no agent can accidentally resume the superseded Tasks 11–12 sequence.

### R1 — prove runtime, program and hosting boundaries

Use bounded, disposable spikes on an approved feature branch. Produce a decision note with actual commands/results; do not create an alternative production runtime alongside the selected one.

Budget recommendation: one development day for comparative evaluation, then a review if key evidence is still missing. Reject candidates first on required target/headless support, open registration, state capture/order and execution interruption; only survivors need integration/performance measurements. Use tiny common fixtures, not three production-ready prototypes. End with one selected implementation path and explicit evidence gaps; extend evaluation only by a recorded scope decision.

- [ ] Define minimal entity/component lifecycle, type registration, phase ordering, snapshot and engine-operation interfaces, independent of files and Compose.
- [ ] Compare a simple component store and Fleks using one common contract: dynamic create/destroy/attach/remove, stable iteration, external types, replay/snapshot and representative entity counts. Publish license/maintenance/dependency/toolchain findings before adoption.
- [ ] Check whether using KorGE facilities actually satisfies Compose embedding, multiple preview worlds, headless tests and required targets. Choose it only if the evidence improves delivery over the existing renderer; record failures precisely rather than declaring it impossible in advance.
- [ ] Compare a small typed program interpreter with credible embeddable-language options. Require functions, state, branching/iteration, typed host calls, budget interruption, source diagnostics, supported targets and deterministic behavior. Prove atomic segment commit/rollback, explicit yield state and deterministic host-call charges. Use one executor for all no-code frontends; no JVM-only scripting fallback disguised as multiplatform support.
- [ ] Demonstrate a player-free world created directly through APIs and a component supplied by a separate consumer module. File loading is absent from this proof.
- [ ] Demonstrate the proposed path on JVM, JS/Wasm Node and Native; verify Android/iOS compilation through the established CI where necessary.

Exit: select and record the storage/program/backend implementation; name unsupported evidence gaps. Re-estimate the following work from measured results. Do not build the editor against an unproven closed schema.

### R2 — general world, resources and execution

Suggested boundary: evolve `core/engine` incrementally while retaining an explicit legacy entry path. No empty placeholder modules.

- [ ] Generation-checked handles, entity/component lifecycle, optional hierarchy, resource references and scene-instance identity.
- [ ] Open component/behavior factories; typed property/function/event metadata using explicit common Kotlin registration.
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

Build one executable programming foundation and a minimal editor vertical slice together.

- [ ] Typed program representation, user state/records/collections, functions/parameters/returns, conditions, iteration, events, timers and engine calls.
- [ ] Type checking/preparation, execution budgets, continuation state and source-to-runtime diagnostics; failures cannot hang the host.
- [ ] An actual workspace object model and editor transactions for entity/component/property/behavior edits, with preconditions, undo/redo and rollback.
- [ ] A small Compose hierarchy/inspector and event/function editing surface using those operations. Play instantiates a separate world; step/inspect/stop is functional.
- [ ] Implement a mechanic not present in the platformer using user-authored functions and queries, without adding a new engine opcode for that mechanic.

Tests: functions/loops/collections, custom event payloads, invalid typing, recursion/loop/allocation/event exhaustion, snapshot/replay of suspended logic, segment rollback, transaction failure rollback, undo equivalence and play-state isolation. Wall-clock watchdog aborts are inconclusive, not deterministic faults. Test correlation/deduplication and ordered replay of external service results. UI verification includes edit → Play → inspect → Stop → undo.

Exit: creators can author executable custom behavior rather than select from a fixed mechanics menu. A later graph UI builds on these same semantics.

### R5 — editor depth, persistence and third-party SDK proof

- [ ] Retain the approved hierarchy/assets/viewport/inspector/rules/issues/console layout, design system, keyboard access, device preview, selection, gizmos, pan/zoom, tile painting, copy/paste and asset import.
- [ ] Reusable scene/prefab authoring, composition and explicit instance state; event-sheet editing over the program runtime; debugger traces and extension-provided inspection.
- [ ] Directory/repository persistence interfaces, per-resource codecs, stable IDs, autosave/recovery, import/export and dependency locks. Browser storage is explicitly bounded; later OPFS/cloud sync remains planned.
- [ ] v1 `GameProject` import path and deliberate migrations; no silent reinterpretation or data loss when a package is unavailable.
- [ ] Developer package registration, capabilities, version/target compatibility, resource codecs/migrations and editor hooks.
- [ ] Separate consumer package, using only the public SDK, adds a component, behavior and editor operation. It appears in inspector/program catalog, participates in undo and can run in supported exports. Use first-party code as an external-consumer test, not a claim of an actual third-party marketplace seller.

Exit: extensions do not require changing a sealed core list, central inspector switch or shared serializer. Save/reload and runtime capabilities are independently tested.

### R6 — migrate demonstrations and resume player shells

- [ ] Move platformer controller/patrol/collection/checkpoint/goal/progression and procedural appearance into a demonstration package using the public SDK.
- [ ] Preserve the legacy baseline and meaningful behavioral regressions. Explain any deliberate semantics changes with focused tests; do not overwrite a golden simply to make migration pass.
- [ ] Build a top-down puzzle/action demonstration with no platformer-controller assumption and Brainrot Tycoon with UI/economy/character evolution. Freeze the engine surface across these proofs; identify any true missing primitive as an architecture finding.
- [ ] Resume `b2bf830` / issue #6 selectively. Reuse platform entry points, fonts and state tests; keep sequential adventure screens in the platformer demo. Generic player hosts a package's entry scene and UI/lifecycle.
- [ ] Verify desktop, Chrome/Wasm and Android emulator with screenshots; link iOS framework and run simulator tests in CI. Preserve physical-device checks as explicit outstanding evidence where unavailable.

Exit: all three distinct demos run on the same engine without genre-specific core edits; generic players can run a non-platformer scene. No mocked/replayed gameplay presented as real.

### R7 — validation and Koog capability integration

- [ ] Expose capability discovery, real workspace inspection, registered editing operations, behavior authoring and runtime test/debug tools through Koog class-based tools.
- [ ] Stage edits against a workspace revision, execute in the real engine, return traces/issues and an undoable diff; accepted transactions commit and rejected/cancelled work is discarded.
- [ ] Preserve Level Designer, Character Forge, Game Architect, procedural no-LLM creation, bounded repair, transparent media generation, cost/history/cancel and recording/replay executors.
- [ ] General integrity/type/dependency/budget checks plus registered scenario validators. Preserve reachability and economy validation as domain packages, with honest coverage/inconclusive results for arbitrary programs.
- [ ] Show an agent constructing new behavior and using the external-consumer extension through the same operations as the UI.

Tests: UI/tool operation parity; stale revisions; rollback; cancellation; replay with live validators; capability restrictions and unsupported packages; known bad scenarios; deterministic execution on supported targets. No live model/image calls in CI. Real-key smoke and recording remain manual evidence, with replay labelled.

Exit: the AI interacts with the engine/editor, authors executable behavior and validates execution; it is not merely filling a serialized game schema.

### R8 — publishing, exports and commercial package foundations

- [ ] Resume issue #7 Pages deployment only when generic players and required demos are ready; preserve static arcade and no-key replay accessibility.
- [ ] Ktor publish/list/get and validation use installed/approved package versions, program validation and resource limits. Separate build/execution workers from the public API process before accepting untrusted native packages.
- [ ] Publish/export resolves content, programs and exact runtime dependencies. Default packages reuse players; compiled extensions trigger compatible platform builds. Per-game Android identity, desktop distribution, iOS framework and release/cloud-build workflow remain.
- [ ] Package manifests/locks cover content, portable logic, compiled runtime and editor extensions separately. Preserve licensing, AI provenance, migrations, moderation and compatibility semantics.
- [ ] Define asynchronous monetization/publishing/analytics host-service interfaces with explicit results; no fabricated revenue, purchases or ad completion. Keep test modes labelled.
- [ ] Retain accounts/sync, server-hosted agents/credits, analytics/social arcade, cloud builds/signing, self/managed publishing, marketplace sales/payouts, ads/consent/IAP and reporting in subsequent delivery increments. Do not imply storefront, payments or native hot-loading already exists.

Exit: reproducible exports and a real publishing slice; documented target and package availability; no data-format workaround offered as proof of app-store compliance.

### R9 — hardening and submission

- [ ] Performance/allocation checks on representative scenes and budgets; accessibility and input/focus/device checks; no reflection-only path on Native/web.
- [ ] Full supported local and CI gates, deterministic conformance, clean-clone run instructions, documentation/SDK examples and shared-code measurement.
- [ ] Clearly separate shipped features, replay modes, prototype limitations and future commercial features in README and demos.
- [ ] Preserve final screencast/essay/tag/submission workflow and owner actions. Reconfirm the schedule using actual gate completion; no assertion of Unity/Godot feature parity.

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

## 5. Schedule and scope gates

The original date constraint remains 7 December 2026, 23:59 CET; this document does not re-verify contest eligibility/rules. The former day-by-day delivery estimates are superseded because the scope premise changed.

- After R1: publish measured feasibility and estimates; choose whether the contest slice fits while preserving the commercial roadmap.
- After R4: reassess based on a running general engine/editor/programming slice, before committing to breadth.
- Contest minimum recommendation: the generality and extension proofs, a real editable behavior, real-engine Koog validation, desktop/web/Android playback, iOS CI, Pages, honest replay and clear docs. The broader approved AI/demo/publishing features remain scheduled work; any change to their contest priority or dates needs explicit review, not silent deletion.
- Defer performance sophistication, freeform graph polish and native hot-loading before reducing engine generality. Avoid a fixed platformer skeleton as the fallback definition of success.

## 6. Workflow and verification

Use issue → branch → incremental tests/checkpoint commits → focused PR → green `build` and non-red iOS → owner-authorized merge. Do not bypass branch rules. New libraries require a recorded license, maintenance and target-support reason. Credit actual authors only.

Before pushing implementation, run formatting separately from tests:

```text
./gradlew spotlessApply
./gradlew spotlessCheck allJvmAndWebTests
./gradlew localNativeTests
```

The last command is required on Windows when `core/*` changes. Add focused platform compilation/framework/UI checks appropriate to each gate. Do not use local `build` or `allTests`. Documentation-only review verifies links, status consistency and whitespace; do not report an interrupted implementation run as a passed gate.

Architecture definition of done: the six proofs in [design §10](../design/2026-11-20-general-purpose-engine.md#10-proof-of-the-architecture) are demonstrated, new APIs documented, no genre assumptions in generic contracts, and existing valid work preserved.
