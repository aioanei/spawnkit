# Scene hierarchy, world resources and seeded random — R2b

Tracked by [issue #32](https://github.com/aioanei/spawnkit/issues/32). This is an R2 increment of the
[revised plan](plans/2026-11-20-engine-foundation-revision.md).

## Hierarchy

The hierarchy is an optional, ordered parent/child relationship between objects, for composition and editor
outlines. It does not change query order or execution order: queries stay in slot order, and systems follow the
schedule. Spatial transform inheritance is R3 presentation work built on top of it.

| Operation | Behavior |
|---|---|
| `setParent(child, parent, index = -1)` | Appends, or inserts at `index`. Reparenting under the same parent reorders. `parent = null` makes a root. |
| `parent(e)`, `children(e)` | The parent, or `null`; the children in authored order. |
| Invalid edits | Self-parenting, cycles, a position out of range, and stale or foreign handles are rejected with no change. |
| Positions | `index` counts positions after the moved child has been removed, so reordering `[a, b, c]` under the same parent accepts `0..2`. `-1` appends; other negative values are rejected. |
| `destroy(e)` | Detaches `e`; its children become roots in their existing order. A reused slot starts without hierarchy. |
| `destroyTree(e)` | Destroys descendants depth-first in child order, then `e`. |
| `WorldCommands.setParent` / `destroyTree` | Deferred to commit. The command is skipped if the child, parent or target was destroyed earlier in the same batch. A cycle introduced only by command ordering fails at commit like any other native command error. |

`destroyTree` recurses once per level of hierarchy depth; scene trees are expected to be shallow.

## World resources

`RuntimeBuilder.resource(id, authoring, checkpoint, create)` registers a typed per-world singleton, such as settings,
score tables or random generators. Each world calls `create` once, so worlds never share values.
`World.resource(type)` returns the world's value and rejects another runtime's resource type. Codecs and checkpoint hooks must not read or change resources; checkpoint contexts do not expose the world. Snapshots require a
codec for every registered resource and fail explicitly otherwise, so resource state is never silently dropped.

## SeededRandom

`SeededRandom` is SplitMix64 using only wrapping 64-bit integer operations:

- `nextLong()`;
- unbiased `nextInt(bound)`, using rejection sampling;
- `nextDouble()` in `[0, 1)`, with 53 bits and exact power-of-two scaling.

`SeededRandom.codec` checkpoints its exact position. It is not cryptographically secure. For seed 42, the first
`nextLong()` is `-4767286540954276203`, the SplitMix64 reference value.

## Checkpoints and compatibility

Snapshots capture ordered child lists and resource values, and restore them into fresh worlds. Entity references are
remapped as before. The hash includes these sections only when they are non-empty, so every existing golden is
unchanged. That covers the storage equivalence golden, the R1b consumer golden and the R1d program golden.

## Evidence

[`HierarchyResourceTest`](../core/runtime/src/commonTest/kotlin/dev/spawnkit/runtime/HierarchyResourceTest.kt) runs
on JVM, Android host, JS, Wasm and mingwX64. It covers:

- ordering, insertion, reordering and reparenting;
- cycles, self-parenting, stale and foreign handles, and positions out of range;
- destroy versus `destroyTree`, and slot reuse;
- deferred commands, including skipped destroyed targets;
- per-world resources;
- snapshot/restore/replay with hierarchy and resources;
- missing-codec failures;
- cross-target goldens for the random sequence (longs, bounded ints, double bits) and a resource and hierarchy
  checkpoint hash.

## Scene templates and instance identity (R2f)

Tracked by [issue #40](https://github.com/aioanei/spawnkit/issues/40).

A `SceneTemplate` is a reusable arrangement of authored objects, such as a scene or prefab. Each `TemplateObject`
has a stable ID, an optional parent ID and `ComponentSeed` factories. Templates reject:

- duplicate IDs;
- unknown parents;
- parent cycles.

`World.instantiate(template, parent)` creates the objects in declaration order with fresh component values. It
reproduces the hierarchy, places roots under `parent`, and returns a `SceneInstance` that maps authoring IDs to this
instance's handles. Components are validated before anything is created. `WorldCommands.instantiate` does the same
at commit and passes the instance to a callback.

Every created entity records `ObjectIdentity(templateId, instance, objectId)`, available from `World.identity`. The
editor and diagnostics can therefore map a runtime object back to what the creator authored. Instance numbers are
per world and start at 1. Destroying an entity clears its identity. The counter and identities are checkpointed and
hashed once any template has been instantiated.

Evidence: [`SceneTemplateTest`](../core/runtime/src/commonTest/kotlin/dev/spawnkit/runtime/SceneTemplateTest.kt)
covers:

- validation and atomic rejection;
- independent instances;
- hierarchy and parenting;
- identities, and identity clearing on destroy;
- commands-based instantiation;
- snapshot/restore/replay;
- a cross-target golden.

Asset references and authoring-workspace persistence of templates come with R4/R5.
