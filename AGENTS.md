# AGENTS.md — working on SpawnKit

Instructions for coding agents (Codex, Claude Code, Junie, Copilot, …) and humans. Read this file fully before changing code.

## Product in one paragraph

SpawnKit is an AI-powered, no-code 2D game creation studio built with Kotlin Multiplatform. A game is a versioned
**Game Document** (`GameProject`, JSON). One deterministic Kotlin runtime compiles that document into a `RuntimeWorld`
and plays it in the editor preview, in the browser arcade, and in exported Android/iOS/desktop/web games. JetBrains
Koog agents create levels, characters and whole games by emitting small specs that deterministic code turns into the
same typed editing commands a human uses, then **validate their output with the real engine** before the user accepts
a diff. Full design: [docs/design/2026-11-19-spawnkit-design.md](docs/design/2026-11-19-spawnkit-design.md).
Current implementation plan: [docs/plans/](docs/plans/).

## Module map

| Module | Kind | Purpose |
|---|---|---|
| `build-logic/` | included build | convention plugins `spawnkit.kmp.core`, `spawnkit.kmp.compose`, `spawnkit.quality` |
| `core/model` | KMP, all targets | Game Document schema, IDs, serialization, migrations |
| `core/templates` | KMP, all targets | template documents embedded as generated sources |
| `core/engine` | KMP, all targets | world compiler, fixed-step deterministic simulation, rules VM |
| `core/validation` | KMP, all targets | integrity checks, reachability solver, playtest, economy check |
| `core/editing` | KMP, all targets | typed commands, undo/redo, changesets |
| `core/api` | KMP, all targets | client/server DTOs |
| `render` | KMP + Compose | `GameView`: Canvas renderer, camera, input, HUD |
| `ai` | KMP | Koog agents and tools, media providers, record/replay executors |
| `app/shared` + `app/*App` | KMP + Compose | creator app (editor, AI Studio, arcade) and platform entry points |
| `player/shared` + `player/*App` | KMP + Compose | exported-game shells (single game / arcade gallery) |
| `server` | Ktor (JVM) | publishing, arcade hosting, validation |

Modules that do not exist yet are created by the milestone that needs them; do not add empty placeholder modules.

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

Do not run `./gradlew build` or `allTests` locally: they include browser (Karma) and Apple tasks that are slow or
unavailable on most machines. Use the aggregates above.

## Conventions

- Root package `dev.spawnkit`; Kotlin official code style enforced by Spotless + ktlint (`.editorconfig`).
- `core/*` modules use `explicitApi()` and `allWarningsAsErrors`; every public declaration has KDoc.
- Simulation code (`core/engine`) is deterministic: fixed 60 Hz ticks, only `+ - * /`, `sqrt`, `floor`, `abs`,
  `min`, `max` on `Double`; no `pow`, trigonometry, `exp`, `ln`, wall-clock time, randomness without the seeded RNG,
  or platform calls. A cross-target golden hash test enforces this.
- Units: 1 world unit = 1 tile; y axis points down; entity position is the top-left corner of its AABB.
- Serialization: every polymorphic subtype has an explicit `@SerialName`; never name a property `type`; class
  discriminator is `kind`; bump `CURRENT_SCHEMA_VERSION` and add a `JsonElement` migration for breaking changes.
- The AI never writes document JSON directly: specs → typed commands → validation → user-approved changeset.
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

1. Pick work: the lowest open milestone on GitHub, `priority:high` issues first. Each task has an issue with an
   objective, requirements and acceptance criteria.
2. Branch from `main`: `feat|fix|chore|docs|test/<issue-number>-<slug>`.
3. Test first where practical; keep commits small, using Conventional Commits (`feat(engine): …`).
4. Before pushing: `./gradlew spotlessApply allJvmAndWebTests` (plus `localNativeTests` on Windows when `core/*` changed).
5. Open a focused PR using the template; link the issue (`Closes #N`); include test output and screenshots for UI.
6. CI job `build` must be green. The `ios` job is advisory but must not be left red.
7. The repository owner authorizes merges.

## Definition of done

- Acceptance criteria of the issue met and demonstrated (test, screenshot or command output in the PR).
- `spotlessCheck` and `allJvmAndWebTests` green; new public API documented.
- Docs updated when behavior, commands or architecture changed (`docs/ARCHITECTURE.md`, ADRs, README).
- Known limitations stated honestly in the PR.
