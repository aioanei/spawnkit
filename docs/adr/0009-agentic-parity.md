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
   - There are no UI-only and no agent-only editing paths. An editor increment that adds a manual capability is not
     done until its agent tool and a parity test exist.
2. **Tools are derived, not hand-written per feature.**
   - The agent tool surface is generated from the same catalogs that generate the UI:
     - the editing operation catalog;
     - capability descriptors, which give component properties with constraints, help and units;
     - the rule block catalog (`RuleBlocks`: conditions and actions with their parameters);
     - installed extensions.
   - A new component, rule block or extension becomes promptable without new agent code.
   - Hand-written Koog tools are reserved for multi-step workflows, such as building a level or a character, and
     those compose the derived tools.
3. **Rules by prompt.** Agents can create, change, delete, reorder, enable and duplicate rules on one object or
   many ("every coin").
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
     agent prepared them. Examples: publishing to an Arcade, starting a store build, deleting a scene.
   - Agents never receive signing keys, store credentials or API keys of other services.
   - Destinations and credentials are chosen by the creator.
6. **AI stays optional.**
   - Every capability remains fully usable by hand, without model credentials.
   - Demos use labelled replay (ADR-0005).
   - The novice usability gate still excludes AI assistance, so manual creation must stand on its own.

## Consequences

- The editor operation catalog and descriptors must carry enough metadata (names, help, parameters, constraints,
  examples) for both generated controls and generated agent tools. Missing metadata is a defect in both.
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
