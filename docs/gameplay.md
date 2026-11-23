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
| `tick()` | The current tick, 60 per second. The first tick is 1, so "every second" is `tick() % 60 == 0`. |
| `isDown`, `wasPressed`, `wasReleased` | The standard actions `input:left`, `input:right`, `input:up`, `input:down` and `input:jump`. |

### Determinism

Touching and tapping use only `+`, `*`, comparisons and `abs`, so every target makes the same decisions. Rotation
therefore does not turn an object's box. Pointer positions are world units: hosts convert screen positions through
the camera. Touching reads positions as they stand when the function is called, including earlier rules' committed
moves this tick, but not moves made earlier in the same rule run (those apply when the run finishes).

## Rule blocks (C4c)

`RuleBlocks` is the friendly block vocabulary of the standard engine, used by the builder's rule editor and available
to agents. A `Rule` is a When block, optional And-if blocks and Do blocks; it is checked every tick.

| Conditions (When / And if) | Actions (Do) |
|---|---|
| Every moment · Game starts · Every N seconds | Move left/right/up/down at a speed per second |
| Touching a tag · Tapped | Add to / take from a number (this object or a tagged one) |
| Holding / pressing a standard action | Set a number |
| A number compared with a value | Show a number in a text, after a prefix ("Score: n") |
| How many objects have a tag, compared with a value | Remove this object |
| | Set plain text on this object or a tagged one |

- **One executable model.** `RuleBlocks.compile` turns rules into ordinary every-tick handlers (one `if` each). Every
  node of a block carries the block's node ID (`rule<i>.if<k>` or `rule<i>.do<k>`) and its plain description, so
  diagnostics and faults point at the block (`RuleBlocks.locate`).
- **Safe round trip.** `RuleBlocks.decompile` reads a handler as blocks only when compiling the recognised rule
  reproduces it exactly. Anything else, such as a rule written by an agent, is a `RuleItem.Advanced` rule: it is shown
  read-only and kept byte-for-byte when other rules change. The rule number comes from the handler's own node ID, so
  block rules stay blocks when rules around them are added or removed; recompiling renumbers them by position.
- **Timing.** The world counts ticks from 1: "Game starts" is `tick() == 1` and "Every N seconds" is
  `tick() % (60·N) == 0`.

## Scenes and "Go to scene" (C9)

- **The block.** `Action.GoToScene(sceneId)` compiles to a program effect request named `goToScene`, with the scene
  ID as its payload. It is checked every tick like any rule.
- **Switching.** After each tick, `StandardEngine.handleRequests(world, project, sceneId)` makes requested copies and
  returns the last scene request once.
  `PlaySession` (`player/playerKit`, created with `StandardEngine.playSession`) then switches to a fresh world of that
  scene. Builder Play, exported players and the web player all behave this way.
  - Going to the scene that is already playing restarts it.
  - Pointers pressed on the old scene are dropped.
  - Numbers marked **Keep across scenes** carry over by tag (C14a).
- **Missing scenes.**
  - Rule edits cannot add a "Go to scene" for a missing scene.
  - A target removed later is a rule problem on the block ("that scene was removed").
  - During play, such a request is ignored with a message.
- **Title screens.** The builder's "+ Title screen" applies one `Batch` of catalog operations:
  - a Title scene with a background, the game's name, and a Play button tagged `play-button`;
  - the button's rule "When tapped → Go to scene ⟨previous start scene⟩";
  - moving the Title scene to position 1, so it becomes the start scene.

  Everything is then edited with the normal inspector and rules. Since C15 it first asks for a layout (below).

## Home screen kit (C15a)

