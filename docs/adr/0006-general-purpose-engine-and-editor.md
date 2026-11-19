# ADR-0006: General-purpose engine and executable editor capabilities

- Status: **proposed**, 2026-11-20; implementation awaits owner review.
- Mandate: the owner's 2026-11-20 request supersedes the document-centric architectural direction. The specific solution below remains a proposal.
- Design and evidence: [architecture revision](../design/2026-11-20-general-purpose-engine.md).
- Delivery sequence: [revised plan](../plans/2026-11-20-engine-foundation-revision.md).

## Context

The original platformer slice has a sealed gameplay component hierarchy, mandatory player, fixed platformer systems, role-specific storage and global level-completion semantics. A JSON compiler defines its available mechanics. This does not meet the revised goal of an extensible 2D engine with an integrated editor, custom gameplay and third-party component/behavior/tool distribution.

Kotlin Multiplatform, Compose Multiplatform, Koog and Ktor, the approved product roadmap and existing valid implementation must be preserved. Alternative scene-tree, ECS, hybrid and existing-engine approaches are evaluated in the linked design.

## Proposed decision

1. Use scene/component authoring over an open, explicitly scheduled runtime. The central contracts are executable engine/editor APIs, entities, components, resources, programs and registered capabilities.
2. Provide portable creator programs with general computation and engine calls, plus a compiled KMP SDK for new components, behaviors, systems, services and editor tools. Initial native extensions are resolved into compatible builds; universal hot-loading is not promised.
3. Let UI, Koog and plugins use actual editor transactions and runtime inspect/test/debug APIs. Play instances are isolated from editable project state.
4. Treat persistence as adapters. Preserve legacy v1 JSON import and tested platformer execution during staged migration. Move genre mechanics and adventure progression into a demonstration package.
5. Keep deterministic execution as a tested capability profile, not an unqualified promise about arbitrary third-party native code. Keep domain validators, without claiming universal proof of arbitrary user programs.
6. Require empty/player-free worlds, three distinct demonstrations and a separate SDK consumer to prove generality before calling the foundation complete.

Storage backend, program implementation and engine-library adoption need the bounded compatibility evidence in R1. This ADR accepts no dependency or unverified universal target claim.

## Consequences

- Retain reusable runtime/render/build/test work and checkpoint unfinished player shells. Migration is incremental; no repository restart or wholesale deletion.
- The editor, program runtime and package SDK become earlier foundation work. Old daily estimates and remaining Tasks 11–12 are paused pending revision approval.
- JSON remains permitted for storage and tool transport; its schema cannot be the finite vocabulary of engine behavior.
- Native packages require trust, target-aware builds and version locks; portable programs require execution/resource boundaries. Metadata or signatures alone are not sandboxing.
- All approved no-code/AI/media/publishing/monetization/marketplace features remain. Their full commercial delivery cannot be promised by the old contest schedule.

## Relationship to previous ADRs

- **ADR-0001:** its central document-first premise and restriction that new mechanics require core changes are superseded by the owner's mandate. Keep it as historical rationale for the existing slice.
- **ADR-0002:** remains the legacy v1 storage contract; not the schema of all future engine capabilities.
- **ADR-0003:** IDs and asset discipline remain useful. Tile units and bundle layout describe the old slice; new general units/package persistence follow the reviewed design.
- **ADR-0004:** existing determinism tests/profile remain binding. Broader scheduling, programs and package conformance extend this discipline; no weakening of current tests.
- **ADR-0005:** shared editing, user acceptance and honest replay remain. Flat specs become one optional authoring frontend; agents can invoke general editor capabilities and author programs.

Do not mark this ADR accepted or begin migration merely because it is committed. Record the owner's review first.
