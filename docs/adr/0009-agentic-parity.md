# ADR-0009: Agentic parity — everything a creator does by hand, an agent can do from a prompt

Status: **accepted** (owner requirement, 2026-11-20). Extends [ADR-0005](0005-ai-writes-via-typed-commands.md) and
[ADR-0006](0006-general-purpose-engine-and-editor.md). Tracking: [issue #68](https://github.com/aioanei/spawnkit/issues/68).

## Context

Creators build games manually in the builder: objects, properties, scenes, and When / If / Do rules. The owner
requires that **all of it**, rules first of all, can also be done by AI agents from natural-language prompts. For
example: "When the player touches a coin, add 1 to the score and remove the coin"; "make every coin worth 5";
"why doesn't my door open?".

The specs already route agents through typed editor operations with staging, validation and user acceptance
(ADR-0005, design §6, plan R7). They do not yet guarantee **completeness**. Nothing requires every manual capability,
present or future, to have an agent counterpart. Without that guarantee, agent coverage would lag behind the UI.

## Decision

1. **Parity is a requirement, not a feature.**
   - Every capability a creator can use manually in the builder has an equivalent agent tool backed by the same
     typed operation, validation and undo.
   - There are no UI-only and no agent-only editing paths. From C7/R7 on, when the agent layer exists, an editor
     increment that adds a manual capability is not done until its agent tool and a parity test exist. Before C7,
     increments record the missing agent coverage in their issue.
2. **Tools are derived, not hand-written per feature.**
   - The agent tool surface is generated from the same catalogs that generate the UI:
     - an editing operation catalog (to be built in C7: today `EditOperation` is a sealed class with only a label);
     - capability descriptors, which already give component properties with constraints, help and units;
     - a rule block catalog (to be built in C7: today `RuleBlocks` compiles and decompiles sealed `Condition` and
       `Action` types, but carries no names, help, parameter constraints or examples);
     - installed extensions.
   - A new component, rule block or extension becomes promptable without new agent code.
   - Hand-written Koog tools are reserved for multi-step workflows, such as building a level or a character, and
     those compose the derived tools.
3. **Rules by prompt.** Agents can perform every rule edit the builder supports by hand, on one object or many
   ("every coin"). Today that is adding, changing and deleting rules and their blocks (`EditOperation.SetRules`).
   Reordering, duplicating and enabling or disabling rules are added as typed operations for both hands and agents
   together, never for agents alone.
   - They author friendly When / If / Do blocks whenever the blocks can express the request, so the creator can read
     and edit the result manually.
   - Advanced rules (portable programs) are a fallback, labelled as advanced and explained in plain words.
   - Agents explain existing rules in the creator's terms. They debug a rule by running the staged world headlessly
     and reporting from real traces whether and when it fired.
4. **The same workspace, the same safeguards.**
   - Agents act on a staged changeset against the expected workspace revision and validate it in the real engine.
   - They present it as the same blocks, objects and properties the creator sees, with a runnable preview.
   - Accept commits it as one undoable step. Reject or cancel discards it.
   - An ambiguous prompt gets a clarifying question ("Which score: Score or High score?"), not a destructive guess.
5. **Consent and secrets.**
   - Outward-facing or irreversible actions need an explicit confirmation from the creator in the UI, even when an
     agent prepared them. Examples: publishing to an Arcade, starting a store build, overwriting or deleting a saved
     project file. Undoable workspace edits, such as removing a scene, are reviewed through the normal changeset.
   - Agents never receive any credentials, including signing keys, store credentials and model API keys, through tool
     inputs or outputs.
   - Destinations and credentials are chosen by the creator.
6. **AI stays optional.**
   - Every capability remains fully usable by hand, without model credentials.
   - Demos use labelled replay (ADR-0005).
   - The novice usability gate still excludes AI assistance, so manual creation must stand on its own.

## Consequences

- C7 must first build the operation catalog and the rule block catalog with metadata (names, help, parameters,
  constraints, examples) for both generated controls and generated agent tools. After that, missing metadata is a
  defect in both.
- A parity test suite enumerates the catalog. For each operation, applying it through the UI path and through the
  agent tool yields the same workspace, validation result and undo behaviour. Recorded prompt scenarios, replayed in
  CI without live model calls, cover rule authoring end to end. One example: building Score + collect purely from
  prompts must equal the manually built rules.
- The builder gains a prompt surface in compact and expanded layouts (an assistant panel, and "Ask AI" on the
  Rules panel and inspector). Both route to the same staged-changeset review.
- Agent-only conveniences (multi-object edits, explanations) must decompose into ordinary operations so they can be
  reviewed, undone and replayed.
- Generated Kotlin or native code stays out of scope for ordinary agent tools (design §6); it would follow a
  separate reviewed build workflow.