The first half of [#89](https://github.com/aioanei/spawnkit/issues/89), so a game gets a real home page made of
several objects. [Plan](plans/2026-11-23-c15a-home-screen-kit.md).

- **Palette pieces.** **Button**: a green shape with a Play label inside it and "When tapped → Go to scene ⟨the next
  scene⟩" (the first other scene after the last; no rule while the game has one scene). **Text**: words on the screen.
  A piece with parts is one undo step.
- **Grouping.** `move_into` puts an object inside another, or back at the top level, and keeps it where it is on
  screen. In the builder: "Move into…" under the object's name in the inspector, or hold a row of the scene list and
  drag it onto another row (or below the list for the top level).
- **Title screen layouts.** "+ Title screen" offers Play; Play + Levels; Play + Levels + Shop + How it works. Each
  extra button goes to a scene made in the same step: **Levels** has a button for every scene the game had, in one
  centred column for up to four levels, then two and three narrower columns, so it fits a phone held upright; **Shop** and **How it works** have a heading, texts to replace and a Back button. A Shop's items and purchases
  come with C12.

Tests: `MoveIntoTest` 4 (common); `HomeScreenKitTest` 7 checks that level buttons fit a phone and plays the full layout from the title to the levels, a level,
the shop and How it works and back; `HomeScreenKitUiTest` builds a button and text, groups them with Move into… and by
dragging, and adds the full layout, on phone and desktop; `OperationParityTest` adds `move_into`.

On the Android API 36 emulator, `tools/android-home-smoke.py` adds the full layout and a Text with real taps, moves the
Text into the Play button with Move into…, plays the home screen and opens Levels from its button. Screenshots:
[editing](media/android-home-edit.png), [home screen](media/android-home-play.png),
[levels](media/android-home-levels.png). Importing images, the Image piece and a logo come with C15b.
- **Evidence.** Android emulator screenshots: [editing](media/android-title-edit.png),
  [playing the title](media/android-title-play.png), [after tapping Play](media/android-title-level.png).

## Images in projects (C15b)

The second half of [#89](https://github.com/aioanei/spawnkit/issues/89). Decision:
[ADR-0012](adr/0012-images-in-projects.md); [plan](plans/2026-11-23-c15b-images.md).

- **Stored in the project.** `Project.images` keeps each PNG in Base64 with its SHA-256. The workspace file moved to
  schema 2; schema 1 files open with no images. Limits: 2048 × 2048 pixels and 512 KB per image, 4 MB per game, and a
  10 Mi-character project file. Saves, exports, the Arcade and the web player carry the images with the project.
- **Importing.** The Add panel's **Image…** piece chooses a picture on Android (the system picker) or desktop (a file
  dialog) and places it in the scene, as one undo step; **Import…** only adds it to the game's image list. Other
  formats and large photos are converted to a PNG whose longest side is at most 1024 pixels. iOS and the web builder
  have no picker yet.
- **Using.** A sprite's **Image** is chosen from the game's images in the inspector; **Pixelated** keeps pixel art
  crisp. The image list offers Add, Rename and Remove; an image a sprite shows cannot be removed.
- **Drawing.** `rememberProjectImages` decodes each picture once per hash and gives it to `RuntimeCanvas` in the
  builder, Play, exported players and the web player. Images never enter the simulation.

Tests: `ImagesTest` 10 (common: SHA-256 vectors, PNG checks, operations, catalog, schema 1 migration, damaged files);
`GamePackageTest` and the server's `ArcadeTest` carry an image through export and the Arcade; a render test shows a
pixelated sprite's hard edge; `ImagesUiTest` imports, places, picks and plays a picture on phone and desktop; parity
cases cover the three image operations, the sprite's image and Pixelated.

On the Android API 36 emulator, `tools/android-logo-smoke.py` pushes an original logo
(`tools/fixtures/spawnkit-logo.png`) to the device's pictures, adds a title screen, imports the logo with **Image…**
through the system photo picker, and plays the home screen with it. The photo picker hides file names, so the image is
called Picture. Screenshots: [picker](media/android-logo-picker.png), [editing](media/android-logo-edit.png),
[home screen with the logo](media/android-logo-play.png).

## Test ads with real counts (C12a)

The first half of [#77](https://github.com/aioanei/spawnkit/issues/77). [Plan](plans/2026-11-23-c12a-test-ads.md).

- **Settings.** `Project.ads` (workspace schema 3; older files open with ads off): a banner on the start scene and an
  interstitial between scenes every N scene changes. The Release Manager's **Test ads** card changes them with the
  `set_ads` operation, so agents can too.
- **Players.** `AdLayer` (`player/playerKit`) shows a labelled **TEST AD — SpawnKit** panel from `TestAdHost`; there is
  no ad SDK and no third-party call. `AdSchedule` never shows an interstitial on the first scene change, so never
  before the first play, and an interstitial pauses the game until it is closed. `AdHost` returns an explicit
  `AdResult`, so a real network (K6) can plug in later.
- **Counting.** Only the Arcade's web player has an `AdReporter`. It batches views and clicks to
  `POST /api/games/{slug}/ad-events` with a random play-session ID and numbered events, and no personal data. The
  server counts each session and number once, limits reports per address, and keeps totals per game and UTC day
  (database schema 2). Builder previews and offline web exports have no reporter, so they are never counted.
- **Statistics.** My games → **Ad statistics** (owner only, `GET /api/games/{slug}/ad-stats`) shows views, clicks
  and click-through rate per day, "Revenue: $0.00 — test ads earn nothing", and an optional What if line labelled "an
  estimate, not earnings".

Tests: `AdSettingsTest` 4 (common), `AdsTest` 4 (player), the server's `AdStatsTest` 3, `TestAdsUiTest` on phone and
desktop, and the `set_ads` parity case.

## Game-wide values, randomness, economy and spawning (C14a)

Blocks for complete games ([#88](https://github.com/aioanei/spawnkit/issues/88)), all general and all in the block
catalog, so the block dialog and the agent tools include them:

- **Game-wide values.** A Number with **Keep across scenes** carries its value to the object with the same tag in the
  next scene (`StandardEngine.carryKeptNumbers`, used by every play session). The session remembers values whose tag a
  scene lacks, so coins survive a shop scene without a coins label. A title screen's "Game starts → set Coins to 0"
  starts a fresh run. Existing number blocks (add, set, compare, show) work on kept numbers through their tag.
- **Set a random number** from a range, from the seeded generator, so a game always rolls the same way.
- **Add one number to another**, for income: "Every second, add Income to Coins".
- **Templates and Spawn a copy.** An object with the **Template** component stays in the builder but is left out of
  play. "Spawn a copy of ⟨tag⟩ at ⟨this object / an object with a tag / a point / a random spot⟩, moving ⟨direction⟩
  at ⟨speed⟩" asks for a copy; after the tick, players build it from the authored object, with its own rules, just as
  they handle "Go to scene". Copies that move get **Steady movement** (`gameplay:motion`), which the engine applies each
  tick after rules. A world stops spawning at 2,000 objects, including every child in a copy; a copy that does not fit
  is skipped as a whole. Spawning at this object or a tagged object includes its parents' translation and scale, and
  sees moves made earlier in the same rule.
- **Leaves the play area** is true when an object is wholly outside a box (40 × 24 units by default) around the first
  active camera, divided by its zoom. The box is the same on every screen, so play stays deterministic.

Common tests: `KeptNumbersTest` 4, `NumberBlocksTest` 4, `SpawnTest` 9 and `PlayAreaTest` 3;
`PlaySessionScenesTest` +2; `OperationParityTest` adds each new block and changes kept numbers, templates and steady
movement by hand and by the agent tool with the same result. `SpawningUiTest` builds a template, its cleanup rule
and a moving-copy rule with taps on phone and desktop, then plays them.

On the Android API 36 emulator, `tools/android-spawn-smoke.py` creates the template, its cleanup rule and a spawner
with real taps, then checks that the copy moves down. Screenshots: [rules](media/android-spawn-rules.png),
[playing](media/android-spawn-play.png), [after moving](media/android-spawn-moving.png).

## Platform physics and camera follow (C14b)

The rest of [#88](https://github.com/aioanei/spawnkit/issues/88), for platformers and levels wider than the screen.
[Plan](plans/2026-11-23-c14b-physics-camera.md).

- **Body** (`gameplay:body`): speed across and down, gravity (50 by default; 0 floats, for games seen from above),
  bounce (0 to 1) and fastest fall (20), in world units per second. Rules can read and set them as `body_vx`, `body_vy`
  and so on.
- **Solid** (`gameplay:solid`): bodies stand on it and cannot pass through it. The builder's Platform piece is solid.
- **One physics system** (`gameplay:physics`) runs after rules and steady movement. For each body in scene order:
  1. *Ride:* a body that stood on a solid moves with that solid's movement since the last moment, however the solid
     moved: steady movement or Move blocks with the patrol pattern (a number for the direction, edges that flip it).
  2. *Settle:* a body overlapping a solid, for example walked into a wall by a Move block or met by a rising platform,
     is pushed out the shorter way; exactly in the middle, it goes on top.
  3. *Gravity,* never beyond the fastest fall.
  4. *Across, then down,* in steps no longer than the body (at most a quarter unit), so fast bodies cannot pass
     through thin walls. A solid stops the body at its edge, and the speed reverses times the bounce; small rebounds
     stop, so bouncing bodies come to rest. Landing while moving down means **on the ground**.

  Edges that only meet do not count, so a body on the ground walks freely. Only `+ - * /`, comparisons, `abs`, `min`,
  `max` and `floor` are used, and the body's on-the-ground flag and each solid's last position are checkpointed, so a
  run repeats exactly on every target and from a checkpoint.
- **Blocks:** **On the ground** (condition); **Jump** at a speed, only from the ground (15 jumps a little over 2 units
  with normal gravity); **Set speed** in a direction, for springs, knock-back and dashes. Walking stays the Move block.
- **Follow** (`gameplay:follow`): keeps its object at the first object with a tag (player by default), across, up and
  down, or both. On a camera, the view follows the player; Leaves the play area then uses the moving camera. It runs
  after physics, so the camera never lags a moment behind.

Limits: bodies collide with solids, not with each other (a body can also be solid, like a crate to stand on); rotation
does not turn boxes; "on the ground" means blocked while falling, so it assumes gravity pulls down, and a body with no
gravity rides platforms only once it has been pushed onto one. A body on a platform at the very start rides from the
second moment, once its footing is known. Leaves the play area removes objects already outside the camera's area at
the start, so in wide levels use it for shots, not for enemies placed further along.

Common tests: `PhysicsTest` 14, `PhysicsBlocksTest` 4 and `FollowTest` 5; `OperationParityTest` adds the three blocks
and the Body, Solid and Follow properties; `PhysicsUiTest` builds a platform, a player with a body and a Jump rule, and a
following camera by taps on phone and desktop, then plays it: the player lands, jumps with the on-screen button or Space,
and lands again.

On the Android API 36 emulator, `tools/android-physics-smoke.py` builds the same game with real taps and checks that the
player stands on the platform and jumps. Screenshots: [rules](media/android-physics-rules.png),
[playing](media/android-physics-play.png), [jumping](media/android-physics-jump.png).

## Catalog and rule operations (C7a)

[`RuleBlockCatalog`](operation-catalog.md#rule-block-catalog-coregameplay-ruleblockcatalog) describes every block:
its name, help, parameters with limits and starting values, and examples. The builder's block dialog is generated
from it, and agents receive the same entries as tool parameters. `RuleOperations` adds, changes, moves, duplicates
and removes rules and blocks as one `SetRules` step each, for the Rules panel and agents alike.
`StandardEngine.operations` is the combined operation catalog. Rule numbers in the catalog start at 1.

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

`RuleBlocksTest` (common, all core targets): every block round-trips; every block prepares against the standard
engine; non-block and re-identified handlers stay advanced and unchanged; block locations and descriptions (including
oversized IDs); a Score + collect game built only from blocks plays; text blocks update labels; timed conditions fire
on schedule.

## Not yet

- Blocks for sounds; rules with their own state. Copies start from the authored template, not its current state.
- Colors in rules and multiple tags per object. Solid objects and gravity come with C14b.
- A per-game random seed setting.
