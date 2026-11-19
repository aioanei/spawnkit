# M0–M1 Bootstrap & Engine Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A public, CI-verified SpawnKit repository whose deterministic Kotlin game runtime plays a hand-written platformer on Desktop, Web (Wasm) and Android (iOS compiled/tested in CI) — the D1–D4 "vertical-slice gate" of the design.

**Architecture:** Wizard baseline restructured into `build-logic` convention plugins + `core/*` (pure Kotlin, all targets incl. mingwX64/linuxX64) + `render` (Compose GameView) + `player/*` (exported-game shells) + existing `app/*` creator shells + `server`. Games are `GameProject` JSON documents compiled into an index-based `RuntimeWorld` and stepped at a fixed 60 Hz.

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.12.2 (material3 1.12.0-alpha03), AGP 9.1.1 (`com.android.kotlin.multiplatform.library`), Gradle 9.5.1, Ktor 3.6.1, kotlinx-serialization 1.13.0, kotlinx-coroutines 1.11.0, Spotless 8.11.0 + ktlint 1.8.0, detekt 2.0.0-alpha.6 (optional, see Task 2), kotlin.test.

**Spec:** `docs/design/2026-11-19-spawnkit-design.md` (approved plan; §5.1–5.3, §5.9, §7–§8, §10–§11 are the parts this plan implements).

## Global Constraints

- Root package `dev.spawnkit`; Android applicationIds `dev.spawnkit` (creator) and `dev.spawnkit.player` (player; overridable per game later).
- License Apache-2.0; README, docs, code comments in English.
- `explicitApi()` in every `core/*` module; official Kotlin code style enforced by Spotless+ktlint; `allWarningsAsErrors = true` in `core/*`.
- `core/*` targets: `jvm`, `android`, `iosArm64`, `iosSimulatorArm64`, `js { nodejs(); browser() }`, `wasmJs { nodejs(); browser() }`, `mingwX64`, `linuxX64`. Compose modules (`render`, `player/shared`, `app/shared`) drop `mingwX64`/`linuxX64`.
- Simulation: fixed 60 Hz (`TICK_RATE = 60`, `DT = 1.0 / 60`), only `+ − × ÷ √`, `floor`, `abs`, `min`, `max` on `Double` (no `pow`, trig, `exp`, `ln`); no wall-clock or platform calls inside `core/engine` simulation code.
- Units: 1 world unit = 1 tile; y axis points down; entity position = top-left of its AABB.
- Serialization: every polymorphic subtype has an explicit `@SerialName`; no property named `type`; `schemaVersion` on `GameProject`; JSON config `ignoreUnknownKeys = true`, `encodeDefaults = true`, `classDiscriminator = "kind"`.
- Never commit `.env`, `local.properties`, keystores or API keys; `.env` is in `.gitignore`.
- Commits: Conventional Commits, authored by the local git identity, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- After the Task 5 bootstrap push, every change goes through issue → `feat|fix|chore|docs/<issue>-<slug>` branch → PR → green `ci / build` → merge only with the user's authorization.

## Review Focus

1. **Frame hitch / backgrounded tab** delivering a multi-second delta → expect at most `MAX_TICKS_PER_FRAME = 5` steps, no spiral of death. Test in Task 9 (`FixedStepClockTest.clampsLargeDelta`).
2. **Player falls below the scene** → expect death + respawn at last checkpoint (or spawn), never an infinite fall. Test in Task 8 (`PlatformerPhysicsTest.fallingOutOfSceneRespawns`).
3. **Tunneling** through a 1-tile floor after a long fall → expect landing. Test in Task 8 (`PlatformerPhysicsTest.longFallDoesNotTunnelThroughFloor`).
4. **Future/unknown documents** (higher `schemaVersion`, unknown component `kind`) → expect a typed decode error, not a crash; unknown *keys* ignored. Test in Task 6 (`GameProjectJsonTest.rejectsNewerSchema`, `…unknownComponentKindIsTypedError`).
5. **Extreme window aspect ratios** (ultra-wide desktop, tall phone) → full virtual viewport visible, letterboxed, never cropped. Test in Task 10 (`ViewportTest`).

---

### Task 1: Repository skeleton from the wizard baseline

