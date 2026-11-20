# Portable program executor — R1d screening and proof

Tracked by [issue #28](https://github.com/aioanei/spawnkit/issues/28). This completes the program/host-effect half
of the remaining R1 work in the [revised plan](plans/2026-11-20-engine-foundation-revision.md). The storage half is
[R1c](runtime-storage.md).

## Decision

Creator logic runs on **one SpawnKit-owned executor in common Kotlin**, in the new `core/program` module. It
type-checks a typed program tree and lowers it to resumable stack bytecode. The tree is the shared form behind
visual When / If / Do rules, later node graphs and agent-authored behaviors. No published embedded runtime passed
the required-target screen. The executor runs identically on every core target and has one cross-target golden.

This is a proof slice, not the finished R4 language. It establishes the execution, failure, effect and checkpoint
semantics that the editor and agents will build on. Records, maps, richer queries, debugging traces and the visual
editor are later work (see [Gaps](#evidence-gaps-and-follow-up)).

## Candidate screening

Required targets: jvm, android, iosArm64, iosSimulatorArm64, js, wasmJs, mingwX64, linuxX64. Each candidate is
rejected at its first failed mandatory criterion. Artifacts were inspected on Maven Central on 2026-11-20, using
directory listings, `maven-metadata.xml` and Gradle `.module` variant attributes.

| Candidate | Artifact (latest) | Published targets | Result |
|---|---|---|---|
| QuickJS | `io.github.dokar3:quickjs-kt` 1.0.18 (2026-11-07) | jvm, androidJvm, iOS arm64/simulator/x64, linux, macOS, mingwX64, tvOS | ❌ no js or wasmJs |
| Guest Wasm (Chasm) | `io.github.charlietap.chasm:chasm` 2.5.0 (2026-11-15) | jvm, androidJvm, iOS arm64/simulator, linux, macosArm64, mingwX64 | ❌ no js or wasmJs for the runtime artifact; only an internal `vm` module publishes web variants |
| Lua (LuaJ port) | `com.soywiz.korlibs.luak:luak` 4.0.0-alpha-2 (last 2023-01-24) | jvm, android, js, iOS, linuxX64, mingwX64, macOS | ❌ no wasmJs; unmaintained alpha |
| Lua (LuaJ port) | `com.github.minigdx:luak` 1.2.0 | jvm, js | ❌ no Native or wasmJs |
| JVM Kotlin scripting | — | JVM only | ❌ excluded by the plan; not a portable executor |
| **Common Kotlin interpreter** | `core/program` (this work) | all core targets | ✅ selected |

Licenses, performance and binding quality were not assessed for rejected candidates, because screening stops at
the first failed mandatory criterion. Using the browser's own JavaScript engine on web while running QuickJS
elsewhere would create two executors with different semantics. The plan forbids that.

## Language slice

All types and values are immutable, which keeps rollback and checkpoint copies alias-free.

- **Types and values:**
  - whole number (64-bit, wrapping);
  - decimal (only `+ - * /` and comparisons);
  - yes/no;
  - text;
  - object (entity handle);
  - nothing;
  - typed lists.
- **Statements:**
  - declare and assign;
  - state writes;
  - property writes;
  - if/else, while, for-each;
  - return;
  - emit an event;
  - wait N ticks;
  - request an effect;
  - evaluate an expression.
- **Expressions:**
  - literals and variables;
  - arithmetic, comparison and short-circuit logic;
  - user function calls with parameters and returns;
  - typed host-function calls;
  - state reads;
  - entity property reads;
  - queries over properties (returned in slot order);
  - `Self`;
  - list build, append, size and index.
- **Program:** state fields with initial values, functions, and handlers triggered on every tick or on an event.
  Handlers of an event with a payload bind it to `payload`.

Every node carries a `SourceRef(nodeId, label)`. Preparation (`prepareProgram`) returns either a `PreparedProgram`
or every `Diagnostic`, phrased in creator terms ("Expected whole number for state 'count' but found text").

Preparation checks:

- types for operators, assignments, state, properties, arguments, returns, events and effects;
- unknown names;
- duplicate declarations;
- arity;
- that typed functions return on every path.

Nothing executes from an invalid program.

## Host capabilities

Packages expose capabilities through `ProgramHostBuilder`. Every capability is explicit and typed; programs cannot
reach arbitrary engine APIs.

- `property(name, component, type, read, write)` binds a component as a typed property.
- `function(name, parameters, returns, cost, implementation)` registers a trusted Kotlin function with a
  deterministic fuel `cost`. It throws `ProgramHostException` to fail.
- `event(name, payload)` and `effect(name, payload)` declare messages.

### Properties from component descriptors (R2c)

`ProgramHostBuilder.properties(descriptor, prefix)` binds every supported property of a component descriptor
([R2a](runtime-descriptors.md)) as a program property named `prefix + id`, reusing its friendly metadata:

| Descriptor kind | Program type |
|---|---|
| Whole number | `IntegerType` |
| Decimal | `DecimalType` |
| Yes/no | `BoolType` |
| Text, choice | `TextType` |

Object references are skipped until nullable reference semantics are designed. Each property-write statement checks
the descriptor's value kind as it runs. An out-of-range number, unknown choice, over-long text or line break in single-line text
faults the segment with `FaultKind.InvalidValue` at the write's node and rolls back the whole segment. Only writes are checked: a value that native code stored out of range reads normally.
One declaration therefore drives
the generated inspector, visual rules and agents, with identical constraints. Evidence: `ProgramDescriptorBindingTest`.

### Input functions (R2e)

`ProgramHostBuilder.inputs(actions, axes)` gives rules typed access to the deterministic [input model](runtime-input.md).
`isDown`, `wasPressed` and `wasReleased` take an action ID and return yes/no; `axis` takes an axis ID and returns a
decimal. Host functions see the world's `InputView` through `HostCallContext.input`. Recorded frames replay identically
through rules. Evidence: `ProgramInputTest`.

## Execution semantics

`ProgramFeature` installs the `program:behavior` component and the `program:execute` system. Each tick, entities are
processed in slot order. For each one:

1. Resume due waits, oldest first.
2. Run every-tick rules in declaration order.
3. Run rules for events delivered this tick.

Events emitted during tick *t* are delivered on tick *t*+1. All inboxes are drained before any program runs, so
delivery does not depend on processing order.

**Atomic segments.** A segment is one rule run until it ends, waits or faults. Its work is buffered:

- state writes;
- property writes (later reads in the same segment see them);
- emitted events;
- effect requests.

All of it is applied only when the rule ends or reaches `wait`. A fault discards that segment's buffered work. It
then records a `ProgramFault(kind, source, message, tick)` and stops that instance. Segments committed earlier stay,
and the world itself never faults.

Structural world changes still follow `core/runtime`'s deferred rules. Host functions are trusted. A
`ProgramHostException` or a wrongly typed result faults the segment; any other exception propagates as a native
failure, which faults the world.

**Bounded execution.** All limits are deterministic counts set in `ProgramLimits`:

| Limit | Default |
|---|---|
| Fuel per segment (one per instruction plus host costs) | 100,000 |
| Call depth | 64 |
| List elements created per segment | 100,000 |
| Maximum list size | 10,000 |
| Suspended waits per instance | 32 |
| Events plus effects per segment | 256 |
| Characters in one text value | 10,000 (text concatenation also counts toward the allocation budget) |
| Events waiting in one inbox | 256; overflow stops the receiving instance, so event cascades cannot multiply |
| Rule nesting depth at preparation | 64; deeper trees get a diagnostic instead of overflowing the stack. Text constants longer than the text limit are also rejected |

There is no wall-clock watchdog. Fault kinds:

- `FuelExhausted`, `CallDepthExceeded`, `AllocationExceeded`, `MessageLimitExceeded`;
- `InvalidArithmetic` (division by zero, or a wait shorter than one tick);
- `IndexOutOfRange`;
- `MissingProperty` (the object no longer exists, or lacks the component);
- `HostFailure`.

`Long.MIN_VALUE / -1` is defined to wrap identically on every target instead of relying on platform traps.
Equality follows IEEE-754 for decimals (NaN ≠ NaN, 0.0 = -0.0), including inside lists, and its fuel cost scales
with value size. Literals and state initial values are checked deeply: list items must match the declared element
type, and object constants are rejected even inside lists. Property-reader and host-function results are checked
the same way at run time; a mismatch faults with `HostFailure` instead of escaping as a crash.

**Waits and checkpoints.** `wait` commits the segment and stores the continuation as plain data: frames (unit,
program counter, locals) plus the operand stack. A `ProgramInstance` is checkpointed by its R1b `StateCodec`,
including:

- committed state and suspended threads;
- thread and segment counters;
- fuel;
- the event inbox;
- unacknowledged effects;
- any fault.

Entity values remap through the restore context. Restore re-associates each instance with its registered
`PreparedProgram` by ID and code fingerprint. A missing or changed program fails with a clear message. The
fingerprint hashes canonical instruction text plus declarations (state names and initial values, handler
triggers, function signatures), with decimals written as raw bits, so it does not depend on the platform. Restore
also checks that the checkpointed state matches the program's field count.

**Effects.** A committed request gets ID `"{slot}.{generation}:{segment}:{n}"`. Replay from a checkpoint therefore
reproduces the same IDs, so an external sink can deduplicate. The host reads `pendingEffects` and calls
`acknowledgeEffects(id)` after delivery. A rolled-back segment never produces an effect, and its events are never
delivered.

## Evidence

Tests are in [`core/program/src/commonTest`](../core/program/src/commonTest/kotlin/dev/spawnkit/program). Each test
runs unchanged on JVM, Android host, Node JS, Node Wasm and mingwX64 (30 tests per target); Linux and iOS run in CI.

- **Acceptance fixture** (`ProgramCheckpointTest`):
  - The `collector` program calls a user function `weight` for every queried object.
  - It accumulates into state, collects a list, emits `collected`, waits 3 ticks, then edits the collected objects
    and requests an `analytics` effect.
  - A separate `scorer` program reacts to the event through a host function.
  - Checkpoints taken at ticks 1, 2, 4 and 5 restore and replay to tick 12. Ticks 1 and 2 are mid-wait; ticks 4 and
    5 come after a resume. Replay matches the uninterrupted run's state hash, pending effects (IDs and payloads)
    and fuel.
  - Golden, recorded on JVM and identical on every target: fuel `3044`, hash `-1990361367860152568`.
- **Diagnostics** (`ProgramCompileTest`): each error is reported at its exact node, and all errors are reported in
  source order. Covers type errors, an unknown variable, host argument types, an unknown property, a missing return
  path, wrong arity, event payload mismatches and an unknown event trigger.
- **Faults and rollback** (`ProgramExecutionTest`):
  - A runaway loop faults at the loop node. State, property writes and effects from two earlier segments remain,
    and the world keeps running.
  - Unbounded recursion faults with `CallDepthExceeded` at the call node.
  - Unbounded list growth faults with `AllocationExceeded`.
  - A failing host call rolls back state, property, event and effect writes; the event is not delivered.
  - Wrongly typed host results, division by zero and an out-of-range index fault as expected.
  - Reads see the segment's own buffered writes.
  - Fuel charges are deterministic and include host costs.
  - Effects are acknowledged in order.
- **Hardening** (`ProgramHardeningTest`), added after independent review:
  - deep type checks of list literals and state, including object constants hidden inside lists;
  - bounded text growth;
  - the inbox limit stopping a self-amplifying event cascade;
  - fingerprints changing with triggers, state fields and initial values;
  - wrongly typed property readers faulting instead of crashing;
  - suspended-wait and message limits;
  - `MIN / -1` and `MIN % -1`;
  - short-circuit `and`/`or` skipping the right side;
  - IEEE NaN equality;
  - zero-tick waits;
  - stale objects faulting with `MissingProperty`;
  - slot reuse across nested blocks and for-each;
  - excessive nesting reported as a diagnostic.

## Evidence gaps and follow-up

- **Interpreter performance** is not benchmarked yet. Operations are `Op` objects in a `when` dispatch, with no
  packed encoding or optimization. Measure on representative rules before R9.
- **Records/maps, text functions and decimal↔whole-number conversion** are not in the slice. The same
  checker/VM/codec patterns extend to them in R4.
- **Debugging.** There are no traces or step debugging yet. Faults already carry source nodes for editor
  highlighting.
- **Effect requests** are queued on the instance; actual service adapters (analytics, ads, purchases) arrive with
  R8 host services.
- **Instance lifecycle.** An instance should be created per entity with `ProgramFeature.instantiate`; this is a
  documented contract, not enforced. A faulted instance stays stopped until replaced.
- **Devices.** Android device and iOS execution are CI and host-test evidence only, not device runs.
- **Preparation is recursive.** The nesting limit of 64 counts statements and expressions together. It leaves about
  1.6× headroom on the mingwX64 debug test binary: 100 levels passed and 128 overflowed its stack. Long left-nested
  sums beyond 64 terms are rejected with a diagnostic. An iterative compiler, or smaller frames, would lift this
  limit; do that before graph editors generate large expressions.
- **Event flooding is a deliberate choice.** Another entity can stop a receiver by sending it more than
  `maxInboxEvents` events in one tick. The outcome is deterministic and explained by the fault. A drop-oldest
  policy is the alternative if that proves too strict.
