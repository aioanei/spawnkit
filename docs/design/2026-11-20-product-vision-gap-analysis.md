# Product vision clarification — gap analysis and decisions

Date: 2026-11-20. Baseline: `main` at `cfa16a3` (R1 complete; R2a–R2f merged). This note reconciles the owner's
clarified product vision with the repository. It is deliberately short: it records classifications and the few
decisions that change the plan. The full requirement list lives in the owner's clarification and is reflected in
[ADR-0007](../adr/0007-mobile-builder-and-in-builder-release.md) and the
[plan's contest track](../plans/2026-11-20-engine-foundation-revision.md#contest-delivery-track).

## Contest facts (rechecked 2026-11-20 at kotlinconf.com/contest-rules)

- Deadline: 7 Dec 2026, 23:59 CET. Winners are expected around 21 Dec.
- Judging: 40% creativity and novelty, 40% KMP use and shared code, 20% Kotlin coding conventions.
- License: Apache-2.0, MIT or BSD-3; this repo uses Apache-2.0.
- Entries: individual only, created during the contest, no template projects.
- Submission: repository, 300-word essay and a comprehensive README; a 3–5 minute screencast is optional.
- Correction to the earlier design note: the rules state no minimum platform count. "≥ 2 platforms" was an
  inference.

## Classification

| Requirement | Status | Evidence or gap |
|---|---|---|
| Genuine general-purpose 2D engine, not JSON-driven | **Partially implemented, direction correct** | `core/runtime` provides open components, scheduled systems, checkpoints, sparse-set storage, descriptors, hierarchy, resources, input and scene templates. `core/program` provides the portable logic executor. No JSON in the engine contract. Legacy `core/model`/`core/engine` stays as the platformer baseline. |
| Rendering, sprites, camera, layers, collisions, physics | **Planned (R3), missing in the general runtime** | Exists only in the legacy platformer renderer and engine. |
| Animation, audio, tilemaps, particles | **Missing / planned (R3–R5)** | Not in the general runtime. |
| Events, timers, game state, custom logic | **Implemented (R1d, R2e)** | Program events, waits, state, input functions, bounded atomic segments. |
| Inventory, economy, quests, dialogue, story | **Missing; expressible later** | To be built as program and component packages, not engine special cases. |
| Prefabs and scenes | **Partially implemented** | R2f templates and instance identity. No editor, persistence or transitions yet. |
| Full builder on Android **and** desktop | **In conflict with the earlier contest design; missing** | The design's §5.5 scoped the creator to desktop and web, with AI in-process on desktop only. The creator app is a 53-line shell. |
| Touch-first editor interactions | **Missing** | No editor exists yet. |
| Professional visual editor | **Planned (R4–R5), missing** | — |
| No-code mechanics (rules, visual scripting) | **Partially implemented** | The executor exists (R1d). No rule editor UI. Descriptor-driven inspector model (R2a). |
| Third-party extensions and marketplace | **Planned (R5, R8), deferred after contest** | The public registration APIs (`RuntimeBuilder`, descriptors, `ProgramHost`) are the SDK seed. |
| Release Manager in the builder | **In conflict with the earlier plan; missing** | The plan and design assumed Gradle-built per-game players and a CLI-driven Android build. |
| Independent exported games | **Missing** | Player shells checkpoint `b2bf830` remains unmerged. |
| Arcade (browser platform) | **Planned (R8), missing** | `server` is a stub. |
| Koog agentic AI Studio | **Planned (R7), missing** | No `ai` module yet. |
| Autonomous characters | **Deferred after contest** | Programs and resources are the runtime foundation; no LLM needed at runtime. |
| Story and level tools | **Deferred after contest**, except a basic level editor | — |
| Ads, IAP, self and managed publishing, payouts | **Deferred after contest** | Keep the host-service seams in R8. |
| Accounts, cloud sync, version history | **Deferred after contest** | Local save/load and import/export come first. |
| Creator dashboard and analytics | **Deferred after contest** | No fabricated analytics. |
| Security: credentials, safe user logic | **Partially implemented** | Bounded deterministic program execution (R1d). Credential handling is still to be designed with AI and release work. |
| Workflow: issues, PRs, CI, AGENTS.md as shared instructions | **Implemented** | — |

## Decisions

1. **The engine direction stands.** Nothing here justifies rewriting `core/runtime` or `core/program`. Remaining R2
   items (asset references, scenario helpers) are folded into later steps where they are needed.
2. **The mobile builder is first-class from the first editor increment.** Every editor screen ships responsive
   layouts with touch gestures (tap select, drag move, pinch zoom, two-finger pan) and mouse and keyboard
   equivalents. Each editor PR is verified on Android and desktop; iOS compiles in CI. The web creator follows, since
   Compose for Web is Beta. Desktop is never a mandatory step.
3. **Release uses a universal-player-plus-package model.**
   - The builder exports a game as a prepared project package plus a prebuilt SpawnKit player runtime. For web,
     that is the player's Wasm distribution bundled into the creator app.
   - Exports run without the creator app. The contest slice is: web export from the builder (folder or zip), and
     publishing to the SpawnKit Arcade over Ktor.
   - Android standalone export (repacking and signing a template player APK) is the next release step.
   - iOS, desktop native and store submission need remote builds or official tooling, so they come after the
     contest. Gradle is never shown to creators.
4. **Koog agents run in-process on Android and desktop**, with user-supplied keys stored on the device and never in
   project files or exports. A labelled replay mode serves demos without keys. A server-hosted agent mode follows
   later. Agents act only through editor operations.
5. **Contest priority is a vertical slice on the general engine** (see the plan's contest track). Long-term features
   remain on the roadmap and are not removed.
