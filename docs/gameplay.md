# Gameplay facilities and the standard engine — C4a

Tracked by [issue #49](https://github.com/aioanei/spawnkit/issues/49). This is item C4a of the
[contest delivery track](plans/2026-11-20-engine-foundation-revision.md#contest-delivery-track). It gives
[portable programs](runtime-programs.md), and therefore the visual rules of C4b, the general facilities that real
no-code mechanics need. Examples are collecting items, tapping for coins, passive income and showing a score. There
are no genre-specific opcodes: every mechanic is an ordinary rule built from these pieces.

## `core/gameplay`

| Piece | What it is |
|---|---|
| `Tag` (`gameplay:tag`) | A group name such as `player`, `coin` or `enemy`, compared ignoring case and surrounding spaces. Rules find and test objects by tag. |
| `NumberValue` (`gameplay:number`) | A whole number kept by an object: a score, coins, lives. |
| `gameplay:random` | Each world's `SeededRandom`, started from the engine seed and checkpointed with the world. |
| `Gameplay.bounds` | An object's world box: its shape (else sprite, else text) size, moved and scaled by it and its parents. |
| `StandardEngine` | Installs 2D presentation, gameplay, the standard input actions and rules. The builder uses it, and exported players will construct the same engine. |

### What rules can use

`Gameplay.bind` adds these to a `ProgramHostBuilder`. `StandardEngine` also adds the
[input functions](runtime-programs.md#input-functions-r2e) for the standard actions.

**Properties** come from the component descriptors, so rules obey the same limits as the inspector:

- `x`, `y`, `rotation`, `scaleX`, `scaleY`;
- `shape_…`, `sprite_…`, `text_…` and `camera_…` (for example `shape_width`, `text_text`);
- `tag` and `number`.

Colors are not yet available to rules.

**Functions:**

| Function | Result |
|---|---|
| `touching(tag)` | Yes when this object's box overlaps (or meets) another object with the tag. An object never touches itself; objects without a shape, sprite or text cannot be touched; hidden shapes still count, like invisible walls. |
| `tapped()` | Yes when a pointer went down on this object this tick: pressed now, not pressed by the same pointer on the previous tick, inside the box. Holding does not repeat. |
| `firstTagged(tag)` | The first object with the tag in scene order; faults with a clear message when there is none. |
| `countTagged(tag)` | How many objects have the tag. |
| `textOf(n)` | A whole number as text, for labels such as `"Score: " + textOf(n)`. |
| `random(min, max)` | A whole number from min to max inclusive, from the seeded generator. Both ends must be within ±10⁹, and min must not exceed max. |
| `tick()` | Ticks since play started, 60 per second; for example "every second" is `tick() % 60 == 59`. |
| `isDown`, `wasPressed`, `wasReleased` | The standard actions `input:left`, `input:right`, `input:up`, `input:down` and `input:jump`. |

### Determinism

Touching and tapping use only `+`, `*`, comparisons and `abs`, so every target makes the same decisions. Rotation
therefore does not turn an object's box. Pointer positions are world units: hosts convert screen positions through
the camera. Touching reads positions as they stand when the function is called, including earlier rules' committed
moves this tick, but not moves made earlier in the same rule run (those apply when the run finishes).

## Evidence

`GameplayTest` (common, all core targets):

- **A coin-collect game authored as rules.** The player moves while `input:right` is held. Each coin that touches the
  player adds a point to the object tagged `score`, updates its label to `Score: n`, and removes itself. After 12
  ticks the score is 2, the label reads `Score: 2`, and no coins remain.
- **Replay.** The same game restored from a mid-game checkpoint and fed the same input reaches the same state hash.
- **Tapping and passive income.** Taps inside a button earn coins; holding, pressing outside, and releasing do not.
  Income arrives once per 60 ticks. Exact totals are asserted.
- **Touching semantics:** meeting edges, self-exclusion, parent position and scale, shapeless objects, hidden shapes.
- **Random:** the same seed gives the same rolls in two worlds, rolls stay in range, the generator position survives a
  checkpoint, and an inverted range faults the rule.
- **Text and lookups:** `textOf` formatting, and `firstTagged` faulting with the missing tag's name.
- **Catalog:** the standard engine exposes these properties, functions, actions and components.

## Not yet

- The When / If / Do rule editor in the builder (C4b), and attaching rules to objects in the workspace.
- Submitting keyboard and pointer input from the builder's play view (C4b).
- Colors in rules, multiple tags per object, and collision response (solid objects, gravity) — R3 physics.
- A per-game random seed setting.
