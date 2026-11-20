# AGENTS.md — working on SpawnKit

Instructions for coding agents (Codex, Claude Code, Junie, Copilot, …) and humans. Read this file fully before changing code.

## Product in one paragraph

SpawnKit is a general-purpose, extensible 2D game engine built with Kotlin Multiplatform, with an integrated
Compose Multiplatform no-code editor, Koog agents, Ktor services, publishing, monetization and a marketplace.
Creators and agents use real engine/editor capabilities to build and validate games; developers can add reusable
components, behaviors and tools without modifying engine source. Persistence formats are implementation details.
The existing document-driven platformer is a migration baseline and demonstration, not the general engine contract.
Current architecture and creation requirements: [general-purpose revision](docs/design/2026-11-20-general-purpose-engine.md).
Plan/status: [PLANS.md](PLANS.md), [PROGRESS.md](PROGRESS.md).

## Current direction and implementation checkpoint

The owner confirmed engine ownership and guided creation for non-programmers, removed time pressure as an
architectural constraint, then authorized merging plan PR #20 and starting implementation. R0 reconciliation is
complete. R1a / issue #21 and R1b / issue #23 are merged. R1c / issue #26 (storage decision) and R1d / issue #28 ([portable program executor](docs/runtime-programs.md)) are
merged, so R1 is complete. R2a ([descriptors](docs/runtime-descriptors.md)) and R2b ([hierarchy, resources and random](docs/runtime-hierarchy.md)) are
merged, as is R2c (descriptor-derived program properties). R2d ([deterministic input](docs/runtime-input.md)) and R2e (input functions for rules) are merged.
Current work is R2f / issue #40: [scene templates and instance identity](docs/runtime-hierarchy.md).
The architecture reassessment is open for owner review in PR #25 and is not merged autonomously. The visual
editor remains unfinished. Do not resume old Tasks 11–12 or treat unselected libraries as approved.
Preserve completed work and player checkpoint `b2bf830` on `feat/6-player-shells`. JSON remains allowed for
storage/transport; its schema and a closed list of gameplay mechanics cannot define engine capability.

- SpawnKit owns the runtime, gameplay APIs, behavior execution, editor capabilities and extension SDK. Focused
  libraries are allowed; another complete engine cannot become the foundation.
- Guided visual creation is the default: blank projects or optional examples, drag-and-drop objects, friendly
  behaviors and visual When / If / Do rules. AI, coding and technical IDs are not prerequisites.
- Basic/advanced tools, agents and extensions share one executable model. Metadata must generate usable property
  controls/behavior forms for installed capabilities; custom inspectors are optional enhancements.
- Contextual help, previews, undo/redo, autosave/recovery, explainable errors, keyboard access and scalable text
  are acceptance requirements. Keep advanced graphs/types/settings progressively disclosed.
- Technical and novice-usability gates determine readiness. Contest deadlines and delivery speed do not justify
  restricting engine expressiveness, ownership or usability. See the revised plan for the four-of-five novice gate.

## Module map

| Module | Kind | Purpose |
|---|---|---|
| `build-logic/` | included build | convention plugins `spawnkit.kmp.core`, `spawnkit.kmp.compose`, `spawnkit.quality` |
| `core/model` | KMP, all targets | existing v1 document schema, IDs, serialization and migrations; legacy import boundary in the target design |
| `core/templates` | KMP, all targets | existing embedded template fixtures; preserve during migration |
| `core/engine` | KMP, all targets | preserved tested platformer runtime and compatibility regression baseline |
| `core/runtime` | KMP, all targets | provisional headless world/component/scheduler and explicit checkpoint/replay APIs; no legacy model dependency |
| `core/program` | KMP, all targets | portable program executor: typed programs, type checking, resumable bytecode VM, atomic segments, checkpointed waits |
| `samples/runtimeProbe` | KMP, all targets | separately compiled public-API consumer and cross-target extension evidence |
| `benchmarks/runtimeStorage` | KMP executable (jvm, js, wasmJs, mingwX64, linuxX64) | unshipped runtime storage benchmark harness; public `World` API only |
| `core/validation` | KMP, all targets | integrity checks, reachability solver, playtest, economy check |
| `core/editing` | KMP, all targets | planned real workspace operations, undo/redo, transactions and changesets |
| `core/api` | KMP, all targets | client/server DTOs |
| `render` | KMP + Compose | `GameView`: Canvas renderer, camera, input, HUD |
| `ai` | KMP | planned Koog engine/editor capability tools, media providers, record/replay executors |
| `app/shared` + `app/*App` | KMP + Compose | creator app (editor, AI Studio, arcade) and platform entry points |
| `player/shared` + `player/*App` | KMP + Compose | exported-game shells (single game / arcade gallery) |
| `server` | Ktor (JVM) | publishing, arcade hosting, validation |

This table distinguishes existing responsibilities where affected; it is not a claim that planned modules exist.
Future boundaries are in the revision. Modules that do not exist yet are created by the milestone that
needs them; do not add empty placeholder modules.

