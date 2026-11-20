# ADR-0006: General-purpose engine and executable editor capabilities

- Status: **accepted architectural and creation requirements**, 2026-11-20; runtime/editor implementation remains outstanding.
- Mandate: the owner superseded the document-centric direction, confirmed SpawnKit's engine ownership, removed time pressure as an architectural constraint, selected guided visual creation and requested implementation of the documentation revision.
- Design and evidence: [architecture revision](../design/2026-11-20-general-purpose-engine.md).
- Delivery sequence: [revised plan](../plans/2026-11-20-engine-foundation-revision.md).

Review addendum, 2026-11-20: the owner's latest request is to reevaluate alternatives and revise the plan before
proceeding. The [architecture reassessment](../design/2026-11-20-engine-alternatives-review.md) and plan updates
are proposed for review. This ADR's accepted requirements remain intact; storage, program implementation and
persistence choices remain evidence-based decisions. Merged R1a/R1b work is preserved. Further implementation
waits for owner review and direction to continue.

## Context

The original platformer slice has a sealed gameplay component hierarchy, mandatory player, fixed platformer systems, role-specific storage and global level-completion semantics. A JSON compiler defines its available mechanics. This does not meet the revised goal of an extensible 2D engine with an integrated editor, custom gameplay and third-party component/behavior/tool distribution.

Kotlin Multiplatform, Compose Multiplatform, Koog and Ktor, the approved product roadmap and existing valid implementation must be preserved. Alternative scene-tree, ECS, hybrid and existing-engine approaches are evaluated in the linked design.

## Decision

1. Use scene/component authoring over an open, explicitly scheduled runtime. The central contracts are executable engine/editor APIs, entities, components, resources, programs and registered capabilities.
2. Provide portable creator programs with general computation and engine calls, plus a compiled KMP SDK for new components, behaviors, systems, services and editor tools. Initial native extensions are resolved into compatible builds; universal hot-loading is not promised.
3. Let UI, Koog and plugins use actual editor transactions and runtime inspect/test/debug APIs. Play instances are isolated from editable project state.
4. Treat persistence as adapters. Preserve legacy v1 JSON import and tested platformer execution during staged migration. Move genre mechanics and adventure progression into a demonstration package.
5. Keep deterministic execution as a tested capability profile, not an unqualified promise about arbitrary third-party native code. Keep domain validators, without claiming universal proof of arbitrary user programs.
6. Require empty/player-free worlds, three distinct demonstrations and a separate SDK consumer to prove generality before calling the foundation complete.
7. SpawnKit owns its runtime, gameplay APIs, behavior execution, editor capabilities and extension SDK. Supporting libraries may supply focused facilities; another complete engine cannot become its foundation.
8. Guided visual creation is the default: functional blank projects or optional examples, drag-and-drop objects, friendly behavior forms, visual When / If / Do rules and safe play/edit/recovery. AI and advanced programming concepts are optional.
9. Extend registration metadata with friendly labels/help, defaults, units, constraints, pickers, examples and progressive visibility. Automatically generate usable editor controls for first-party and external capabilities.
10. Release requires novice evidence as well as technical correctness: at least four of five first-time non-programmers complete the documented creation/recovery exercise independently, without AI assistance, coding, JSON editing, technical IDs or API keys.

Storage backend, program implementation and focused dependency adoption need the capability-based compatibility evidence in R1. This ADR accepts no dependency or unverified universal target claim. The owner's request removes contest deadlines and elapsed development time as architecture selection criteria.

## Consequences

- Retain reusable runtime/render/build/test work and checkpoint unfinished player shells. Migration is incremental; no repository restart or wholesale deletion.
- The editor, program runtime and package SDK become earlier foundation work. The old daily schedule and Tasks 11–12 ordering are superseded; complete R0 reconciliation, then proceed through R1–R9.
- JSON remains permitted for storage and tool transport; its schema cannot be the finite vocabulary of engine behavior.
- Native packages require trust, target-aware builds and version locks; portable programs require execution/resource boundaries. Metadata or signatures alone are not sandboxing.
- All approved no-code/AI/media/publishing/monetization/marketplace features remain. Capability, correctness and usability gates determine readiness; the contest is a showcase opportunity rather than an architectural constraint.
- Guided tools and advanced authoring use the same executable program/project model. Templates and ready-made behavior assemblies do not impose a finite genre vocabulary.

## Relationship to previous ADRs

- **ADR-0001:** its central document-first premise and restriction that new mechanics require core changes are superseded by the owner's mandate. Keep it as historical rationale for the existing slice.
- **ADR-0002:** remains the legacy v1 storage contract; not the schema of all future engine capabilities.
- **ADR-0003:** IDs and asset discipline remain useful. Tile units and bundle layout describe the old slice; new general units/package persistence follow the reviewed design.
- **ADR-0004:** existing determinism tests/profile remain binding. Broader scheduling, programs and package conformance extend this discipline; no weakening of current tests.
- **ADR-0005:** shared editing, user acceptance and honest replay remain. Flat specs become one optional authoring frontend; agents can invoke general editor capabilities and author programs.

Acceptance records the owner's reviewed requirements and implementation request, not completion of engine/editor migration. Track actual R0–R9 implementation and evidence in [PROGRESS.md](../../PROGRESS.md); the established issue/PR workflow still applies.
