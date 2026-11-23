# C13a Game Architect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The first part of C13 ([#85](https://github.com/aioanei/spawnkit/issues/85), [design §9](../design/2026-11-22-title-screens-presets-accounts-ads.md#9-c13--ai-first-building-and-hosted-ai)):
the assistant can plan and build a **complete** game from one prompt with the builder's own operations. That means a
title screen, several levels, game-wide values, a shop with test purchases, ad spots, and win and lose scenes, each
scene checked in the real engine. C13b makes the builder AI-first (prompt-first New game, the chat as the main panel,
apply right away with Revert). C13c records a live whole-game session as a labelled replay and adds emulator evidence.

**Why now.** The catalog already has every operation a complete game needs. The reading tools see only the session's
scene, and nothing shows the agent a worked example. A whole game is also many model calls with large tool
definitions: without prompt caching it would cost several dollars, more than the $1 daily allowance per account.

## Tasks

### Task 1: The whole game in view
- `look_at_game` returns every scene (ID, name, start scene, object count), the images and the ad settings. It looks
  inside the session's scene, or another scene given by `scene`. Fields the demo reads stay unchanged.
- `check_game` and `trace_rules` take an optional `scene`; `check_game` also takes `all`.
- Tests: an agent builds and checks rules in a second scene; check `all` reports a problem in another scene.

### Task 2: Worked examples
- `look_at_example`: the complete-game templates (C17) as read-only examples. Without `scene` it gives an overview;
  with one, the scene in the same form as `look_at_game`. The `ai` module depends on `core/presets`.
- Tests: the overview lists the templates and their scenes; a scene matches `look_at_game` on the loaded template.

### Task 3: Game Architect instructions and budget
- The instructions explain how to build a complete game:
  - plan first;
  - the start scene is the title screen with a Play button;
  - levels, then win and lose scenes;
  - kept numbers for coins and lives;
  - Test purchase blocks for the shop and `set_ads` for ad spots;
  - check every scene, trace the main rules, and finish with what was made and how to change it by hand.
- Allow more model calls per request (60).
- A scripted fixture builds a three-scene game (title, level, win) through the tools, checks it, accepts, and plays
  it from the title to the win scene in the real engine.

### Task 4: Prompt caching and honest cost
- `KoogModel` asks Anthropic to cache the system prompt and tools (`AnthropicParams.cacheControl`).
- `Usage` counts cache writes and reads from Koog's metadata. `Cost`, the server's ledger prices, recordings and the
  request JSON price them: writes at 1.25 × the input price, reads at 0.1 ×.
- Tests: the wire test sees `cache_control` in the request and cache tokens in the reply; old recordings and replies
  without cache fields still read.

### Task 5: Docs, gates, PR
