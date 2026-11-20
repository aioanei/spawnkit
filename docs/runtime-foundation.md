# Headless runtime foundation — R1 evidence

Tracked by [issue #21](https://github.com/aioanei/spawnkit/issues/21), following the merged [plan PR #20](https://github.com/aioanei/spawnkit/pull/20).
The next increment, [issue #23](https://github.com/aioanei/spawnkit/issues/23), adds [explicit checkpoints and replay](runtime-checkpoints.md).

## Implemented boundary

`core/runtime` is a pure Kotlin Multiplatform module with no dependency on `core/model`, `core/engine`, Compose,
document decoding or a third-party ECS. It is the first R1 evidence implementation, with provisional public APIs.
The existing document-driven platformer remains in `core/engine`, unchanged, while comparison/migration proceeds.
Separating this concrete module avoids making a general runtime import the legacy gameplay schema.

`samples/runtimeProbe` is compiled separately and depends only on the public runtime API. It registers its own
`Counter` component, friendly metadata and a stateful native system, then runs independent worlds. It proves an
external consumer can extend execution; it is not a claim of a shipped third-party marketplace package or editor UI.

## Direct construction and execution

```kotlin
val builder = RuntimeBuilder()
val position = builder.component<Double>("example:position", AuthoringInfo("Position"))
builder.system("example:move") {
    WorldSystem { world, _ ->
        for (entity in world.entities(position)) {
            val old = checkNotNull(world.get(entity, position))
            world.set(entity, position, old + FIXED_DELTA_SECONDS)
        }
    }
}
val world = builder.build().createWorld()
val entity = world.spawn()
world.set(entity, position, 0.0)
world.step()
```

This is developer SDK documentation. The future guided creator UI will invoke the same capabilities without
requiring creators to write this Kotlin or enter technical identifiers. A completely empty world also steps.

## Execution and mutation contracts

- Entity handles include a world identity, slot and generation. Destroyed or foreign handles cannot address a
  replacement entity. Slot reuse is last-freed-first; queries return ascending live slots and snapshot their membership.
- Component keys are typed and registry-scoped. Duplicate IDs, foreign registry keys and registration after build
  are rejected. The current metadata includes name, description and category; richer property/forms metadata is future work.
- System factories create per-world instances; extension authors must return fresh mutable state rather than share
  a singleton. Phases order BeforeUpdate → Update → AfterUpdate. Explicit before/after dependencies are validated
  at build; missing targets, cycles and phase contradictions fail. Independent ties use lexical stable IDs.
- Hosts call `step()` for a 1/60 s tick. There is no implicit frame clock, player, gravity, goal, input action list or
  genre event vocabulary. Tick counts attempted updates, including one that fails.
- Existing component values may be replaced immediately, so later systems observe earlier writes. Spawn, destroy,
  attach and detach during updates must use the per-tick `WorldCommands`. They commit after all systems, in recorded
  order. A target destroyed earlier in the batch is skipped by later commands; foreign/stale inputs fail at recording.
- Spawn initialization runs at commit, and may attach components through ordinary world APIs. Command recording
  expires before commit; retained queues cannot enqueue more work or be reused in a later tick.
- Worlds are confined to one caller thread. Component objects, initialization callbacks and native systems are
  trusted code. They are not sandboxed and must finish in bounded time; they can intentionally share mutable values,
  so callers must supply fresh component instances when isolation is intended.
- A native update/commit failure is rethrown and faults the world. Pending commands are discarded; previous direct
  writes and already committed structural effects remain inspectable. Further mutation/stepping fails, and
  `failedSystem` names the origin, including failures in its queued initializer. Create a fresh world to restart.
  An earlier healthy checkpoint can create a new world for restart. This does not implement or weaken the future
  portable-program atomic segment/rollback contract.

## Evidence and remaining R1 work

The common tests cover player-free execution, typed component lifecycle, stale/foreign handles, registry ownership,
stable queries, registration closure, phase/dependency ordering, deferred edits, queue expiry, native failures and
independent state from the separately compiled consumer. Run:

```text
./gradlew :core:runtime:jvmTest :samples:runtimeProbe:jvmTest
./gradlew spotlessCheck allJvmAndWebTests localNativeTests
```

The aggregate adds Node JS/Wasm, Android host and available native targets through the existing convention plugin;
the macOS workflow runs iOS simulator tests. `samples/**` changes now trigger that workflow as well.
See [PROGRESS.md](../PROGRESS.md) and the implementation PR for actual results; a configured target is not itself
evidence of a successful run.

R1 is deliberately **not complete**. This increment's simple component store is a comparison candidate, not a
performance-based production selection. Fleks was inspected through its primary [repository/license](https://github.com/Quillraven/Fleks)
and [2.16 release](https://github.com/Quillraven/Fleks/releases/tag/2.16); no dependency or license text was incorporated
into runtime source. Its tagged [Native conventions](https://github.com/Quillraven/Fleks/blob/2.16/build-logic/src/main/kotlin/fleks/kmp-native.gradle.kts)
declare Linux/Windows/macOS targets but comment out iOS targets, while its [web conventions](https://github.com/Quillraven/Fleks/blob/2.16/build-logic/src/main/kotlin/fleks/kmp-js.gradle.kts)
declare Wasm rather than a JS target. These source declarations do not establish the required published JS/iOS
variant compatibility; do not infer it from the project's multiplatform label. Artifact/adapter compatibility,
state capture and performance evidence remain necessary before selecting or rejecting a particular version/fork.

Explicit in-memory component/private-system capture, fresh-world restore and replay fingerprints are implemented
in [R1b](runtime-checkpoints.md), with missing state contracts rejected. Still required: portable-program/interpreter
comparison and effect/budget proofs, richer registered property/function
capabilities and measured library/backend selection. This API alone does not provide a generality proof across
three games, a visual editor, no-code behaviors, novice-user acceptance, physics, publishing or marketplace execution.
