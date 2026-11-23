# C14a Game-wide Values, Randomness, Economy and Spawning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The first half of C14 ([#88](https://github.com/aioanei/spawnkit/issues/88)): numbers that survive "Go to
scene", random numbers, a tycoon economy block, and spawning copies of objects during play, with a "Leaves the screen"
condition. C14b adds platform physics and camera follow.

**Architecture:**
- **Game-wide values are kept numbers.** The Number component gains a "Keep across scenes" property. When the game
  goes to another scene, the play session carries every kept number to the objects with the same tag in the new scene,
  and remembers numbers whose tag the new scene lacks for later scenes. Existing number blocks (change, set, compare,
  show) work on them unchanged through their tag. No format change: components and properties are open.
- **Randomness** compiles to the seeded `random(min, max)` host function that already exists.
- **Economy:** "Add one number to another" (income per second with "Every … seconds").
- **Spawning.** An object marked **Template** stays in the builder but is left out of the play world. "Spawn a copy of
  ⟨tag⟩ at ⟨this object / a tagged object / a position / a random spot⟩, moving ⟨direction⟩ at ⟨speed⟩" requests a
  `spawn` effect; after the tick the play session builds the copy from the authored template object, with its own
  rules, exactly like "Go to scene" is handled. Copies moving in a direction get the existing Move behaviour through a
  `gameplay:motion` component the engine advances each tick. A "Leaves the screen" condition is true when the
  object's bounds no longer overlap the camera's view.
- Everything is general (ADR-0006) and goes through the rule block catalog, so the block dialog and the agent tools are
  derived (ADR-0009).

**Spec:** [design §10](../design/2026-11-22-title-screens-presets-accounts-ads.md), issue #88.

## Global Constraints

- Deterministic: only `+ - * /` on doubles in the engine, seeded randomness, fixed ticks. A seeded run repeats on
  every core target.
- `core/*` keeps `explicitApi()` and KDoc on every public declaration.
- Every new block: a `BlockSpec`, compile and exact decompile (`RuleBlocks.recognise`), `describe`, and a parity case.
- Both builds green, including `localNativeTests` / `-p mingwX64` since `core/*` changes.

## Tasks

### Task 1: Kept numbers across scenes
- `NumberValue.keep` with a "Keep across scenes" descriptor property; the codec keeps the old state for `keep = false`.
- `StandardEngine.carryKeptNumbers(from, to, memory)`; `playSession` uses it on every scene change.
- Tests: `aKeptScoreSurvivesTwoLevels`; `numbersNotKeptStartFresh`; `aValueMissingFromOneSceneComesBackLater`.

### Task 2: Random number and add-number blocks
- `Action.SetRandomNumber(target, min, max)` and `Action.AddNumber(target, from)`, with specs, compile, recognise and
  describe.
- Tests: round trips; a seeded run gives the same numbers twice; income adds every second.

### Task 3: Templates and spawning
- `gameplay:template` component ("Template: only copies appear in play"); `Action.Spawn(tag, place, direction,
  speed)`; `gameplay:motion` component and system; `StandardEngine.takeRequests(world)` returning the scene request
  and the spawn requests; `spawnCopy` building one template object (and its children) into a running world with its
  rules.
- Tests: `templatesAreLeftOutOfPlay`; `aSpawnedCopyRunsItsOwnRules`; `copiesMoveInTheirDirection`;
  `spawningAtARandomSpotRepeatsWithTheSameSeed`.

### Task 4: Leaves the screen
- `Condition.LeftScreen`, compiled to an `offScreen()` host function against the first camera (or the default view).
- Test: a shot removed once it leaves the screen.

### Task 5: Builder, parity and evidence
- The block dialog shows the new blocks (generated); the inspector shows Keep across scenes and Template.
- `OperationParityTest` cases for the new blocks; UI test building a spawner by taps; emulator screenshot.
- Docs: `docs/gameplay.md`, `PROGRESS.md`, `AGENTS.md`.