**Files:**
- Create (copied from verified wizard output at `…/scratchpad/wizard/gen/SpawnKit`, excluding `.idea/`, `local.properties`, `gradle/gradle-daemon-jvm.properties`, `build-trial.log`): `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `app/**`, `server/**`
- Delete after copy: `core/` (wizard sample), `app/shared/src/**/Greeting*.kt`, `GreetingUtil.kt`
- Create: `LICENSE` (Apache-2.0 full text, `Copyright 2026 aioanei`), `NOTICE`, `.editorconfig`, `README.md` (stub: pitch, status "under active development for the KMP Contest", build commands), `.gitignore` (wizard entries + `.env`, `*.jks`, `*.keystore`, `/recordings/private/`)

**Interfaces:**
- Produces: Gradle project `SpawnKit` with modules `:app:shared`, `:app:desktopApp`, `:app:webApp`, `:app:androidApp`, `:server`; `app/shared` temporarily depends on nothing from core.

- [ ] **Step 1:** `git init -b main` in the project directory; copy files; remove wizard sample code so `App.kt` shows a placeholder "SpawnKit" title screen (Material3 `Text`).
- [ ] **Step 2:** Verify: `./gradlew --console=plain :server:test :app:desktopApp:compileKotlin :app:webApp:compileKotlinWasmJs :app:androidApp:assembleDebug` → `BUILD SUCCESSFUL`.
- [ ] **Step 3:** Commit `chore: bootstrap SpawnKit from the official KMP wizard baseline`.

### Task 2: build-logic convention plugins and quality gates

**Files:**
- Create: `build-logic/settings.gradle.kts`, `build-logic/convention/build.gradle.kts`, `build-logic/convention/src/main/kotlin/` → `SpawnkitCorePlugin.kt` (id `spawnkit.kmp.core`), `SpawnkitComposePlugin.kt` (id `spawnkit.kmp.compose`), `SpawnkitQualityPlugin.kt` (id `spawnkit.quality`), `ProjectExt.kt` (`Project.spawnkitNamespace(): String` = `"dev.spawnkit." + path segments joined by "."`)
- Create: `config/detekt/detekt.yml` (only if detekt works, see Step 4)
- Modify: `settings.gradle.kts` (`pluginManagement { includeBuild("build-logic") }`), `build.gradle.kts` (apply `spawnkit.quality`), `gradle/libs.versions.toml` (add `kotlinx-serialization = "1.13.0"`, `kotlinx-collections-immutable = "0.5.2"`, `spotless = "8.11.0"`, `ktlint = "1.8.0"`, `detekt = "2.0.0-alpha.6"`, plugin `kotlinSerialization`)

**Interfaces:**
- Produces: plugin ids `spawnkit.kmp.core`, `spawnkit.kmp.compose`, `spawnkit.quality`; every later module's `build.gradle.kts` is ≤ 15 lines (plugins + dependencies).
- `spawnkit.kmp.core`: targets per Global Constraints, `explicitApi()`, `allWarningsAsErrors`, android namespace from `spawnkitNamespace()`, `commonTest` → `kotlin-test`.
- `spawnkit.kmp.compose`: same minus native desktop targets, plus compose + compose-compiler plugins and common Compose deps (runtime, foundation, ui, material3, components-resources).

- [ ] **Step 1:** Implement the three plugins; `spawnkit.quality` configures Spotless `kotlin { target("**/*.kt"); ktlint("1.8.0") }` and `kotlinGradle { target("**/*.kts"); ktlint("1.8.0") }`, excluding `**/build/**`.
- [ ] **Step 2:** Run `./gradlew spotlessApply` then `./gradlew spotlessCheck` → passes.
- [ ] **Step 3:** Migrate `app/shared` to `spawnkit.kmp.compose`; re-run Task 1 Step 2 command → `BUILD SUCCESSFUL`.
- [ ] **Step 4:** Add detekt 2.0.0-alpha.6 (`dev.detekt` plugin) with `buildUponDefaultConfig = true` on `core/*` once they exist; if it fails against Kotlin 2.4.21, remove it and record the decision in `docs/adr/0006-static-analysis.md` (ktlint + `allWarningsAsErrors` + `explicitApi` remain).
- [ ] **Step 5:** Commit `build: add convention plugins and Spotless/ktlint quality gate`.

### Task 3: CI workflows

**Files:**
- Create: `.github/workflows/ci.yml` (workflow `ci`, job `build`, `ubuntu-latest`), `.github/workflows/ios.yml` (workflow `ios`, job `ios-simulator`, `macos-latest`, never a required check)

**Interfaces:**
- Produces: required status check name **`build`** (from workflow `ci`), consumed by Task 5 ruleset.
- `ci.yml`: triggers `pull_request`, `push` to `main`; steps: checkout, setup-java (temurin 21), setup-gradle (current major, verified at implementation time via `gh api repos/<owner>/<action>/releases/latest`), `./gradlew --console=plain spotlessCheck allJvmAndWebTests :server:test :app:androidApp:assembleDebug :app:desktopApp:compileKotlin` where `allJvmAndWebTests` is an aggregate task registered by `spawnkit.quality` depending on every project's `jvmTest`, `jsNodeTest`, `wasmJsNodeTest`, `linuxX64Test`, `testAndroidHostTest` (when present); cache `~/.konan`.
- `ios.yml`: triggers `pull_request` (paths `core/**`, `render/**`, `player/**`, `build-logic/**`, `gradle/**`), `workflow_dispatch`; runs `./gradlew --console=plain iosSimulatorArm64Test` and (after Task 11) `:player:shared:linkDebugFrameworkIosSimulatorArm64`.

- [ ] **Step 1:** Write both workflows and the aggregate task.
- [ ] **Step 2:** Verify locally: `./gradlew --console=plain allJvmAndWebTests` → `BUILD SUCCESSFUL` (linuxX64 tests are skipped on Windows hosts; mingwX64 covered by `localNativeTests` aggregate = `mingwX64Test`, documented in AGENTS.md).
- [ ] **Step 3:** Commit `ci: add Linux build/test and macOS iOS simulator workflows`.

### Task 4: Project knowledge, ADRs and GitHub templates

**Files:**
- Create: `AGENTS.md`, `CLAUDE.md` (plain text: "Read AGENTS.md"), `docs/design/2026-11-19-spawnkit-design.md` (verbatim copy of the approved plan), `docs/ARCHITECTURE.md`, `docs/adr/0001-games-as-data.md`, `docs/adr/0002-serialization-and-schema.md`, `docs/adr/0003-ids-coordinates-assets.md`, `docs/adr/0004-determinism.md`, `docs/adr/0005-ai-writes-via-typed-commands.md`, `.github/pull_request_template.md`, `.github/ISSUE_TEMPLATE/task.yml`, `.github/ISSUE_TEMPLATE/bug.yml`, `CONTRIBUTING.md` (branch/commit/PR rules)

**Interfaces:**
- `AGENTS.md` sections (fixed): Product in one paragraph · Module map · Commands (build, test per target, run each app, `spotlessApply`) · Conventions (Global Constraints above) · Guardrails (never commit secrets; only main session edits `settings.gradle.kts`, `libs.versions.toml`, `core/model`; don't retry a failing gate blindly — diagnose; no live LLM calls in tests) · Workflow (issue → branch → PR → CI → user-authorized merge; how to pick the next issue: lowest open milestone, `priority:high` first) · Definition of done.
- PR template headings: Summary · Motivation · Implementation · Linked issues · Testing & verification · Platforms · Screenshots · Known limitations · Breaking changes.

- [ ] **Step 1:** Write files; ADR format = Context / Decision / Consequences, content taken from design §5.1, §5.9 and §13.
- [ ] **Step 2:** `./gradlew spotlessCheck` still green; commit `docs: add AGENTS.md, architecture, ADRs and contribution templates`.

### Task 5: Publish the repository (requires user confirmation)

**Files:** none (GitHub state only)

**Interfaces:**
- Produces: `https://github.com/aioanei/spawnkit` (public), labels, milestones, issues, ruleset on `main`.

- [ ] **Step 1:** Ask the user to confirm creation of public repo `aioanei/spawnkit` (and whether they grant standing authorization to merge green, reviewed PRs). Do not proceed without a yes.
- [ ] **Step 2:** `gh repo create aioanei/spawnkit --public --source . --remote origin --description "AI-powered no-code 2D game creation — a Kotlin Multiplatform app that builds Kotlin Multiplatform games." --push`.
- [ ] **Step 3:** Labels: `type:feat|fix|chore|docs|test`, `area:model|engine|validation|editing|render|player|editor|ai|server|ci|docs`, `priority:high|normal`. Milestones with due dates: M0 Bootstrap (Nov 20) · M1 Engine (Nov 23) · M2 Editor (Nov 27) · M3 Validation (Nov 27) · M4 AI Studio (Nov 30) · M5 Distribution (Dec 2) · M6 Hardening (Dec 5) · M7 Submission (Dec 7).
- [ ] **Step 4:** Issues: one per remaining task of this plan (Tasks 6–12, M1) with objective/requirements/acceptance criteria copied from the task, plus one epic issue per later milestone listing its design §7 must-haves.
- [ ] **Step 5:** Ruleset on `main` via `gh api -X POST repos/aioanei/spawnkit/rulesets`: require PR (0 approvals, dismiss stale reviews off), required status check `build`, block force-push and deletion, no bypass actors.
- [ ] **Step 6:** Confirm the first `ci` run on `main` is green (`gh run watch`); if red, fix on a `fix/` branch via PR.

### Task 6: `core/model` v1 — the Game Document

**Files:**
- Create module `core/model` (`build.gradle.kts`: `spawnkit.kmp.core` + `kotlinSerialization`; deps `kotlinx-serialization-json`)
- Create in `core/model/src/commonMain/kotlin/dev/spawnkit/model/`: `Ids.kt`, `GameProject.kt`, `Scene.kt`, `Tiles.kt`, `Entity.kt`, `Component.kt`, `Geometry.kt`, `Variables.kt`, `SpawnJson.kt`, `Migrations.kt`
- Test: `core/model/src/commonTest/kotlin/dev/spawnkit/model/GameProjectJsonTest.kt`, `TileLayerTest.kt`

**Interfaces (Produces):**
- IDs: `@JvmInline @Serializable public value class ProjectId(public val value: String)`; same for `SceneId`, `EntityId`, `PrefabId`, `AssetId`, `VariableId`, `LayerId`.
- `public const val CURRENT_SCHEMA_VERSION: Int = 1`
- `GameProject(schemaVersion: Int = CURRENT_SCHEMA_VERSION, id: ProjectId, meta: ProjectMeta, settings: GameSettings, tileset: Map<String, TileDef>, variables: List<VariableDef> = emptyList(), prefabs: List<Prefab> = emptyList(), scenes: List<Scene>)` — scene list order = level order.
- `ProjectMeta(name: String, description: String = "", version: String = "1.0.0")`; `GameSettings(viewportTiles: Size2 = Size2(20.0, 11.25), tilePixels: Int = 32, startScene: SceneId, physics: PhysicsSettings = PhysicsSettings())`; `PhysicsSettings(gravity: Double = 50.0, maxFallSpeed: Double = 20.0)`.
- `TileDef(kind: TileKind, color: ColorRgba, asset: AssetId? = null)`; `enum class TileKind { Empty, Solid, OneWay, Hazard, Decor }`; legend keys are single-character strings; `"."` and `" "` always mean `Empty`.
- `TileLayer(id: LayerId, name: String, rows: List<String>)` with `public fun TileLayer.charAt(x: Int, y: Int): Char` (`'.'` outside bounds) and `public fun validateRows(width: Int, height: Int): List<String>` (error messages).
- `Scene(id: SceneId, name: String, size: GridSize, background: ColorRgba, layers: List<TileLayer>, entities: List<EntityInstance>)`; `GridSize(width: Int, height: Int)`.
- `EntityInstance(id: EntityId, name: String, position: Vec2, prefab: PrefabId? = null, tags: Set<String> = emptySet(), components: List<Component> = emptyList())`; `Prefab(id: PrefabId, name: String, tags: Set<String> = emptySet(), components: List<Component>)`.
- `@Serializable public sealed interface Component` with subtypes (`@SerialName` in parentheses): `Visual` (`visual`: `size: Size2, color: ColorRgba, asset: AssetId? = null, shape: Shape = Shape.RoundedRect`), `Body` (`body`: `size: Size2, offset: Vec2 = Vec2.Zero`), `PlayerController` (`player`: `moveSpeed: Double = 8.0, jumpVelocity: Double = 17.0, coyoteTicks: Int = 6, jumpBufferTicks: Int = 6`), `Collectible` (`collectible`: `variable: VariableId, amount: Double = 1.0`), `Hazard` (`hazard`), `Patrol` (`patrol`: `speed: Double = 2.5, turnAtEdges: Boolean = true`), `Checkpoint` (`checkpoint`), `Goal` (`goal`).
- `Vec2(x: Double, y: Double)` (+ `Zero`), `Size2(width: Double, height: Double)`, `ColorRgba(argb: Long)` serialized as `"#AARRGGBB"` string via custom serializer; `enum class Shape { Rect, RoundedRect, Circle }`.
- `VariableDef(id: VariableId, name: String, initial: Double = 0.0, persistent: Boolean = false)`.
- `public object SpawnJson { public val json: Json; public fun encode(project: GameProject): String; public fun decode(text: String): DecodeResult }`; `sealed interface DecodeResult { data class Ok(val project: GameProject); data class Error(val message: String) }` — `decode` reads `schemaVersion` from the `JsonElement` first, runs `Migrations.migrate(element, from)` for older versions, returns `Error("Unsupported schemaVersion N (max 1)")` for newer, and wraps `SerializationException` into `Error`.

- [ ] **Step 1: Write failing tests** — `GameProjectJsonTest`: `roundTripIsLossless` (sample project with every component subtype → `decode(encode(p)) == Ok(p)`), `componentDiscriminatorIsKind` (encoded JSON contains `"kind":"player"`), `unknownKeysAreIgnored`, `rejectsNewerSchema` (`schemaVersion: 2` → `Error` whose message contains `"Unsupported schemaVersion 2"`), `unknownComponentKindIsTypedError` (`"kind":"laser"` → `Error`, no throw), `colorSerializesAsHex` (`ColorRgba(0xFF336699)` ↔ `"#FF336699"`). `TileLayerTest`: `charAtOutsideIsEmpty`, `validateRowsReportsWrongWidth` (row of 9 chars in width-10 scene → message contains `"row 0"`).
- [ ] **Step 2:** `./gradlew :core:model:jvmTest` → FAIL (unresolved references).
- [ ] **Step 3:** Implement the files above.
- [ ] **Step 4:** `./gradlew :core:model:jvmTest :core:model:wasmJsNodeTest :core:model:jsNodeTest :core:model:mingwX64Test` → PASS.
- [ ] **Step 5:** Commit `feat(model): add versioned GameProject document with polymorphic components`.

### Task 7: `core/templates` — embedded template documents

**Files:**
- Create: `templates/platformer-adventure/project.json` (hand-written: 3 scenes "Meadow", "Caves", "Sky Fort"; each ≥ 60×14 tiles with solid ground, gaps, one-way platforms, spikes, ≥ 8 coins, ≥ 2 patrol enemies, 1 checkpoint, 1 goal; variables `coins`, `deaths`; legend `#` solid, `=` one-way, `^` hazard, `~` decor)
- Create module `core/templates` (`spawnkit.kmp.core`, no deps); build-logic task `generateEmbeddedTemplates` (registered by a new `spawnkit.templates` plugin) that writes `build/generated/templates/commonMain/kotlin/dev/spawnkit/templates/EmbeddedTemplates.kt` and adds it to `commonMain`
- Test: `core/templates/src/commonTest/kotlin/dev/spawnkit/templates/EmbeddedTemplatesTest.kt` (module test depends on `core/model`)

**Interfaces (Produces):** `public object EmbeddedTemplates { public val all: Map<String, String> }` keyed by template directory name (`"platformer-adventure"`), values = raw JSON (generator emits raw strings split into ≤ 30 000-char chunks concatenated at runtime to respect JVM constant limits).

- [ ] **Step 1: Failing test** `platformerTemplateDecodesAndIsValid`: `SpawnJson.decode(EmbeddedTemplates.all.getValue("platformer-adventure"))` is `Ok`, has 3 scenes, every layer passes `validateRows`, each scene has exactly one entity with `PlayerController` and at least one with `Goal`.
- [ ] **Step 2:** Run `./gradlew :core:templates:jvmTest` → FAIL.
- [ ] **Step 3:** Implement generator + template JSON.
- [ ] **Step 4:** `./gradlew :core:templates:jvmTest :core:templates:wasmJsNodeTest :core:templates:mingwX64Test` → PASS.
- [ ] **Step 5:** Commit `feat(templates): embed Platformer Adventure template as generated sources`.

### Task 8: `core/engine` — compiler, platformer systems, state hash

**Files:**
- Create module `core/engine` (`spawnkit.kmp.core`; deps `:core:model`; test deps `:core:templates`)
- Create in `core/engine/src/commonMain/kotlin/dev/spawnkit/engine/`: `Input.kt`, `RuntimeWorld.kt`, `WorldCompiler.kt`, `GameSession.kt`, `Events.kt`, `systems/PlatformerSystem.kt`, `systems/TileCollision.kt`, `systems/PatrolSystem.kt`, `systems/InteractionSystem.kt` (collectibles, hazards, checkpoints, goal, enemy contact), `StateHash.kt`
- Test: `core/engine/src/commonTest/kotlin/dev/spawnkit/engine/` → `WorldCompilerTest.kt`, `PlatformerPhysicsTest.kt`, `InteractionTest.kt`, `TestLevels.kt` (tiny ASCII scenes built in code)

**Interfaces:**
- Consumes: `GameProject`, `Scene`, `Component` subtypes, `TileLayer.charAt`, `PhysicsSettings` (Task 6).
- Produces:
  - `public const val TICK_RATE: Int = 60`, `public const val DT: Double = 1.0 / 60`
  - `public enum class InputAction { Left, Right, Up, Down, Jump, Action }`; `@JvmInline public value class InputFrame(public val bits: Int)` with `isDown(InputAction)`, companion `of(vararg actions: InputAction)`, `NONE`.
  - `public sealed interface GameEvent { data class Collected(val entity: EntityId, val variable: VariableId, val amount: Double); data object PlayerDied; data class CheckpointReached(val entity: EntityId); data object LevelCompleted; data class EnemyDefeated(val entity: EntityId) }`
  - `public sealed interface CompileResult { data class Ok(val world: RuntimeWorld); data class Failed(val errors: List<String>) }`; `public object WorldCompiler { public fun compile(project: GameProject, scene: SceneId): CompileResult }` — merges prefab components with instance components (instance wins per `@SerialName`), requires exactly one `PlayerController`.
  - `public class RuntimeWorld` — read-only view: `tick: Long`, `entityCount: Int`, `x(i)`, `y(i)`, `width(i)`, `height(i)`, `isAlive(i)`, `entityId(i)`, `playerIndex: Int`, `variable(id: VariableId): Double`, `tileKind(x: Int, y: Int): TileKind`, `scene: Scene`, `levelCompleted: Boolean`, `isGrounded(i)`; internal mutable `DoubleArray`s for pos/vel.
  - `public class GameSession(project: GameProject, startScene: SceneId = project.settings.startScene)` — `world: RuntimeWorld`, `step(input: InputFrame): List<GameEvent>`, `restartLevel()`, `advanceToNextScene(): Boolean`, `stateHash(): Long`.
  - `public fun RuntimeWorld.stateHash(): Long` — FNV-1a 64 over `tick`, every alive entity's `x,y,vx,vy` via `toRawBits()`, alive flags, variable values, `levelCompleted`.
- Physics rules (exact): horizontal velocity = ±`moveSpeed` while Left/Right held, else 0; gravity `+gravity*DT` to vy, clamp `maxFallSpeed`; jump if (grounded or ticksSinceGrounded ≤ `coyoteTicks`) and jump pressed within the last `jumpBufferTicks` → `vy = -jumpVelocity`; releasing Jump while `vy < 0` → `vy *= 0.5` once; move X then resolve vs `Solid` tiles, move Y then resolve vs `Solid` and `OneWay` (one-way only when moving down and previous bottom ≤ tile top); `Hazard` tile overlap or `y > scene.height + 2` → `PlayerDied`, respawn at last checkpoint position (else original spawn), `deaths` variable += 1 if defined; player-vs-`Patrol` overlap: if player `vy > 0` and player bottom − DT·vy ≤ enemy top → enemy dies, player `vy = -jumpVelocity * 0.6`, `EnemyDefeated`; else `PlayerDied`. Patrol: moves `±speed`, reverses on solid tile ahead or (if `turnAtEdges`) no ground ahead.

- [ ] **Step 1: Failing tests** — `WorldCompilerTest`: `compilesPlatformerTemplateScene`, `instanceComponentOverridesPrefab`, `missingPlayerIsCompileError` (message contains `"PlayerController"`). `PlatformerPhysicsTest`: `playerFallsAndLandsOnGround` (after 120 idle ticks `isGrounded(player)` and `y + height == groundTop` within 1e-9), `holdingRightMovesEightTilesPerSecond` (60 ticks → Δx == 8.0 within 1e-9 on flat ground), `jumpReachesExpectedApex` (apex height ≈ 17²/(2·50) = 2.89 ± 0.2 tiles), `coyoteJumpAllowedWithinSixTicks`, `oneWayPlatformSupportsFromAboveOnly`, `longFallDoesNotTunnelThroughFloor` (drop from 40 tiles onto 1-tile floor → grounded on it), `fallingOutOfSceneRespawns` (emits `PlayerDied`, player back at spawn, `deaths == 1`). `InteractionTest`: `collectingCoinIncrementsVariableOnce`, `hazardTileKillsPlayer`, `checkpointChangesRespawnPoint`, `stompDefeatsPatrolSideContactKills`, `goalCompletesLevel`, `stateHashIsStableForSameInputs` (two sessions, same 600-tick input script → equal hashes), `stateHashDiffersForDifferentInputs`.
- [ ] **Step 2:** `./gradlew :core:engine:jvmTest` → FAIL.
- [ ] **Step 3:** Implement per the interfaces and physics rules.
- [ ] **Step 4:** `./gradlew :core:engine:jvmTest :core:engine:wasmJsNodeTest :core:engine:jsNodeTest :core:engine:mingwX64Test` → PASS.
- [ ] **Step 5:** Commit `feat(engine): add deterministic platformer runtime with world compiler and state hash`.

### Task 9: Cross-target determinism golden test and fixed-step clock

**Files:**
- Create: `core/engine/src/commonMain/kotlin/dev/spawnkit/engine/FixedStepClock.kt`, `core/engine/src/commonTest/kotlin/dev/spawnkit/engine/DeterminismGoldenTest.kt`, `core/engine/src/commonTest/kotlin/dev/spawnkit/engine/FixedStepClockTest.kt`, `core/engine/src/commonTest/kotlin/dev/spawnkit/engine/InputScripts.kt`

**Interfaces (Produces):** `public class FixedStepClock(public val maxTicksPerFrame: Int = MAX_TICKS_PER_FRAME) { public fun advance(frameNanos: Long): Int; public val alpha: Double }` (first call returns 0; `alpha` = leftover fraction for interpolation); `public const val MAX_TICKS_PER_FRAME: Int = 5`. `InputScripts.speedrunMeadow: List<InputFrame>` (≥ 900 frames, deterministic, built from segments like `hold(Right, 90) + tap(Jump) …`).

- [ ] **Step 1: Failing tests** — `FixedStepClockTest`: `firstFrameYieldsZeroTicks`, `sixtyHzFramesYieldOneTickEach` (16_666_667 ns steps → 1 tick each, cumulative 60 over 60 frames ±1), `clampsLargeDelta` (5-second delta → 5 ticks, accumulator reset). `DeterminismGoldenTest.meadowSpeedrunHashMatchesGolden`: run `speedrunMeadow` on scene 1 of the template → `assertEquals(GOLDEN_MEADOW_HASH, session.stateHash())` and `world.variable(coins) >= 3`.
- [ ] **Step 2:** Implement clock; run test on JVM to obtain the hash, set `GOLDEN_MEADOW_HASH` to that literal (record the derivation in the test KDoc).
- [ ] **Step 3:** `./gradlew :core:engine:jvmTest :core:engine:wasmJsNodeTest :core:engine:jsNodeTest :core:engine:mingwX64Test` → PASS on all (any mismatch = determinism bug to fix in engine, never by per-platform goldens). iOS + linuxX64 are verified by CI (`ios.yml`, `ci.yml`).
- [ ] **Step 4:** Commit `test(engine): add cross-target determinism golden and fixed-step clock`.

### Task 10: `render` — Compose GameView

**Files:**
- Create module `render` (`spawnkit.kmp.compose`; deps `:core:engine`); `render/src/commonMain/composeResources/font/` with one OFL font (e.g. Space Grotesk, downloaded from its official Google Fonts GitHub repo, `OFL.txt` committed alongside)
- Create in `render/src/commonMain/kotlin/dev/spawnkit/render/`: `Viewport.kt`, `Camera.kt`, `InputCollector.kt`, `GameView.kt`, `WorldPainter.kt`, `TouchControls.kt`, `Hud.kt`
- Test: `render/src/commonTest/kotlin/dev/spawnkit/render/ViewportTest.kt`, `CameraTest.kt`

**Interfaces:**
- Consumes: `GameSession`, `RuntimeWorld`, `InputFrame`, `FixedStepClock`, `GameEvent` (Tasks 8–9).
- Produces:
  - `public data class ViewportTransform(val scale: Float, val offsetX: Float, val offsetY: Float, val widthPx: Float, val heightPx: Float)`; `public fun computeViewport(containerW: Float, containerH: Float, virtualW: Float, virtualH: Float): ViewportTransform` (uniform scale = min ratio, centered letterbox).
  - `public fun cameraOrigin(targetX: Double, targetY: Double, viewW: Double, viewH: Double, sceneW: Double, sceneH: Double): Pair<Double, Double>` (centers target, clamps to scene bounds; centers scene if smaller than view).
  - `@Composable public fun GameView(session: GameSession, modifier: Modifier = Modifier, showTouchControls: Boolean = defaultShowTouchControls(), onEvents: (List<GameEvent>) -> Unit = {})` — `withFrameNanos` loop driving `FixedStepClock`, requests focus on start and on tap, keyboard (arrows/WASD, Space/W/Up = Jump), touch buttons, draws tiles (only visible range) and entities with interpolation, HUD showing every variable named in `GameSettings` HUD order (coins, deaths) using the bundled font.
  - `public expect fun defaultShowTouchControls(): Boolean` (true on Android/iOS, false on JVM/web unless touch detected later).

- [ ] **Step 1: Failing tests** — `ViewportTest`: `ultraWideLetterboxesHorizontally` (3440×1000 with 20×11.25 → scale 88.888…, offsetX > 0, offsetY == 0), `tallPhoneLetterboxesVertically` (1080×2400 → offsetX == 0, offsetY > 0), `exactAspectHasNoBars`. `CameraTest`: `clampsAtSceneLeftEdge`, `clampsAtSceneRightEdge`, `centersSmallScene`.
- [ ] **Step 2:** `./gradlew :render:jvmTest` → FAIL; implement; → PASS (also `:render:wasmJsNodeTest`).
- [ ] **Step 3:** Commit `feat(render): add Compose GameView with letterboxed viewport, camera, input and HUD`.

### Task 11: `player/*` — exported-game shells on Desktop, Web, Android, iOS

**Files:**
- Create modules: `player/shared` (`spawnkit.kmp.compose`; deps `:render`, `:core:templates`; iOS framework `baseName = "Shared"`, static), `player/desktopApp` (kotlinJvm + compose; `mainClass = "dev.spawnkit.player.MainKt"`, packageName `SpawnKit Player`), `player/webApp` (wasmJs + js `binaries.executable()`; `index.html` with full-viewport container `#game`), `player/androidApp` (`com.android.application`, `applicationId = "dev.spawnkit.player"`, landscape-capable, immersive full screen), `player/iosApp` (copy of wizard `app/iosApp` with build phase `./gradlew :player:shared:embedAndSignAppleFrameworkForXcode` and `PRODUCT_BUNDLE_IDENTIFIER=dev.spawnkit.player…`)
- Create: `player/shared/src/commonMain/kotlin/dev/spawnkit/player/PlayerApp.kt`, `PlayerState.kt`; platform entry points `main.kt` / `MainActivity.kt` / `MainViewController.kt`
- Modify: `settings.gradle.kts` (include player modules), `.github/workflows/ios.yml` (add framework link step)
- Test: `player/shared/src/commonTest/kotlin/dev/spawnkit/player/PlayerStateTest.kt`

**Interfaces:**
- Produces: `public sealed interface PlayerScreen { data object Title; data object Playing; data object LevelComplete; data object GameComplete }`; `public class PlayerState(project: GameProject) { val screen: StateFlow<PlayerScreen>; val session: GameSession; fun start(); fun onEvents(events: List<GameEvent>); fun next(); fun restart() }`; `@Composable public fun PlayerApp(project: GameProject)`; `public fun loadBundledProject(): GameProject` (decodes `EmbeddedTemplates.all["platformer-adventure"]`, throws `IllegalStateException` with the decode error message otherwise).

- [ ] **Step 1: Failing tests** — `PlayerStateTest`: `startsOnTitle`, `levelCompletedEventShowsLevelComplete`, `nextAdvancesSceneThenGameComplete` (3 scenes → after 3 completions `GameComplete`), `restartResetsCoins`.
- [ ] **Step 2:** Implement; `./gradlew :player:shared:jvmTest` → PASS.
- [ ] **Step 3:** Verify each platform: `./gradlew :player:desktopApp:run` (manual: title → play Meadow with keyboard → collect coin → reach goal), `./gradlew :player:webApp:wasmJsBrowserDevelopmentRun` (same in Chrome), `./gradlew :player:androidApp:installDebug` on the API-36 emulator (touch controls). Capture one screenshot per platform into `docs/media/`.
- [ ] **Step 4:** Commit `feat(player): add exported-game shells for desktop, web, android and iOS`.

### Task 12: GitHub Pages deployment of the web player

**Files:**
- Create: `.github/workflows/pages.yml` (on push to `main` + `workflow_dispatch`; builds `./gradlew :player:webApp:wasmJsBrowserDistribution`; uploads `player/webApp/build/dist/wasmJs/productionExecutable` with `actions/upload-pages-artifact`; deploys with `actions/deploy-pages`)
- Modify: `README.md` (add "Play in your browser" link `https://aioanei.github.io/spawnkit/`)

- [ ] **Step 1:** Enable Pages with GitHub Actions source: `gh api -X POST repos/aioanei/spawnkit/pages -f build_type=workflow`.
- [ ] **Step 2:** Merge the PR (with user authorization); verify the deployment job is green and the URL loads the player and the Meadow level is playable in Chrome and Firefox.
- [ ] **Step 3:** Commit `ci: deploy the Wasm player to GitHub Pages` (part of the PR).

---

## Self-review notes
- Spec coverage for D1–D4: repo/license/CI/AGENTS/ADRs (Tasks 1–5), model + templates (6–7), engine + determinism (8–9), GameView (10), player on desktop/web/android + iOS CI (11), Pages (12). Rules VM, editor, validation, AI, server publishing and Brainrot Tycoon are deliberately in the next plan (M2+).
- Type names used across tasks: `GameProject`, `SceneId`, `SpawnJson.decode → DecodeResult`, `WorldCompiler.compile → CompileResult`, `GameSession.step(InputFrame): List<GameEvent>`, `FixedStepClock.advance`, `computeViewport`, `cameraOrigin`, `GameView`, `PlayerState`, `EmbeddedTemplates.all` — consistent.
