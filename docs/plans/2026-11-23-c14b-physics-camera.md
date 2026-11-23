# C14b Platform Physics and Camera Follow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The second half of C14 ([#88](https://github.com/aioanei/spawnkit/issues/88)): deterministic platform
physics and a camera that follows a tagged object, so platformers with levels wider than the screen can be built from
general blocks. C14a (PR #96) delivered kept numbers, randomness, economy and spawning.

**Architecture:**
- **Body** (`gameplay:body`): speed across and down, gravity, bounce and fastest fall, all in world units per second.
  It also keeps whether the object stood on something after the last moment; that flag is play state only (captured in
  checkpoints, not a property).
- **Solid** (`gameplay:solid`): bodies cannot pass through it. It remembers where it was after the last moment (play
  state only), so the engine knows how far it moved.
- **One physics system** (`gameplay:physics`), after rules and steady movement, for each body in scene order:
  1. *Carry:* a body that stood on a solid moves with that solid's movement since the last moment, however the solid
     moved (Move blocks, steady movement). This is how bodies ride moving platforms.
  2. *Settle:* a body already overlapping a solid (a Move block walked it into a wall, a platform rose into it) is
     pushed out along the axis that needs the shorter push.
  3. *Gravity:* speed down grows by gravity per second, up to the fastest fall.
  4. *Across, then down:* the body moves along x in steps no longer than a quarter unit, and any solid it now overlaps
     pushes it back to the solid's edge; its speed on that axis reverses times the bounce (small rebounds stop). Then
     the same along y. Landing while moving down sets "on the ground".
  Only `+ - * /`, comparisons, `abs`, `min` and `max` on doubles (ADR-0004). Edges that only meet do not count as
  overlapping for solids, so a body resting on the ground can walk.
- **Blocks:** "On the ground" (condition), "Jump" (if on the ground, set the speed up), "Set speed" (set the body's
  speed in a direction, for launch pads, knock-back and dashes). Walking stays the existing Move block. Everything is in
  the block catalog, so the block dialog and agent tools are derived (ADR-0009).
- **Follow** (`gameplay:follow`): keeps its object at the world position of the first object with a tag, across, down
  or both. Put it on a camera for levels wider than the screen. A system after physics applies it.
- Moving platforms use the existing patrol pattern (a number for the direction, Move blocks, and edges that flip it),
  as NPC Dodge does; no genre-specific block (ADR-0006).

**Spec:** [design §11](../design/2026-11-22-title-screens-presets-accounts-ads.md), issue #88.

## Global Constraints

- Deterministic, as in C14a; a seeded physics run repeats exactly on every core target and from a checkpoint.
- `core/*` keeps `explicitApi()` and KDoc on every public declaration.
- Every new block: a `BlockSpec`, compile and exact decompile, `describe`, and a parity case. New components get a
  parity case through the generated inspector.
- Both builds green, including `localNativeTests` / `-p mingwX64` since `core/*` changes.

## Tasks

### Task 1: Body, solid and the physics system
- `Body`, `Solid` with descriptors (`body_…` properties for rules) and codecs; `PhysicsSystem`.
- Tests (`PhysicsTest`): landing on the ground; falling at most the fastest fall; blocked by walls on both sides;
  bouncing; walking along the ground with Move; riding a moving platform across and down; pushed up by a rising
  platform; a checkpoint mid-jump replays to the same hash.

### Task 2: Physics blocks
- `Condition.OnGround`, `Action.Jump(speed)`, `Action.SetSpeed(direction, speed)`; the `onGround()` host function.
- Tests: round trips through programs and the catalog; descriptions; jumping only from the ground; a jump reaching the
  expected height; Set speed launching a body.

### Task 3: Camera follow
- `Follow(tag, across, down)` with descriptor, codec and `FollowSystem` after physics.
- Tests: a camera follows a running player across only; follows both axes; no tagged object leaves it in place;
  Leaves the play area uses the followed camera.

### Task 4: Builder, parity and evidence
- Parity cases for the three blocks and for Body, Solid and Follow properties.
- `PhysicsUiTest`: build a platformer (ground, player with body, Jump rule, camera follow) by taps on phone and desktop
  layouts, then play it and see the player land and jump.
- Android emulator screenshots of the platformer; docs (`docs/gameplay.md`, `PROGRESS.md`, `AGENTS.md`).
