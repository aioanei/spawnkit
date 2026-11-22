# C10b Brain-Rot Presets and Guidance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two brain-rot style presets (Aura Farmer, NPC Dodge) and guidance in all five presets: the blocks each one
uses in the picker, and a "How it works" scene reachable from each title screen.

**Architecture:**
- The presets and the How it works scenes are made by the same builder-call recipes as C10
  (`app/shared/test@jvm/.../presets/PresetRecipes.kt`), then written to `presets/<id>/project.json`.
- `GamePreset` gains `teaches`, a list of block names. A test checks every listed block appears in the preset's rules.
- The picker shows `Uses: …` under each pitch.

**Spec:** issue #84. Owner, 2026-11-22: "the templates are for showing judges what this app is capable of and also
showing users and guiding them about what they can do". Proposal approved with "go on working".

## Global Constraints

- Original characters and names only: no Skibidi Toilet, no "Italian brainrot" characters, and no other third-party
  meme IP. The contest requires work the entrant fully owns.
- Only today's blocks plus Go to scene. No emoji, since the canvas font may not have them.
- Text lines on How it works scenes are at most 32 characters, so they fit a phone held upright (about 8.5 units).
- Each new preset has a title screen, a win scene and a lose scene with a button back to the game.

## Review Focus

1. Tapping fast enough to win Aura Farmer on a phone: the goal must be reachable by a person, not only a bot.
2. NPC Dodge must be survivable by a person who moves, yet an idle player must lose.
3. The How it works button must sit above the phone's on-screen controls.
4. The picker's preview must still show each game scene, not the How it works scene.
5. The recipe check must still catch drift for all five presets.

---

### Task 1: Guidance in the existing presets

- Failing tests in `PresetGamesTest`:
  - `everyPresetExplainsHowItWorks`: tapping `how-button` on the title opens "How it works", and tapping
    `back-button` returns to Title;
  - `listedBlocksAreUsed`: every name in `teaches` is a block the preset's rules use;
  - the catalog test also requires a non-empty `teaches`.
- `GamePreset(id, name, pitch, teaches)`. Recipes gain `howItWorks(lines, tryThis)`.
- The picker card shows `Uses: a · b · c`.
- Rewrite the files with `SPAWNKIT_WRITE_PRESETS=1`, then run the tests.

### Task 2: Aura Farmer and NPC Dodge

- Failing tests:
  - `auraFarmerBotTapsToMaxAura` and `auraFarmerIdleEndsInNegativeAura`;
  - `npcDodgeBotSurvives` (moves to a safe spot) and `npcDodgeIdleGetsNPCd`.
- The recipes:
  - **Aura Farmer**: Aura Toaster; Aura counter, goal 50; a 15 s timer.
  - **NPC Dodge**: Main Character with arrow keys and edge push-back. Lane NPCs bounce left and right on rows -2, 0
    and 2; column NPCs bounce up and down on columns -2 and 2. A 20 s survival timer.
- Add both to `GamePresets` with pitches and `teaches`. Write the files, then run the tests.

### Task 3: Picker, evidence, docs

- `NewGamePickerTest` covers the five presets.
- Desktop and phone screenshots, an emulator run of Aura Farmer, and updates to `docs/project-persistence.md`,
  `presets/README.md`, `PROGRESS.md` and `AGENTS.md`.
