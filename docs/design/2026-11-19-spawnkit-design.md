# SpawnKit — Product & Architecture Design (approved 2026-11-19)

**Confirmed decisions (user, 2026-11-19):** eligible solo student entrant → contest-first scoping · product **SpawnKit**, GitHub repo `aioanei/spawnkit`, **public** · license **Apache-2.0** · AI providers **Anthropic (agents) + OpenAI (images)**, keys funded by user (est. $30–60 for dev + demo).

## 1. Context

We start from an empty repository. The goal is a commercially viable, AI-powered, no-code 2D game creation ecosystem built with Kotlin Multiplatform: a Compose Multiplatform creator app (visual editor + Koog-powered AI Studio), one shared KMP game runtime that powers in-editor preview, the browser arcade and standalone exported games, a Ktor backend, and later a marketplace, monetization and publishing services.

The first release targets the **Kotlin Multiplatform Contest (KotlinConf 2027 edition)** and must become the foundation of the commercial product, not a throwaway. **Hard constraint: submission deadline 7 Dec 2026, 23:59 CET — 18 days from today.** The plan therefore delivers a deep, polished vertical slice whose architecture already supports the full vision, and moves everything else to a phased roadmap.

## 2. Verified facts that shape the plan

### Contest (kotlinconf.com/contest, /contest-rules, Kotlin blog 2026-11)
| Topic | Fact |
|---|---|
| Window | 16 Sep 2026 → **7 Dec 2026 23:59 CET**; winners ~21 Dec; 3 winners (trip + KotlinConf 2027 ticket) |
| Eligibility | 18+, full-time student (or within 12 months), **individual only — no teams**, one entry |
| Originality | Created for the contest after the start date; template/"Hello World" projects and libraries excluded; 100% owned by entrant |
| Platforms | KMP, **≥ 2 platforms** |
| License | LICENSE file: Apache-2.0 / MIT / BSD-3 |
| Submission | GitHub link + 300-word essay (entrant's background, idea, tech) + README with run instructions for **all targets** and key features; English; 3–5 min screencast recommended |
| Judging | **40% creativity/novelty · 40% use of KMP / % shared Kotlin code · 20% Kotlin coding conventions**; ties → creativity |
| AI / starter kit | AI not restricted (encouraged); starter kit listed but not required → optional |

Past winners (2025–2026): mostly Android+iOS LLM apps with demo videos and judge-oriented READMEs; the strongest had real technical depth (MIDI synthesis, LAN protocol, realtime multiplayer). None was a creation tool, engine, or self-validating agent system → novelty headroom.

### Technology state (Nov 2026)
- Kotlin **2.4.21**, Compose Multiplatform **1.12.2** (Android/iOS/Desktop Stable, **Web/Wasm Beta**), AGP 9 (Android entry point must be its own module), Gradle 9. Recommended structure: per-platform app modules + shared modules; with server → `app/`, `server`, `core` (kotlinconf-app is the reference: `:core` shared with Ktor backend, Exposed, Wasm+JS-fallback web distribution).
- Runtime options: **KorGE** (MIT, community-maintained, cannot embed in Compose) ✗; **libGDX** (JVM-only backends, not KMP) ✗; **Compose Canvas runtime** (`DrawScope.drawImage(src,dst)` + `withFrameNanos`) proven by Kubriko; Wasm ≈ JVM speed on desktop Chrome/Firefox, weaker on Safari/iOS WebKit.
- Starter kit "Koko" (MIT): mobile-app/monetization template (Koin, Nav3, Room3, Firebase, paywalls, AdMob, fastlane), **no server, no Koog**, 38 agent skills mostly store/monetization.
- Primary development machine runs Windows (no macOS) → iOS is built and tested only on GitHub Actions macOS runners (free for public repos).

### AI stack
- **Koog 1.3.0** (Apache-2.0, stable since 1.0): `agents-core` publishes JVM, Android, iOS, JS, **wasmJs**. Stable: core, OpenAI/Anthropic clients, persistence, OTel. Beta: `koog-ktor`, MCP, planners, Google client.
- APIs used: `strategy {}` graphs, `nodeLLMRequestStructured<T>` + `StructureFixingParser`, `subgraphWithVerification` (→ `CriticResult`), `subgraphWithTask<In,Out>`, `parallel(...)`, class-based `Tool<Args,Result>` (annotation tools are JVM-only), `handleEvents {}`, `RetryingLLMClient`, custom `PromptExecutor`. Gaps: no image/audio generation, no cost calculation, no native human-in-the-loop interrupt; new models declared via `LLModel(...)`; Gemini structured-output bug #2308.
- Prices (per 1M in/out): Claude Sonnet 5.5 $2/$10, Haiku 5.5 from $0.10/$0.50. OpenAI gpt-image-2.x: **native transparent PNG**, gpt-image-2 1024² $0.006 low / $0.053 medium. PixelLab: pixel-art characters/animations/tilesets as sprite sheets (user owns output). ElevenLabs SFX/TTS: free plan non-commercial.
- Ownership: Anthropic assigns outputs to customer; OpenAI customer owns; Recraft/ElevenLabs/Scenario free tiers non-commercial → marketplace must store provider + plan provenance per asset.

### Platform policy
- **Apple 4.2.6**: template/app-generation services must not submit apps on behalf of clients; sanctioned alternative = **single "picker" binary hosting all client content**; 4.3 spam; 4.7 allows mini-games in a host app with filtering/reporting/age gating.
- **Google Play**: spam/repetitive content + minimum functionality policies; new personal accounts need 12 testers × 14 days; **Android developer verification** for sideloaded apps (4 countries since 30 Sep 2026, **global 2027**).
- Store APIs: Play Developer API uploads/tracks/listings (first app manual); App Store Connect API cannot create app records but supports build uploads and review submissions; iOS builds need macOS+Xcode.
- AdMob: official SDKs (Android Next-Gen, iOS 13.x), community KMP wrappers (`admob-cmp` Apache-2.0, `basic-ads` MIT), UMP consent for EEA/UK/CH, official test IDs; platform-owned account serving others' apps not clearly sanctioned → creator-owned accounts by default. Web: H5 Games Ads closed beta. Payouts: **Stripe Connect** (Paddle/Lemon Squeezy forbid marketplaces).

## 3. Product strategy & contest positioning

**Pitch:** *"A multiplatform app that builds multiplatform games — where AI agents don't just generate content, they playtest it."*

- **Novelty (40%)**: one Kotlin runtime is simultaneously editor preview, browser arcade, and exported Android/desktop/iOS game; Koog agents edit through the *same typed commands* as the human and **prove their output with the real engine** (simulation-based reachability, ghost playtest run shown on screen, economy check) before the user accepts a diff.
- **KMP (40%)**: model, engine, validation, editing commands, renderer, editor UI and **agent strategies/tools** all in `commonMain`; core modules also compiled for `mingwX64`/`linuxX64`/iOS and verified by a cross-target determinism hash test; the same Koog agent runs in-process on desktop and (replay mode) in the browser. README reports a measured shared-code percentage (with and without server).
- **Conventions (20%)**: official style via Spotless+ktlint, detekt zero baseline, `explicitApi()` in core modules, KDoc on public API, dedicated conventions pass before submission.

## 4. Starting point decision

**Fresh project from the official KMP wizard (Compose Desktop + Web/Wasm + Android + iOS + Ktor server), immediately restructured into the recommended `app/ · core/ · server` layout with `build-logic` convention plugins.** The downloaded archive is treated as untrusted (extracted in its own directory, inspected, then copied in). From the starter kit we adapt only ideas (AGENTS.md validation guardrails, quality-gate and UI-verification skill concepts, Spotless/CI patterns), with MIT attribution if any text/code is copied. We do not adopt its architecture: it is a Firebase/paywall mobile-app template without server or Koog, and its Firebase-centric shared code does not run on Wasm. Hand-rolling the multi-target Gradle setup would waste deadline days; the wizard gives a correct current baseline while the product itself is entirely original.

## 5. Architecture

### 5.1 Core principle: games are data, one runtime plays them all
A game is a versioned, serializable **authoring document** (`GameProject`): assets, scenes/levels (ASCII-encoded tile layers + entities), prefabs, components, variables, rules, character definitions (rarity, evolution stages), dialogue, HUD config, build identity, monetization placements. Pressing Play **compiles** it into a mutable, index-based **`RuntimeWorld`** (IDs → indices, expressions pre-parsed, static tile layers pre-rendered to an `ImageBitmap`); Stop discards it. No editing during play in v1. The document uses persistent maps keyed by stable IDs; selection/camera are editor state outside the document and outside undo.

This powers every pillar: editor preview (runtime in a Compose pane) · arcade (Wasm player loading a published bundle) · export (generic player + bundle + per-game identity → repackaging, no user compilation) · AI (agents emit specs → typed commands → validated by headless runtime) · marketplace (document fragments with schema-version compatibility) · store compliance (a data-loading "picker" host app is the Apple-sanctioned managed-publishing model).

### 5.2 Module map (Gradle, convention plugins in `build-logic/`)
```
core/model        commonMain  GameProject schema (kotlinx.serialization), IDs/temp refs, schemaVersion + JsonElement migrations
core/engine       commonMain  compile→RuntimeWorld, fixed-timestep loop, systems (platformer controller, tile+AABB collision,
                              triggers, collectibles, hazards, patrol, checkpoints/goal, timers, rules VM, expression evaluator),
                              seeded RNG, save state, robustness caps; input/audio/ads interfaces
core/validation   commonMain  integrity checks, simulation-based reachability (BFS over macro-actions run by the real engine),
                              playtest script generation, economy check, structured Issue reports
core/editing      commonMain  typed commands (apply/revert, groups), changesets with temp-ref resolution and diff summaries
core/api          commonMain  client⇄server DTOs
render            commonMain  Compose GameView: Canvas sprite renderer, camera, letterboxed virtual viewport, HUD widgets,
                              input mapping, reachability/ghost overlays; expect/actual audio (stretch)
ai                commonMain  Koog strategies, class-based tools over core/editing + core/validation, MediaProvider (OpenAI images),
                              LLM model/price table, recording & replaying PromptExecutors
app/designsystem  commonMain  SpawnKit visual identity + components
app/shared        commonMain  creator UI: dashboard, editor panes, AI Studio, publish flow, local arcade view
app/desktopApp · app/webApp (· app/androidApp later)                creator entry points
player/shared     commonMain  player shell: single-game mode + arcade gallery mode, save slots, ads bridge interface
player/androidApp · player/iosApp · player/desktopApp · player/webApp   exported-game entry points
server            JVM         Ktor: publish/list/get bundles, server-side validation, limits, rate limiting, static Wasm player
templates/        JSON + PNG  template documents, embedded into code via a build-logic generation task (also test fixtures)
docs/             ARCHITECTURE.md, adr/, design spec, user guide, demo script, essay draft
```
Core modules target JVM, Android, iOS (arm64 + simulator), wasmJs (node + browser), `mingwX64`, `linuxX64` — native targets give a local Windows signal for Kotlin/Native-only failures before the macOS CI job.

### 5.3 Runtime
- Fixed 60 Hz simulation, interpolated rendering; no wall clock or platform calls inside simulation.
- Determinism: only IEEE-exact operations (+ − × ÷ √) in simulation, own deterministic helpers instead of `pow`/trig; state hash over `toRawBits()`; golden-hash test on JVM, Wasm (node), mingwX64 and iOS simulator.
- Kinematic physics only (AABB vs tiles/AABBs, gravity, jump arcs, one-way platforms, triggers); rigid bodies later behind the same system interface.
- Rendering: `DrawScope.drawImage(src,dst)` sprite sheets, nearest-neighbour filtering for pixel art, frame animations + procedural tweens (bob/squash/flash) so single-frame AI art feels alive, bundled HUD font (Skiko on Wasm has no system fonts), no per-frame allocations.
- Coordinates: world unit = 1 tile, y-down, fixed AABB origin, virtual viewport scaled to fit with letterboxing (phone/tablet/desktop previews).
- Input: keyboard, mouse, touch + on-screen controls, mapped to abstract actions.
- Robustness caps: events per tick, entities per bundle, asset bytes per bundle; rule errors go to the console panel, never crash the player.

### 5.4 No-code gameplay
- **Event sheets ("When / If / Do")**: compact, beginner-friendly, diffable, easy for LLMs to emit as flat data. Triggers: start, every N s, tap/click entity or tag, collision tagA↔tagB, enter area, variable threshold, level complete/failed, timer, custom event, ad reward (later). Conditions: typed comparisons, flags, owned-count, random chance. Actions: change variable, spawn/destroy, swap prefab (evolution), unlock, show dialogue line, go to level, start timer, emit event, play sound/animation, show ad (later).
- **Expression language, capped**: numbers, variables, + − × ÷, comparisons, `min/max/floor/clamp`, `^` with integer exponents only (repeated multiplication); Pratt parser; dropdown-assisted editor with static type checks.
- **Genre composition without genre code**: Brainrot Tycoon = generic components (Clickable, Producer variables, character definitions with rarity + evolution stage sprites) + rules; Platformer = controller/collision components + rules. New genres are new component/rule combinations, not new engines.
- Later: story graphs, utility-AI "agentic characters" (personality-weighted considerations over actions, deterministic, authored by LLMs at design time; no runtime LLM by default).

### 5.5 Editor (Contest Edition)
- Creative-tool layout: left Hierarchy/Assets · center Viewport (pan/zoom, grid + snapping, select, move/resize gizmos, tile painter) · right Inspector (generic, built from `SerialDescriptor` + `@SerialInfo` hints; rotation and z-order as fields) · bottom dock Rules/Issues/Console · top Play/Stop/Restart + device preview.
- All mutations are `core/editing` commands → undo/redo, copy/paste/duplicate, autosave; AI changesets appear as a diff summary the user accepts, rejects, or edits.
- Storage: desktop = project folder (`project.json` + content-hashed PNGs); web = localStorage + file import/export (OPFS/IndexedDB post-contest).
- Distinctive dark "studio" identity built with the frontend-design skill; keyboard shortcuts; accessibility basics (contrast, focus order, scalable text, reduced motion).

### 5.6 AI Studio (Koog)
- Agent strategies and tools live in `ai` commonMain. **Contest: run in-process on desktop** with the user's own keys (env/`.env`), progress as a `Flow`, cancel via coroutine cancellation, per-job budget; approval is client-side (agent returns a `Changeset`; editor previews and applies). Server-hosted agents with persistence/checkpoints/SSE = post-contest.
- **LLMs never write raw document JSON or patches.** They produce small flat specs (e.g. `LevelSpec` as ASCII rows + legend, `CharacterSpec`), deterministic code converts specs → typed commands with temp refs (`@new:hero`), dangling refs are rejected before preview.
- Agents:
  1. **Level Designer** — constraints → `LevelSpec` → commands → `subgraphWithVerification` using the reachability solver + playtest → targeted repair (bounded loop) → ghost run + issue report → changeset. Also a **procedural (no-LLM) generator** feeding the same repair loop.
  2. **Character Forge** — prompt (e.g. "legendary shark × fighter jet, 3 evolutions") → `CharacterSpec` (name, rarity, personality, stage descriptions, income/evolution costs) → `parallel` transparent sprite generation per stage with a style reference → alpha trim → prefab + income/evolution rules → integrity + headless economy check (time-to-afford each stage within bounds) → changeset.
  3. **Game Architect** — prompt → plan (mechanic composition on top of a skeleton project) → runs Level Designer / Character Forge as subgraphs → global validation → playable project + report.
- Cross-cutting: token usage × price table → live cost, generation history, regenerate, `RetryingLLMClient`.
- **Honest demo mode**: a recording `PromptExecutor` captures real LLM/image responses; a replaying executor feeds them back through the *same* strategy while validators and repair run live; UI labels it "Replay recorded ‹date›, ‹model›". The same mechanism powers deterministic CI tests. Replay runs on desktop and in the browser (Koog `agents-core` on wasmJs).

### 5.7 Server (Contest Edition)
Ktor (JVM): publish/update/list/get game bundles, server-side validation with `core/validation`, bundle size/entity limits, Ktor RateLimit, per-game edit token returned at publish (no accounts yet), static hosting of the Wasm player at `/play/{slug}`. Storage behind `GameRepository` (filesystem now; PostgreSQL + S3-compatible storage later). Accounts, marketplace, AI job hosting = post-contest.

### 5.8 Distribution & export
- **GitHub Pages**: Wasm player + static arcade gallery of sample games + the web creator app (with replay demo mode) → judges try everything without a server or keys.
- **Bundle format**: `manifest.json` + content-hash-named PNGs (no zip in commonMain on Wasm).
- **Android**: `player/androidApp` with `-PgameBundle -PappId -PappName -PappIcon -PversionName` → per-game APK; `export-android.yml` (workflow_dispatch) demonstrates cloud builds.
- **Desktop**: `packageDistributionForCurrentOS` in a CI OS matrix (cross-compilation unsupported); README notes unsigned-macOS `xattr` step.
- **iOS**: macOS CI runs iOS simulator tests + links the player framework; simulator app build best-effort.
- Release workflow on tag publishes APK, desktop packages and Pages link.

### 5.9 ADRs written on D1–D2
ADR-0001 games-as-data + authoring/runtime split · ADR-0002 serialization (explicit `@SerialName` on every subtype, no property named `type`, `schemaVersion` from day 1, migrations as `JsonElement` transforms, `ignoreUnknownKeys`, explicit `encodeDefaults`) · ADR-0003 IDs, temp refs, coordinates, assets & bundle format · ADR-0004 determinism rules · ADR-0005 AI writes only via typed commands; replay demo mode.

## 6. Technology decisions
| Area | Choice | Rejected / why |
|---|---|---|
| Build | Kotlin 2.4.21, CMP 1.12.2, Gradle 9 + version catalog + `build-logic`, AGP 9 | CMP 1.13 RC, Amper (EAP) |
| Runtime | Own data-driven runtime (commonMain) + Compose Canvas renderer | KorGE (no Compose embedding), libGDX (not KMP), Kubriko (MPL, no headless determinism/data model, weakens originality) |
| Physics | Own kinematic AABB/tile physics | Box2D ports (unmaintained/no web, overkill) |
| Audio (stretch) | Thin expect/actual SFX layer | Gadulka (JVM needs JavaFX), korlibs-audio (alpha) |
| Serialization | kotlinx.serialization JSON | — |
| Client DI / nav | Koin 4.x; Navigation 3 for top-level destinations | Metro (fine later) |
| Persistence | Files (desktop), localStorage (web), multiplatform-settings for prefs | Room3 (not needed for documents) |
| Server | Ktor 3.x, filesystem `GameRepository` (PostgreSQL/Exposed/Flyway later) | Firebase/Supabase lock-in |
| Agents | Koog 1.3.x stable modules only, commonMain | `koog-ktor`/planner betas, hand-rolled loops |
| LLM | Anthropic Claude Sonnet 5.5 (plan/generate) + Haiku 5.5 (repairs/critique), OpenAI fallback | Gemini (bug #2308, price rises) |
| Images | OpenAI gpt-image-2.x (transparent PNG) behind `MediaProvider`; PixelLab adapter later | Nano Banana (no transparency) |
| Template art | Generated by our pipeline (owned outputs) with CC0 fallback (e.g. Kenney) + attribution file | unlicensed assets |
| Quality | Spotless+ktlint, detekt, `explicitApi()` in core | — |
| Tests | kotlin.test (JVM, Wasm-node, mingwX64, iOS sim), coroutines-test, Compose UI tests (desktop), Ktor `testApplication`, replaying executors | live LLM calls in CI |
| Ads (post-contest) | AdMob via `admob-cmp` or own expect/actual, UMP, official test IDs | simulated ads |
| Payments (post-contest) | Stripe Connect | Paddle / Lemon Squeezy |

## 7. Contest Edition scope

**Demo story:** prompt or template → AI + visual editing → play in editor (with live validation and ghost run) → publish to arcade → play in browser → same game on Android & desktop (iOS verified in CI).

**Must-have**
1. `core/model`, `engine`, `editing`, `validation` in commonMain with tests on JVM, Wasm, mingwX64, iOS simulator + cross-target determinism hash test.
2. Templates: **Platformer Adventure** (tiles, controller, collectibles, patrol enemies, hazards, checkpoint, goal) and **Brainrot Tycoon** built from generic components + rules (clicker, passive income, characters with rarity and evolution stages, upgrades).
3. Player on Web (Pages + arcade gallery), Desktop, Android (per-game build); iOS simulator tests + framework link in CI.
4. Creator app on Desktop + Web with the §5.5 editor.
5. Visible validation: Issues panel, reachability overlay, ghost playtest run.
6. AI Studio: Level Designer (verify/repair loop), Character Forge, Game Architect; progress, cancel, cost, accept/reject diff.
7. Replay demo mode + procedural no-LLM level path.
8. Server: publish/browse/play with validation and limits.
9. Release artifacts, judge-oriented README ("try it in 2 minutes" first), ARCHITECTURE.md, ADRs, screencast script, essay draft.

**Stretch (in order):** `gamecheck` validation CLI (JVM + native) → NPC dialogue list editor → SFX on desktop/web/Android → iOS simulator app build artifact → AI via server route for the web editor → merge mechanic → template-pack import (marketplace seed) → AdMob rewarded test ad on Android → hosted demo server (needs spend approval).

**Cut-line if behind (applied at D8 checkpoint):** stretch list → Game Architect reduced to Level+Character orchestration on a fixed skeleton → web creator app limited to replay demo + play → Android per-game build via documented command only. Never cut: runtime on ≥3 platforms, editor core, Koog validation loop, Pages arcade, tests/CI, README.

**Post-contest:** see §9 (accounts, marketplace, monetization, cloud builds, publishing, analytics, story/agentic characters, more genres, 3D later).

## 8. Schedule (solo entrant + coding agents; ≤2 parallel worktree tracks)
| Day | Date | Exit criteria |
|---|---|---|
| D1 | Nov 20 | Repo `aioanei/spawnkit` (public, Apache-2.0), wizard baseline restructured, build-logic, Spotless/detekt, Linux CI green, AGENTS.md, ADR-0001/0002, design spec committed, milestones + issues, branch rules |
| D2 | Nov 21 | `core/model` v1, ASCII tile layers, command skeleton, templates embedded as generated sources; **contract freeze #1**; iOS CI job (non-required) |
| D3 | Nov 22 | Engine: compile→RuntimeWorld, fixed timestep, controller, tile collision, collectibles, goal; determinism hash test JVM/Wasm/mingw |
| D4 | Nov 23 | GameView + input; hand-written platformer plays on desktop, web, Android; Pages deploy — **vertical-slice gate** |
| D5 | Nov 24 | Commands/undo/changesets; rules VM + expressions; Brainrot Tycoon plays |
| D6 | Nov 25 | Editor shell, design system, viewport, hierarchy, play/stop ‖ Track B: simulation-based reachability |
| D7 | Nov 26 | Inspector, tile painter, asset import, save/load ‖ Track B: ghost replay, Issues panel, server publish skeleton |
| D8 | Nov 27 | Event-sheet editor; **contract freeze #2** (AI command surface) ‖ Track B: Koog skeleton, recording/replaying executors — **scope checkpoint** |
| D9 | Nov 28 | Level Designer end-to-end (spec → commands → solver → repair → diff accept/reject) + procedural path |
| D10 | Nov 29 | Character Forge (transparent stage sprites → prefab + rules → economy check), cost display, cancel |
| D11 | Nov 30 | Game Architect; record demo sessions |
| D12 | Dec 1 | Publish → server validation → `/play/{slug}`; static arcade gallery on Pages |
| D13 | Dec 2 | Android per-game build + workflow; release workflow (APK, desktop packages); iOS app build best-effort |
| D14 | Dec 3 | Replay mode on desktop + web; top stretch item |
| D15 | Dec 4 | **Feature freeze**; bug bash on all targets, Wasm perf, accessibility |
| D16 | Dec 5 | Conventions/KDoc/de-duplication pass, shared-code metric, README, ARCHITECTURE.md, ADRs final |
| D17 | Dec 6 | Screencast recorded by user from script; essay finalized in user's own words; clean-clone README walkthrough |
| D18 | Dec 7 | Buffer; tag `v0.1.0-contest`; user submits form by midday CET |

## 9. Post-contest roadmap
1. **Creation depth**: story/dialogue graph editor, quests/inventory, utility-AI agentic characters, sprite/animation editor (PixelLab sheets), merge/idle/clicker/puzzle/story templates, game-UI editor, OPFS storage, tablet editor.
2. **Platform services**: accounts (OAuth), cloud project sync, PostgreSQL + S3-compatible storage, server-hosted agents with Koog persistence/checkpoints, credits and quotas.
3. **Arcade & analytics**: creator pages, follows, ratings/reviews, leaderboards, trending, moderation/reporting; own analytics ingestion (sessions, retention, level funnels) and crash reports — never fabricated data.
4. **Marketplace**: free packs → paid assets/templates via Stripe Connect with revenue share and payouts; licensing + AI-provenance metadata (provider, plan), compatibility by schema version, moderation.
5. **Builds & self-publishing**: cloud build workers (Linux/Windows/macOS), creator signing-key vault, icon/screenshot generation via headless renderer, AI store listings, privacy-policy assistant, readiness checks; automate via Play Developer API / App Store Connect API where allowed, guide manual steps (accounts, app records, agreements, closed testing).
6. **Monetization**: AdMob in exports with creator-owned accounts, UMP/TCF consent, age-appropriate settings, app-ads.txt hosting on creator pages, H5 Games Ads application for the arcade, revenue reporting via AdMob API / Play GCS exports / App Store Connect reports, premium versions/IAP.
7. **Managed publishing**: company "arcade host" app (picker model per Apple 4.2.6/4.7) on iOS/Android, submission review, agreements, revenue-share ledger — legal review first (Apple 2.5.2 data-vs-code question, Play policies).
8. **3D**: only after 2D product–market fit.

## 10. GitHub workflow
- Repo `aioanei/spawnkit`, public, created only after this plan is approved (gh is authenticated). Branch rules on `main`: PR required with **0 approvals** (a solo owner cannot approve their own PR), required check = fast Linux CI job only (iOS job never required — path-filtered required checks hang and macOS queues are slow), no force-push. Agents never bypass checks; **no merge without the user's authorization**.
- Each task: issue (objective, requirements, acceptance criteria) in a milestone (M0 Bootstrap · M1 Engine · M2 Editor · M3 Validation · M4 AI Studio · M5 Distribution · M6 Hardening · M7 Submission; ~20–25 issues) → branch `feat|fix|chore|docs/<issue>-<slug>` → Conventional Commits (authored by the user's git identity, with the Claude co-author trailer) → focused PR with a short template (summary, motivation, implementation, linked issue, test results, platforms, screenshots, limitations, breaking changes).
- Review: independent review subagent on substantial PRs, clearly labelled as AI review; no fabricated reviews, authors or CI results.
- Parallelism: max 2 worktree tracks at once (RAM); only the main session edits `settings.gradle.kts`, `libs.versions.toml`, and `core/model`; contract freezes on D2 and D8.
- CI: `ci.yml` (Linux: spotlessCheck, detekt, JVM + Wasm-node + linuxX64 tests, server tests, Android assemble, desktop compile), `ios.yml` (macOS: iOS simulator tests, framework link), `pages.yml`, `release.yml` (tag → APK, desktop OS matrix, Pages), `export-android.yml`. No Dependabot/Renovate until judging ends; secret scanning on.
- Persistent knowledge: `AGENTS.md` (architecture map, commands, conventions, guardrails, definition of done, how to pick the next issue — read by Codex/Claude/other agents), `CLAUDE.md` as a plain pointer file (no symlinks on Windows), `docs/ARCHITECTURE.md`, `docs/adr/`, this plan committed as the design spec; GitHub Issues/Milestones are the progress source of truth.
- After submission: freeze `main` until results (Dec 21); continue on a `develop` branch if needed.

## 11. Testing & quality
- Core: unit tests in `commonTest` on JVM, Wasm (node), mingwX64/linuxX64, iOS simulator; golden template documents; cross-target determinism hash.
- Validation: known-bad fixtures (unreachable goal, impossible gap, dangling refs, runaway economy) must be flagged; templates must pass.
- AI: strategies tested with replaying executors (tool flows, repair loops, budget, cancellation); no live calls in CI; manual smoke run with real keys before recording demos.
- UI: Compose desktop UI tests for select→move→undo, add rule, play/stop; screenshots in UI PRs.
- Server: Ktor `testApplication` (publish, validation rejection, limits).
- Definition of done: tests + Spotless + detekt green, docs updated, honest limitations in PR.
- Shared-code metric: build-logic task counting Kotlin LOC per source set, reported in README.

## 12. Commercial, compliance & cost
- **Open-core**: contest repo Apache-2.0; commercial moat = hosted services (AI credits, cloud builds, arcade network, marketplace), brand/trademark (SpawnKit name check before launch), content and community. Proprietary back-office services (billing, payouts, moderation, managed publishing) may later live in a separate private repo using public APIs.
- **Costs**: AI is the main variable cost (≈ $0.30/character, $0.05–0.20/level, $1.5–3/small game at list prices → credits, per-job budgets, Haiku for repairs, caching, replay for demos); storage/egress (zero-egress S3-compatible storage later); hosting (Pages free now; small VPS later); CI free while public; Apple $99/yr and Play $25 only when publishing.
- **Compliance**: GDPR, ad consent (UMP/TCF), families/children ad rules, AI provenance + commercial-rights metadata, arcade moderation/reporting, Apple 4.2.6/4.3/4.7 and Play spam policies shape publishing models.

## 13. Risks & mitigations
| Risk | Mitigation |
|---|---|
| 18-day deadline | Vertical-slice gate D4, scope checkpoint D8, feature freeze D4 Dec, cut-line §7; user's coursework time accounted for at D8 |
| Compose Wasm Beta, Safari/iOS WebKit quirks | Demo on Chrome/Firefox/Edge; simple allocation-free render path; document browser support honestly |
| Float determinism across targets | IEEE-exact ops only, own helpers, golden-hash test on every target |
| Koog beta modules / provider bugs | Stable modules only, pinned version, `AgentRuntime` seam, replaying executors |
| Low-quality or invalid LLM output | Flat specs → typed commands, fixing parser, real validators, bounded repair, human approval |
| Judges lack API keys | Replay demo mode + procedural path + Pages deployment |
| AGP 9 / IDE compatibility | Build via Gradle CLI + CI; recommend current IntelliJ IDEA / Android Studio |
| iOS untestable locally | mingwX64 native compile locally + macOS CI on PRs touching core/player |
| Secret leakage | `.env` gitignored, keys only in env/GitHub secrets, secret scanning, no keys in clients or bundles |
| Malicious published bundles | Data-only bundles, server-side validation, size/entity caps, rate limiting, CSP on player pages |

## 14. User inputs still needed during implementation
- Approval to create the remote repo and set branch rules (D1, one-time).
- `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` placed by the user in a local gitignored `.env` (never pasted into commits; CI needs none).
- Authorization for each PR merge.
- The 300-word essay in the user's own words (draft provided), screencast recording (script provided), form submission by the user.
- Optional: hosted demo server spend.

## 15. Verification (Contest Edition, end-to-end)
1. `./gradlew spotlessCheck detekt allTests` green locally (Windows: JVM, Wasm, mingwX64) and in CI (Linux + macOS iOS simulator).
2. Desktop creator: `./gradlew :app:desktopApp:run` → new project from Platformer template → paint tiles, move entities, undo/redo → Play/Stop → Issues panel flags an unreachable coin → fix → ghost run completes.
3. AI (real keys): Level Designer prompt → iteration log shows failed validation then repair → accept diff → level playable. Character Forge "legendary shark × fighter jet, 3 evolutions" → transparent stage sprites + income/evolution rules → plays in Brainrot Tycoon. Cost shown per job; cancel works.
4. Replay mode: same flows run without keys on desktop and on the Pages-hosted web creator, labelled as replay, validators live.
5. Publish to local server → `/play/{slug}` plays in Chrome/Firefox; Pages arcade gallery plays sample games without server.
6. Android: `./gradlew :player:androidApp:assembleDebug -PgameBundle=… -PappId=… -PappName=…` → install on API-36 emulator → same game with touch controls.
7. iOS: macOS CI job green (simulator tests + framework link).
8. Clean clone on Windows and Linux follows README successfully; release tag produces APK + desktop packages + Pages link.

## 16. First steps after approval
1. Write detailed implementation plans for M0–M1 (writing-plans skill) into `docs/plans/`.
2. Generate the wizard baseline (untrusted archive in its own directory), restructure, add build-logic/CI/AGENTS.md/ADRs, `git init`, then create `aioanei/spawnkit` after confirming with the user, push the bootstrap, set branch rules, create milestones and issues.
3. From D2 on, every change goes through issue → branch → PR → CI → user-approved merge.
