# SpawnKit progress

## Current checkpoint — 2026-11-19

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

## Unfinished work being resumed

- Verify that HUD text actually renders on Wasm, exercise keyboard/focus and touch-button behavior, and capture UI evidence for PR #18.
- Previous browser inspection was stopped by Computer Use because it could not verify Chrome's current URL. Compilation alone does not demonstrate visual correctness.
- Record new findings and commands here, fix any defects exposed by validation, and commit checkpoints on the existing branch.
- Keep PR #18 as a draft until its remaining acceptance checks are demonstrated. Then follow the established checks, PR, and authorized merge workflow.

## Following tasks in the approved plan

- Task 11 / issue #6: exported-game shells for desktop, web, Android and iOS.
- Task 12 / issue #7: GitHub Pages deployment.
- Later milestones remain as defined in the approved design and GitHub issues; no replacement implementation plan has been created.
