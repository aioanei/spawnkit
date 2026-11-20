# SpawnKit Architecture

This document describes the system as built. Decisions and their reasons live in [ADRs](adr/); the product vision
and roadmap live in the [current design](design/2026-11-20-general-purpose-engine.md). The
[original design](design/2026-11-19-spawnkit-design.md) preserves historical requirements and rationale.

## Accepted target and documentation checkpoint

On 2026-11-20 the owner confirmed a SpawnKit-owned general-purpose engine, guided visual creation for people
without programming knowledge, optional AI and milestone-based delivery without contest-driven constraints.
The [new design](design/2026-11-20-general-purpose-engine.md), [revised sequence](plans/2026-11-20-engine-foundation-revision.md)
and [ADR-0006](adr/0006-general-purpose-engine-and-editor.md) record the accepted requirements. The R1a code below
implements an initial headless boundary; the full engine/editor is unfinished. R1 still selects focused dependencies using evidence.

The owner's latest request renews architecture review before further implementation. The
[alternatives reassessment](design/2026-11-20-engine-alternatives-review.md) and updated plan are proposed for review;
accepted requirements and merged R1a/R1b code are preserved. Further implementation waits for review and owner
direction to continue. The provisional simple store and native checkpoint APIs do not select a portable program
runtime or project persistence format.

The diagram/layers below describe the retained platformer baseline. Its mandatory player, closed component set,
fixed gameplay systems and document compiler are not the target general engine contract. See the revision's audit
and migration gates for what is retained, extracted or replaced. Unfinished player shells are preserved at `b2bf830`
on `feat/6-player-shells`; they remain separate from this implementation branch.

## Merged general runtime evidence (R1a and R1b)

`core/runtime` now supplies provisional, headless world/component registration and lifecycle APIs, with a stable
phased system schedule and deferred structural edits. It has no dependency on the document model or platformer
engine. A separately compiled `samples/runtimeProbe` registers custom state and runs it through the public API.
The legacy compiler, physics, renderer and golden remain unchanged. See [runtime foundation](runtime-foundation.md)
for execution/failure contracts and an example that does not load JSON or require a player.

The [R1b checkpoint increment](runtime-checkpoints.md) adds explicit component/private-system capture, fresh-world
restore with entity-reference remapping and canonical replay fingerprints. Missing state contracts fail rather
than being silently omitted. Snapshots are runtime-scoped and in-memory; native hooks remain trusted.

This is R1 evidence, not a completed backend selection, portable behavior executor or visual editor. Those remain
explicit acceptance gates in the revised plan.

## Retained platformer baseline

```
                 ┌──────────────────────── Game Document (GameProject JSON) ─────────────────────────┐
                 │  scenes · tile layers · entities/components · variables · rules · characters     │
                 └───────────────▲───────────────────────────────▲─────────────────────▲────────────┘
                                 │ typed commands                │ typed commands      │ load
             ┌───────────────────┴──────┐         ┌──────────────┴─────────┐   ┌───────┴─────────┐
             │ Creator app (Compose MP) │         │ AI Studio (Koog agents)│   │ Player / Arcade │
             │ editor · preview · undo  │◀─diff──▶│ spec → commands →      │   │ Android · iOS · │
             └────────────┬─────────────┘         │ validate → repair      │   │ desktop · web   │
                          │ compile               └──────────┬─────────────┘   └───────┬─────────┘
                          ▼                                  │ headless                │ compile
                 ┌────────────────────────────────────────────▼───────────────────────▼──────────┐
                 │ core/engine: WorldCompiler → RuntimeWorld → fixed 60 Hz deterministic step    │
                 │ core/validation: reachability, playtest bot, economy check (same engine)      │
                 └───────────────────────────────────────────────────────────────────────────────┘
```

## Legacy baseline layers

1. **Document** (`core/model`): immutable, serializable authoring data. Stable string IDs; `schemaVersion` with
   `JsonElement` migrations. Nothing in the document is executable code.
2. **Runtime** (`core/engine`): `WorldCompiler.compile(project, scene)` resolves IDs to array indices and merges prefab
   components into a mutable `RuntimeWorld`. `GameSession.step(InputFrame)` advances one
   1/60 s tick and returns `GameEvent`s. Rendering never mutates simulation state.
3. **Presentation** (`render`): `GameView` drives `FixedStepClock` from `withFrameNanos`, collects keyboard, mouse
   and touch input, and paints the world with Compose `Canvas` inside a letterboxed virtual viewport.
   Previous entity positions and interpolation live in the renderer; rendering never changes simulation state.
   The camera follows the interpolated player position, clamps to scene bounds and centers smaller scenes.
   Arrow keys/A/D move, Space/W/Up jump, S/Down and E/Enter map to down/action; Android and iOS show hold buttons
   by default. Internal focus changes, host window focus loss, and lifecycle pause/stop/destroy clear held input.
   The lifecycle path is necessary on Wasm, where Compose's window-focus value is constant. Pausing resets the
   clock and input; session replacement starts fresh.
   The HUD follows `GameSettings.hud` order and uses bundled OFL-licensed Lilita One, including on Wasm.
4. **Shells**: `player/*` (exported games) and `app/*` (creator) are thin platform entry points over shared UI.
5. **Services** (`server`): publish and serve game bundles; validate them with `core/validation` before accepting.

## Legacy determinism evidence

The simulation uses only IEEE-754 operations that give identical results on every Kotlin target. A golden test runs
a scripted input sequence through a template level and compares `RuntimeWorld.stateHash()` with a constant on JVM,
JS, Wasm, mingwX64, linuxX64 and the iOS simulator. See [ADR-0004](adr/0004-determinism.md).

## Status

| Area | State |
|---|---|
| Build, CI, conventions | done (M0) |
| Document model, platformer engine, determinism clock and golden test | merged (M1, PRs #14–#17) |
| Renderer: viewport, camera, keyboard/touch, interpolation, HUD | merged [PR #18](https://github.com/aioanei/spawnkit/pull/18); browser smoke and visual evidence retained |
| Exported-game shells | unfinished checkpoint `b2bf830`, preserved for R6 adaptation |
| General runtime world/component/scheduler | first R1a evidence implementation; separate public-API consumer |
| Declared native checkpoints, entity remapping and replay fingerprints | R1b merged in PR #24; runtime-scoped in-memory state |
| Engine/editor/programming/extension foundation | broader R1 comparisons and R2–R9 work remain unfinished |
| Architecture reassessment | proposed for review; further implementation waits for owner direction |
| Guided creation, accessibility and novice-usability release gate | required by revised plan; not implemented or user-tested |
| Browser deployment, editor, validation, AI Studio, publishing | preserved roadmap, revised R0–R9 dependency order |

The targeted [renderer browser smoke check](../tools/render-smoke/README.md) runs an isolated headless Chrome
against the real Wasm preview. It checks keyboard/coin/jump behavior, actual tab focus loss, simultaneous touch
points and cancellation. Captures and the last verified report are in [media](media/README.md).
