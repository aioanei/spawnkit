# Capability descriptors and the generic inspector — R2a

Tracked by [issue #30](https://github.com/aioanei/spawnkit/issues/30). This is the first R2 increment of the
[revised plan](plans/2026-11-20-engine-foundation-revision.md): it separates executable signatures from authoring
presentation metadata.

## Why

The guided editor has to show usable controls for every installed capability, including components from extension
packages, without a hard-coded inspector per component. Executable code keeps using its own typed Kotlin component
classes. Descriptors add what a creator needs to see and what an editor or agent needs in order to edit safely:

- friendly names, help and examples;
- the value kind and its constraints;
- defaults;
- basic/advanced visibility.

No reflection or serializer metadata is involved, so descriptors work identically on every target.

## Registering

```kotlin
val mover = builder.component<Mover>("demo:mover", AuthoringInfo("Mover", "Moves the object", "Motion"))
builder.describe(mover) {
    create { Mover() }                       // fresh default value for "Add component"
    property(
        "speed",
        ValueKind.WholeNumber(min = 0, max = 20, unit = "tiles/s"),
        AuthoringInfo("Speed", "How fast it moves"),
        default = 5L,
        read = { it.speed },
        write = { m, v -> m.copy(speed = v) },
        examples = listOf("5 for walking", "12 for running"),
    )
}
```

Value kinds, each mapped to an obvious generated control:

| Kind | Kotlin value | Constraints | Suggested control |
|---|---|---|---|
| `WholeNumber` | `Long` | min/max/step/unit | stepper or slider |
| `Decimal` | `Double` | finite, min/max/unit | number field or slider |
| `YesNo` | `Boolean` | — | switch |
| `Text` | `String` | max length, single/multi-line | text field |
| `Choice` | `String` (option ID) | must be one of the options | dropdown or segmented control |
| `ObjectReference` | `ObjectRef` | live, same-world, has the required components; `null` means "not set" | object picker (`referenceCandidates`) |

`describe` rejects the following registrations:

- the component belongs to another runtime;
- the component is already described;
- registration has closed;
- a property ID is invalid or duplicated;
- a default violates its kind;
- the `create {}` factory is missing;
- a reference filter names another runtime's component.

## Inspecting and editing

`Runtime.catalog` lists described components in ID order, for palettes and agent discovery.

`Runtime.inspect(world, entity, visibility)` returns the described components and current values. Components come in
catalog order and properties in declaration order. `Visibility.Basic` hides advanced components and properties.
Undescribed components are omitted rather than guessed.

`Runtime.edit` comes in two forms: a typed descriptor, or component and property IDs for editors and agent tools.
Both validate before changing anything. These cases produce an `EditResult.Rejected` with a plain-language message
naming the property:

- the object is stale;
- the component is missing;
- the value is out of range or not finite;
- the text is too long, or contains a line break where one isn't allowed;
- the choice is unknown;
- the Kotlin value type is wrong;
- the reference is stale, foreign or filtered out;
- the component or property ID is unknown.

Edits apply only while the world is healthy and paused between ticks. A running, faulted or checkpointing world
rejects the edit before any write lambda runs, so mutable components are never half-edited. ID-based input is
converted identically on every target: decimals accept `Int`, `Float` or `Double`, while whole numbers need `Long`.
A `create {}` factory must start every property at its declared default; `describe` checks this.

A successful edit returns `EditResult.Applied(previous)`, which is enough for the R4 editor transactions to build
undo. An undo value can be a reference to an object that has since been destroyed;
reapplying it is then rejected like any other stale reference. `addComponent` attaches a fresh factory value.

## Evidence

- [`DescriptorTest`](../core/runtime/src/commonTest/kotlin/dev/spawnkit/runtime/DescriptorTest.kt) covers:
  - registration validation and catalog order;
  - inspector values and visibility;
  - accepted and rejected edits for each value kind, with rejections leaving the world unchanged: range, NaN and
    infinity, cross-target decimal coercion, text length and line breaks, choices and wrong value types;
  - edits rejected on a faulted world without running write lambdas, and factory/default mismatches rejected;
  - undo values;
  - reference validation (live, foreign, missing component, destroyed) and picker candidates;
  - factory-based add, duplicates and stale objects.
- [`ExtensionInspectorTest`](../samples/runtimeProbe/src/commonTest/kotlin/dev/spawnkit/samples/runtimeprobe/ExtensionInspectorTest.kt)
  shows that the separately compiled consumer's `Counter` is discovered, added, inspected and edited through the
  generic model alone. Its own system then runs on the edited value.

## Not yet

- The Compose inspector that renders these controls arrives with the first editor surface (R4).
- Program bindings can be derived from descriptors (R2c, `ProgramHostBuilder.properties`). Object-reference
  properties are not bound to programs yet.
- Units are display text, with no conversion. Records/lists of values, colors, assets and resource references come
  with R2 resources and R3 presentation.
- Edits are individual operations. Transactions, grouping and undo stacks belong to `core/editing` in R4.
