# SpawnKit Architecture

This document describes the system as built. Decisions and their reasons live in [ADRs](adr/); the product vision
and roadmap live in the [original design document](design/2026-11-19-spawnkit-design.md).

## Accepted target and documentation checkpoint

On 2026-11-20 the owner confirmed a SpawnKit-owned general-purpose engine, guided visual creation for people
without programming knowledge, optional AI and milestone-based delivery without contest-driven constraints.
The [new design](design/2026-11-20-general-purpose-engine.md), [revised sequence](plans/2026-11-20-engine-foundation-revision.md)
and [ADR-0006](adr/0006-general-purpose-engine-and-editor.md) record the accepted requirements. The R1a code below
implements an initial headless boundary; the full engine/editor is unfinished. R1 still selects focused dependencies using evidence.

The diagram/layers below describe the retained platformer baseline. Its mandatory player, closed component set,
fixed gameplay systems and document compiler are not the target general engine contract. See the revision's audit
and migration gates for what is retained, extracted or replaced. Unfinished player shells are preserved at `b2bf830`
on `feat/6-player-shells`; they remain separate from this implementation branch.

## First general runtime increment (R1a)

`core/runtime` now supplies provisional, headless world/component registration and lifecycle APIs, with a stable
phased system schedule and deferred structural edits. It has no dependency on the document model or platformer
engine. A separately compiled `samples/runtimeProbe` registers custom state and runs it through the public API.
The legacy compiler, physics, renderer and golden remain unchanged. See [runtime foundation](runtime-foundation.md)
for execution/failure contracts and an example that does not load JSON or require a player.

The [R1b checkpoint increment](runtime-checkpoints.md) adds explicit component/private-system capture, fresh-world
restore with entity-reference remapping and canonical replay fingerprints. Missing state contracts fail rather
than being silently omitted. Snapshots are runtime-scoped and in-memory; native hooks remain trusted.

The [R1c storage decision](runtime-storage.md) keeps a SpawnKit-owned store, replacing per-component hash maps
with sparse-set columns behind the unchanged `World` API. Fleks was rejected at screening for missing published
JS/iOS artifacts. A golden recorded on the original store, plus cross-target benchmarks, document equivalence and
measured results.

The [R1d program executor](runtime-programs.md) adds `core/program`: typed portable programs (functions, state,
lists, loops, queries, typed host calls, events, waits, effects) checked with source diagnostics and run by a
resumable bytecode VM. Execution is bounded by deterministic limits, each segment is atomic, and suspended waits
are checkpointed. QuickJS, Chasm and Luak failed required-target screening.

The [R2a descriptors](runtime-descriptors.md) add typed authoring metadata for components: value kinds,
constraints, defaults, help and visibility. They also add a runtime catalog, a headless generic inspector model and
validated, undo-ready edits, proven with a separately compiled extension component.
The [R2b increment](runtime-hierarchy.md) adds an ordered scene hierarchy, typed per-world resources and a
deterministic `SeededRandom`. All three are checkpointed, and existing hashes are unchanged.
In R2c, programs can bind component descriptors directly, so rule writes obey the same constraints as inspector
edits.
[R2d input](runtime-input.md) adds registered actions and axes, host-submitted per-tick input frames, edges, and
checkpointed, replayable input.

## General presentation, builder and persistence (C1–C5)

`core/graphics` provides general transforms, shapes, sprites, text, cameras and render lists. `render/RuntimeCanvas`
draws those lists in the builder and generic players. `core/gameplay` installs tag/number/contact/input facilities
and the standard rule blocks; it compiles friendly blocks to the same portable handlers used by advanced tools.

`core/editing` owns immutable project/scene/object/component/rule data, typed operations and undo/redo. `app/shared`
supplies the compact and expanded Compose builder: viewport, hierarchy, generated inspector, Rules panel and isolated
Play. C1–C4 are merged; [builder](builder.md), [editing](editing.md) and [gameplay](gameplay.md) document the slice.

C5 adds a separately versioned [project-file codec and recovery journal](project-persistence.md) in `core/editing`.
Platform adapters in `app/shared` supply local storage and Android/desktop file pickers. The builder decodes before
replacing a workspace, preserves missing-capability data, blocks unsupported execution, and autosaves authoring state.
World handles, platform permission handles and play state are not stored in portable project files.

This is still a subset of R4–R5. Per-resource storage, dependency locks, broader extension proof, complete language
authoring and the novice usability studies remain acceptance gates in the revised plan.

## Build system and release pipeline (Kotlin Toolchain)

[ADR-0008](adr/0008-kotlin-toolchain.md) makes the Kotlin Toolchain SpawnKit's primary build system.

- `project.yaml` lists the modules. Each module's `module.yaml` declares its product (`kmp/lib`, `android/app`,
  `jvm/app`, `wasm-js/app`) and applies templates from `toolchain/`.
- Local plugins in `plugins/` generate the embedded templates and run ktlint.
- Sources use the Toolchain layout, which Gradle also reads while the two builds coexist.
- Gradle remains the required CI check, plus numbered exceptions, until the K2 gate. See
  [Kotlin Toolchain](kotlin-toolchain.md) for verified evidence per target.

The same toolchain is the Release Manager's build engine for compiled releases. A Ktor build service generates a
release workspace (player template, validated game package, release profile, vetted release plugins) and builds it in
isolated remote workers. Creators, including phone-only creators, never install or operate it. Web export and
Arcade publishing keep using the prebuilt universal player. See the
[Release Manager build architecture](design/2026-11-20-release-manager-kotlin-toolchain.md). The build service is
planned (K5–K6), not implemented.

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
| Build, CI, conventions | done (M0); Kotlin Toolchain dual build K1 (all modules but benchmarks; Gradle still required in CI) |
| Document model, platformer engine, determinism clock and golden test | merged (M1, PRs #14–#17) |
| Renderer: viewport, camera, keyboard/touch, interpolation, HUD | merged [PR #18](https://github.com/aioanei/spawnkit/pull/18); browser smoke and visual evidence retained |
| Exported-game shells | unfinished checkpoint `b2bf830`, preserved for R6 adaptation |
| General runtime world/component/scheduler | R1a–R1c evidence: lifecycle, checkpoints/replay, selected sparse-set store; separate public-API consumer |
| Portable program executor | R1d proof slice in `core/program`; checkpointed waits, atomic segments, cross-target golden |
| Capability descriptors and inspector | R2a in `core/runtime`; generated Compose controls in the Android/desktop builder |
| Responsive builder and visual rules | C1–C4 merged: general presentation, typed operations, phone/desktop editor and executable blocks |
| Project files and recovery | C5: versioned workspace codec, atomic journal, Android/desktop pickers and explicit recovery |
| Engine/editor/programming/extension foundation | broader R1 comparisons and R2–R9 work remain unfinished |
| Guided creation, accessibility and novice-usability release gate | builder slice exists; complete novice study and remaining accessibility gates are not claimed |
| Browser deployment, editor, validation, AI Studio, publishing | preserved roadmap, revised R0–R9 dependency order |

The targeted [renderer browser smoke check](../tools/render-smoke/README.md) runs an isolated headless Chrome
against the real Wasm preview. It checks keyboard/coin/jump behavior, actual tab focus loss, simultaneous touch
points and cancellation. Captures and the last verified report are in [media](media/README.md).
