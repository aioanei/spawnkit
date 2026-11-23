# Operation and rule block catalogs — C7a (R7 subset)

Tracked by [issue #69](https://github.com/aioanei/spawnkit/issues/69), first requirement of contest item C7. Decision:
[ADR-0009 agentic parity](adr/0009-agentic-parity.md). Everything a creator does by hand in the builder is described
once, in a catalog. The builder's controls and the agent tools both come from that catalog, so neither can do
something the other cannot.

## Parameter vocabulary (`core/editing`, `Catalog.kt`)

A `Parameter` has an ID (also the agent's argument key), a name, help, a `ParameterType`, an optional default (the
value controls start with) and `required`.

| `ParameterType` | Values | Generated builder control |
|---|---|---|
| `Value(kind, suggestions)` | a descriptor `ValueKind`: whole number, decimal, yes/no, text, choice or color | stepper, decimal stepper, switch, text field or tag picker, chips, swatches |
| `Scene`, `Object`, `Component`, `Property` | IDs, checked against the project and installed descriptors | chosen by selection in the builder |
| `PropertyValue` | checked by the chosen property's own kind | the inspector's property control |
| `Components` | `ComponentDoc`s with property values (the palette's presets) | the palette |
| `OneOf(discriminator, variants)` | `Picked(variant, arguments)`, for example a rule block or "which object" | variant chips plus the variant's fields |
| `ListOf(item, maxSize)` | ordered lists, for example a rule's Do blocks | the rule card |

`checkArguments` is the single checker. It normalizes values (JSON whole numbers become decimals where needed,
`#RRGGBB` becomes an ARGB color, choice labels become option IDs) and explains problems in plain language:
"There is no object 'obj-99' in Main scene", "Seconds: Choose a whole number from 1 to 86400". Unknown argument keys
are refused, which catches agent mistakes early.

## Operation catalog

An `OperationSpec` holds an ID, `AuthoringInfo` (name, help, category), parameters, examples and a builder that turns
checked arguments into an `EditOperation`. The editor validates the operation again when it is applied.
`StandardEngine.operations` combines the catalogs:

| Category | Entries | Package |
|---|---|---|
| Project | `rename_project` | `EditingOperations` |
| Scenes (C9) | `add_scene`, `rename_scene`, `remove_scene`, `move_scene` (position 1 is the start scene) | `EditingOperations` |
| Objects | `add_object`, `remove_object`, `rename_object`, `duplicate_object` | `EditingOperations` |
| Components | `add_component`, `remove_component`, `set_property` | `EditingOperations` |
| Release (C12) | `set_ads` (banner on the title screen, interstitial between scenes, scene changes apart) | `EditingOperations` |
| Images (C15) | `add_image` (Base64 PNG), `rename_image`, `remove_image` (refused while a sprite shows it) | `EditingOperations` |
| Grouping (C15) | `move_into` (into a group, or back to the top level, keeping the object where it is on screen) | `ObjectOperations` (`core/gameplay`) |
| Rules | `add_rule`, `add_block`, `change_block`, `remove_block`, `move_rule`, `duplicate_rule`, `remove_rule` | `RuleOperations` (`core/gameplay`) |

Operations join the catalog together with their builder controls, never for agents alone: the scene operations in C9
with the scene list, and `move_into` in C15 with "Move into…" in the inspector and dragging in the scene list. It
applies `ReparentObject` and sets the object's position and scale relative to the new group, as one undo step. `OperationCatalogTest` classifies every
`EditOperation` subtype with an exhaustive `when`, so a new operation does not compile until it is placed.

`core/gameplay` now depends on `core/editing`, as any extension package that adds editor capabilities will. Rule
edits live next to the blocks because block rules are renumbered by position. A generic move in `core/editing` would
leave problems pointing at the wrong rule.

## Rule block catalog (`core/gameplay`, `RuleBlockCatalog`)

Every `Condition` and `Action` has one `BlockSpec`. It holds an ID (`Touching`, `ChangeNumber`, …), part, name,
help, category, parameters with limits and starting values, and examples. `create(arguments)` checks the arguments
and builds the typed block, and `arguments(block)` is its inverse. `conditionType`, `actionType` and `blockType` are
`OneOf` types built from the entries, so a new block appears in the dialog and in agent tools without new screen or
agent code. "Which object" is a `OneOf` of `self` and `tagged` (with a tag).

`RuleOperations` provides typed rule edits (`addRule`, `removeRule`, `moveRule`, `duplicateRule`, `addBlock`,
`changeBlock`, `removeBlock`). Each one returns a `BuildResult` holding one `SetRules` step. Advanced rules are kept
unchanged, and block rules are renumbered by position, so problems keep pointing at the block the builder shows.
The Rules panel calls these functions, and the catalog entries call the same functions after checking arguments. In
the catalog, rule and block numbers start at 1, as the builder shows them. `part` is `when`, `and_if` or `do`.

## Agent tools (`ToolSchema.kt`)

`OperationSpec.toolDefinition(runtime)` derives a framework-neutral `ToolDefinition`: the name, a description with
examples, and a JSON Schema of the input. Component choices are enumerated from the installed runtime, and a `OneOf`
becomes a `oneOf` of objects keyed by its discriminator (`block`, `kind`). `buildFromJson` decodes a tool call and
builds it exactly as the builder path does. `encodeArguments` is the inverse, used for recording and replay. The
Koog layer (C7b) adapts these definitions; it does not hand-write a tool per operation.

```json
{"scene": "scene-1", "object": "obj-3",
 "when": {"block": "Touching", "tag": "player"},
 "do": [{"block": "ChangeNumber", "target": {"kind": "tagged", "tag": "score"}, "amount": 1},
        {"block": "ShowNumber", "target": {"kind": "tagged", "tag": "score"}},
        {"block": "RemoveSelf"}]}
```

## Builder changes

- The block dialog is generated from `RuleBlockCatalog`: kind chips, help, fields, limits and starting values. Test
  tags follow parameter IDs (`kind:<block>`, `<parameter>:<option>`, `inc:<parameter>`, `target:<tag>`), so existing
  rule tests still drive it.
- Rule cards gain a "⋯" menu with Move up, Move down and Duplicate. Advanced rules can be moved, duplicated and
  deleted. Their blocks stay read-only.
- Every rule edit is one undoable step with a specific label, such as "Add a block to rule 1 of Coin".

## Evidence

- `OperationCatalogTest` (`core/editing`, 5 tests). Every entry is described and derives a tool whose schema matches
  its parameters. Every editor operation is covered or has a recorded reason. Arguments and JSON build the
  operations the builder uses. Bad arguments are refused in plain language. Variants, lists and choices round-trip
  through JSON.
- `RuleCatalogTest` (`core/gameplay`, 6 tests). Every block has one entry whose arguments round-trip and whose
  defaults make a block. Limits and wrong parts are explained. A literal JSON `add_rule` builds exactly the compiled
  Score + collect rule and undoes in one step. "Make coins worth 5" works through `change_block`. Moving and
  duplicating renumber block rules and keep advanced rules.
- `OperationParityTest` (`app/shared`, JVM Compose). For each of the 19 catalog entries (15 in C7a, plus the four scene entries in C9) it does the change with real
  builder taps in one editor and with the derived JSON tool in another. Both give the same workspace, rule problems,
  capability check, undo label and project after undo. A catalog entry without a case fails the test.

## Not yet (later C7 increments)

- The assistant UI (C7c). The `ai` module with staged changes, sessions, traces, recording, replay and the Koog
  adapter is in [AI Studio core](ai-studio.md) (C7b).
- Enabling and disabling rules, which needs a stored flag in the project format.
- Agent-authored advanced rules (portable programs), and explanations from play traces.
