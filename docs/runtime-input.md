# Deterministic input — R2d

Tracked by [issue #36](https://github.com/aioanei/spawnkit/issues/36). This is an R2 increment of the
[revised plan](plans/2026-11-20-engine-foundation-revision.md).

## Model

Games describe *what* the player can do, not which keys they press:

- `RuntimeBuilder.action(id, authoring)` registers an on/off input such as "jump".
- `axis(id, authoring)` registers an analog input in `[-1, 1]` such as "move left/right".
- `Runtime.inputActions` and `inputAxes` list both, with friendly names, for binding UIs and agents.

Hosts translate keyboards, gamepads and touch controls into an `InputFrame`: held actions, axis values and pointers.
They submit it with `World.submitInput(frame)` between ticks. The runtime never reads devices or time.

| Rule | Behavior |
|---|---|
| When a frame applies | It becomes the current frame at the start of the next `step()`. The previous current frame is kept for edge detection. |
| No new submission | The last frame stays in effect, so held input persists. |
| Reading | `world.input.isDown`, `wasPressed`, `wasReleased` (edges relative to the previous tick), `axis` (0 when absent), `pointers` and `previousPointers` (C4a, for detecting presses and releases). Pointer positions are world units: hosts convert screen positions through the active camera before submitting. |
| Validation | Rejected: submission during a tick or on a faulted world; actions or axes from another runtime; axis values outside `[-1, 1]` or non-finite; non-finite pointer coordinates; duplicate pointer IDs. |

## Replay and checkpoints

Because systems only see submitted frames, a recorded per-tick frame log replays to the same state hash. Snapshots
capture the current, previous and pending frames, so a restore taken between ticks continues with correct edges and
the already-submitted next frame. The input section is captured and hashed when the runtime registers actions or axes, or when any frame holds
pointers, so existing goldens are unchanged.

## Evidence

[`InputTest`](../core/runtime/src/commonTest/kotlin/dev/spawnkit/runtime/InputTest.kt) runs on JVM, Android host, JS,
Wasm and mingwX64. It covers:

- press/release edges and held carry-over;
- axis application;
- catalogs;
- every validation rule;
- submission during a tick;
- a recorded replay matching the live hash;
- a mid-recording restore with a pending frame matching the live run;
- a cross-target golden.

## Not yet

- Device bindings and rebinding UI live in the renderer and player hosts (R3/R6).
- Programs read input through `ProgramHostBuilder.inputs(actions, axes)` (R2e). It provides `isDown`, `wasPressed`,
  `wasReleased` and `axis`, addressed by action or axis ID. An unknown name faults the rule with `HostFailure`. An action or axis registered by a different runtime
  also faults the rule, not the world. Registering the four functions is atomic. Names
  are checked at run time, not when the program is prepared.
