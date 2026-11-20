# Runtime checkpoints and replay — R1b evidence

Tracked by [issue #23](https://github.com/aioanei/spawnkit/issues/23), following the merged [runtime PR #22](https://github.com/aioanei/spawnkit/pull/22).

## Boundary and usage

The headless runtime can capture a healthy world between ticks, restore it into fresh world/system instances and
compare canonical state fingerprints. This is an in-memory engine capability, not a JSON document compiler,
project persistence format, external plugin ABI or implementation of portable-program transaction rollback.

```kotlin
val snapshot = world.snapshot()
val fingerprint = snapshot.stateHash()
val resumed = runtime.restore(snapshot)
check(resumed.stateHash() == fingerprint)
resumed.step()
```

The original world remains independent. Repeated restores do not consume or mutate the checkpoint. A checkpoint
can restore only through its exact original frozen `Runtime`; another registry with similar type names is rejected
before constructing a world. Old entity handles remain owned by the original world. Query the restored world or
resolve references through state codecs to obtain its handles.

## Explicit native state contracts

Register `StateCodec<T>` as the optional third argument to `RuntimeBuilder.component`. It is required when an
attached value is captured. Unused registrations do not lose current state and do not prevent capture. Primitive
`Int`, `Long`, `Double`, `Boolean` and `String` codecs are available through `StateCodecs`; packages implement their
own codecs for arbitrary component types.

Every installed native system must explicitly implement `CheckpointSystem`, capturing and restoring all private
future-affecting state. Even a stateless system explicitly declares a `StateValue.Null` checkpoint. Lambda systems
are not assumed stateless. Missing component/system contracts fail with their IDs rather than silently dropping data.

Codecs and system hooks exchange generic `StateValue` scalars, sequences, records and entity references. They do not
enumerate gameplay mechanics. `StateCaptureContext.entity(handle)` validates and captures a live or stale same-world
reference; `StateRestoreContext.entity(state)` remaps it into the new world, retaining slot/generation semantics.
Cross-world references fail. Contexts expire and release their world references when their operation returns or fails.

Capture hooks must be pure with respect to simulation/external state. Restore hooks must initialize fresh values
and system state; factories must produce fresh systems. The runtime copies captured value collections immediately
and gives each restore fresh copies, protecting checkpoints from mutable codec scratch buffers and decoder inputs.
Native shared-object aliasing, external resources/services, closures and arbitrary private fields are not discovered
automatically. Packages must represent all such future-affecting state explicitly or decline checkpoint support.
Conformance tests establish a package's declared behavior; an interface cannot prove arbitrary native code correct.

## Captured state and failure semantics

- Attempted tick count; every entity slot's generation/alive status; free-slot stack order; installed component IDs;
  populated component values sorted by type/slot; and native system state in effective execution order.
- Allocator history is included because it affects future handles. A stale reference remains stale after restore,
  while a live reference resolves to the new world. Process/world identity is not part of the fingerprint.
- Capture is allowed only when idle and healthy. Capture during updates, command commit, another capture or a
  faulted world fails. Writes through the captured world's API and reentrant stepping are blocked during capture.
- Pure capture failure leaves engine-owned source state unchanged and the source usable. A restore failure returns
  no partial world, does not consume the checkpoint and does not mutate the source. Native callback side effects
  outside this contract are neither sandboxed nor automatically undone.
- `SnapshotLimits` bounds copied value count and nested depth; cyclic/over-deep trees fail instead of recursing
  indefinitely. Defaults: depth 64, one million values across all component/system payloads. Supported depth is
  1–256; budgets must be positive. These traversal limits do not preempt native callbacks or cap their allocations.

Existing native tick failures still fault the world without rolling back already applied native effects. A healthy
earlier checkpoint can supply a new world to restart. This mechanism does not weaken or substitute for the future
portable-program atomic segment, event/effect replay and budget contracts.

## Deterministic fingerprint and evidence

The v1 fingerprint uses FNV-1a 64-bit over a domain label, allocator/tick/profile state and all declared payloads.
Integers/lengths are mixed as eight little-endian bytes, strings as length plus UTF-16 code units, doubles by raw
IEEE-754 bits, records by sorted field names, and sequences by explicit order. Value tags distinguish kinds. Entity
references contribute slot/generation, not owner identities. This is a non-cryptographic regression fingerprint,
not proof against collisions, code identity, artifact authenticity or arbitrary nondeterministic native behavior.

`samples/runtimeProbe` is a separately compiled consumer. Its `CounterSystem` captures its private update count,
then a mid-run restored world resumes to the same values/tick/hash as uninterrupted execution. If that private
count were omitted, the resumed counter would diverge. A JVM-derived six-tick golden is pinned at
`-4909871966990312915L` and must match on every target; platform-specific goldens are forbidden.

Common tests also exercise mutable payload/buffer isolation, repeated restores, allocator replay, live/stale handle
remapping, foreign references, missing contracts, source/restore failures, context expiry, capture boundaries,
canonical registration/map ordering, signed zero and traversal limits. See [PROGRESS.md](../PROGRESS.md) and the PR
for actual target results. Legacy platformer source and its independent golden remain unchanged.

R1 remains open: storage/library compatibility and performance comparison, portable-program/interpreter and
effect/budget proofs, plus richer capability metadata are still required. There is no new dependency, shipped
no-code editor, disk checkpoint format or claim that arbitrary external native state can be snapshotted implicitly.
