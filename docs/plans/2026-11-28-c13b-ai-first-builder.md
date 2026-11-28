# C13b AI-first builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The second part of C13 ([#85](https://github.com/aioanei/spawnkit/issues/85), [design §9](../design/2026-11-22-title-screens-presets-accounts-ads.md#9-c13--ai-first-building-and-hosted-ai)).
C13a taught the assistant to build a complete game. C13b makes the builder start from it:
- New game opens with "Describe your game…";
- the chat is the main panel;
- AI changes apply right away, each as one undo step with **Revert**.

**Decisions taken while the owner was away (2026-11-28)**, each easy to change:
- **Apply right away is the default.** "Review changes first" is a switch in the assistant. It is kept for this run of
  the app only, like the other panel choices.
- **Revert works while the AI's step is the latest one.** Revert is the editor's own undo of that one step. After a
  manual change or an Undo, the card says to use Undo (↶) instead, so Revert never removes the creator's own work.
- **Where the chat lives.**
  - Phones: AI is the first tab and opens first.
  - Wide screens: AI is the first tab of the side panel beside the canvas and opens first. Selecting an object switches
    to Properties, so manual editing works as before.
  - A column of its own was tried on paper: at 1280 dp it leaves the canvas under 400 dp, so it waits for C19.
- **Signed out.** The "Describe your game…" box explains that building with AI needs an account, with **Sign in**.
  It also offers **Watch the AI build a game**: the reviewed Jungle Banana Run recording (C13a), replayed and labelled
  "Replay", with no model called.

## Tasks

### Task 1: Apply right away, Revert and the conversation (`AssistantState`)
- `reviewFirst` (default `false`). Without review, a proposal is accepted as soon as it arrives.
- `chat`: the creator's requests and answers, the assistant's answers, and **Applied** cards. A card has the summary,
  the steps, and whether it can still be reverted. The chat is cleared when another project is loaded, and kept when
  the scene changes.
- `revert(card)`: undoes the step when the editor's latest step is still that card's step.
  `AgentSession.reverted()` tells the model.
- The prompt box empties when a request is sent.
- Tests: apply right away and one undo step; Revert; Revert refused after a manual change; review first still waits for
  Accept; the chat survives a scene change and is cleared by a new project.

### Task 2: The chat panel (`AssistantPanel`) and AI first (`EditorScreen`)
- The conversation scrolls above a composer (prompt, Send, Attach photo). The Applied card has Revert and a "Show N
  changes" toggle. Header: the model label, Review first, and Developer settings.
- The AI tab comes first on phones and in the side panel, and opens first. On wide screens, selecting an object opens
  Properties.
- Existing UI tests open the tab they use.

### Task 3: Prompt-first New game
- `ProjectSession.describe(prompt)` starts a blank game like New. Once the game is loaded, `buildRequest` holds the
  prompt; the builder opens the AI panel and asks.
- `NewGamePicker` shows "Describe your game…" with **Build it** above the presets. Signed out, it shows Sign in and
  Watch the AI build a game instead.
- `WholeGameRecording` embeds `ai/recordings/jungle-banana-run.json`. A JVM test keeps the two identical, and
  `tools/embed-recording.py` regenerates the embedded copy. `AssistantState.watchReplay` runs it with `ReplayModel` on
  the new blank game, labelled.
- Tests: picker phone and desktop with and without AI; Build it starts a blank game and asks; unsaved changes are
  asked about first; the replay builds Jungle Banana Run's six scenes and stays labelled.

### Task 4: Evidence, docs, gates, PR
- Screenshots: New game with the prompt, the chat after a build with Revert, on phone and desktop sizes.
- Android emulator: the replay from New game on API 36 (C13c may add the live one).
- Docs: AI Studio, PROGRESS, AGENTS checkpoint, the plan's status.
- Gates: Gradle `spotlessCheck allJvmAndWebTests`, Toolchain `check ktlint` and `test -p jvm -p android`.
- A PR stacked on #107.