"All targets" = `jvm`, `android`, `iosArm64`, `iosSimulatorArm64`, `js`, `wasmJs`, `mingwX64`, `linuxX64`.
Compose modules omit `mingwX64` and `linuxX64`.

## Commands

| Task | Command |
|---|---|
| Format code | `./gradlew spotlessApply` |
| Lint gate (CI) | `./gradlew spotlessCheck` |
| All JVM / Node (JS, Wasm) / linuxX64 / Android-host / server tests | `./gradlew allJvmAndWebTests` |
| Native tests on Windows | `./gradlew localNativeTests` |
| One module, one target | `./gradlew :core:engine:jvmTest` (also `wasmJsNodeTest`, `jsNodeTest`, `mingwX64Test`) |
| iOS simulator tests (macOS only; CI job `ios`) | `./gradlew iosSimulatorArm64Test` |
| Creator app | `./gradlew :app:desktopApp:run` · `:app:webApp:wasmJsBrowserDevelopmentRun` · `:app:androidApp:installDebug` |
| Server | `./gradlew :server:run` |
| Runtime storage benchmark (not a test gate) | `./gradlew :benchmarks:runtimeStorage:jvmRun` · `jsNodeProductionRun` · `wasmJsNodeProductionRun` · `runReleaseExecutableMingwX64` |

Do not run `./gradlew build` or `allTests` locally: they include browser (Karma) and Apple tasks that are slow or
unavailable on most machines. Use the aggregates above.

## Conventions

- Root package `dev.spawnkit`; Kotlin official code style enforced by Spotless + ktlint (`.editorconfig`).
- `core/*` modules use `explicitApi()` and `allWarningsAsErrors`; every public declaration has KDoc.
- Existing simulation code (`core/engine`) is deterministic: fixed 60 Hz ticks, only `+ - * /`, `sqrt`, `floor`, `abs`,
  `min`, `max` on `Double`; no `pow`, trigonometry, `exp`, `ln`, wall-clock time, randomness without the seeded RNG,
  or platform calls. A cross-target golden hash test enforces this.
- Existing platformer units: 1 world unit = 1 tile; y axis points down; entity position is the top-left corner of
  its AABB. General engine units/transform facilities follow the revised plan when implemented.
- Serialization (existing document codecs): every polymorphic subtype has an explicit `@SerialName`; never name
  a property `type`; class discriminator is `kind`; bump `CURRENT_SCHEMA_VERSION` and add a `JsonElement` migration
  for breaking changes.
- UI, agents and plugins use actual typed editor operations and real-engine inspection/validation. Specs may be
  tool inputs, but do not limit agents to generating document JSON or predefined game configurations.
  Stage AI edits and commit only a user-accepted changeset against the expected workspace revision.
- Tests use `kotlin.test` in `commonTest` unless a platform API is under test. No live LLM/image API calls in tests;
  use the replaying executor and recorded fixtures.

## Guardrails

- Never commit secrets: `.env`, API keys, keystores, signing certificates. Keys are read from the environment.
- Only the coordinating session edits `settings.gradle.kts`, `gradle/libs.versions.toml` and `core/model` while
  parallel work is in progress; other agents request changes to those files.
- If a quality gate fails, read the failure and fix the cause. Do not retry blindly, weaken a test, add `@Ignore`,
  or disable a lint rule to get green.
- Never bypass required checks or branch rules; never force-push `main`; never merge without the owner's authorization.
- Do not present mocks, placeholders or replayed AI sessions as live functionality. Replay mode must stay labelled.
- Do not add dependencies without a reason recorded in the PR (license, maintenance, multiplatform support).

## Workflow

1. Continue from the documented checkpoint in the revised R0–R9 dependency order and the lowest open milestone
   on GitHub, `priority:high` issues first. Continue unfinished R1 evidence work before moving to R2.
   Each task has an issue with an objective, requirements and acceptance criteria.
2. Branch from `main`: `feat|fix|chore|docs|test/<issue-number>-<slug>`.
3. Test first where practical; keep commits small, using Conventional Commits (`feat(engine): …`).
4. Before pushing: `./gradlew spotlessApply`, then **separately** `./gradlew allJvmAndWebTests` (plus `localNativeTests`
   on Windows when `core/*` changed). Running Spotless in the same invocation as tests can race on report folders.
   When scripting gates, use `set -o pipefail` so a failing Gradle run is not hidden by `| grep`/`| head`.
5. Open a focused PR using the template; link the issue (`Closes #N`); include test output and screenshots for UI.
6. CI job `build` must be green. The `ios` job is advisory but must not be left red.
7. The repository owner authorizes merges.

## Definition of done

- Acceptance criteria of the issue met and demonstrated (test, screenshot or command output in the PR).
- `spotlessCheck` and `allJvmAndWebTests` green; new public API documented.
- Docs updated when behavior, commands or architecture changed (`docs/ARCHITECTURE.md`, ADRs, README).
- Known limitations stated honestly in the PR.
