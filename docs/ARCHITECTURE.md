# SpawnKit Architecture

This document describes the system as built. Decisions and their reasons live in [ADRs](adr/); the product vision
and roadmap live in the [design document](design/2026-11-19-spawnkit-design.md).

## Big picture

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

## Layers

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

## Determinism

The simulation uses only IEEE-754 operations that give identical results on every Kotlin target. A golden test runs
a scripted input sequence through a template level and compares `RuntimeWorld.stateHash()` with a constant on JVM,
JS, Wasm, mingwX64, linuxX64 and the iOS simulator. See [ADR-0004](adr/0004-determinism.md).

## Status

| Area | State |
|---|---|
| Build, CI, conventions | done (M0) |
| Document model, platformer engine, determinism clock and golden test | merged (M1, PRs #14–#17) |
| Renderer: viewport, camera, keyboard/touch, interpolation, HUD | implemented; browser smoke check and visual evidence in [PR #18](https://github.com/aioanei/spawnkit/pull/18) (M1) |
| Exported-game shells and browser deployment | next (M1) |
| Editor, validation, AI Studio, publishing | planned (M2–M5) |

The targeted [renderer browser smoke check](../tools/render-smoke/README.md) runs an isolated headless Chrome
against the real Wasm preview. It checks keyboard/coin/jump behavior, actual tab focus loss, simultaneous touch
points and cancellation. Captures and the last verified report are in [media](media/README.md).
