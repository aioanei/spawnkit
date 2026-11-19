# SpawnKit progress

## Current checkpoint — architecture proposal for review (2026-11-20)

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
