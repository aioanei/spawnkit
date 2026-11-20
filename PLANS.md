# Current project plan

The owner requested a fundamental architecture revision on 2026-11-20, confirmed that SpawnKit owns its engine,
selected guided visual creation for non-programmers, removed time pressure as an architectural constraint and
requested implementation of the documentation revision. Persistence is secondary to executable capabilities.

Plan PR #20 is merged with owner authorization and R0 repository/GitHub reconciliation is complete. R1a
([headless runtime foundation](docs/runtime-foundation.md)) and R1b ([native checkpoints and replay](docs/runtime-checkpoints.md))
are merged. R1c ([runtime storage decision](docs/runtime-storage.md)) and R1d ([portable program executor](docs/runtime-programs.md))
are merged, so R1 is complete. R2a ([descriptors](docs/runtime-descriptors.md)) and R2b ([hierarchy, resources and random](docs/runtime-hierarchy.md)) are
merged, as is R2c (descriptor-derived program properties). Current work is R2d / issue #36:
[deterministic input](docs/runtime-input.md). The architecture reassessment (PR #25) awaits owner review.
The visual editor remains unfinished. Do not resume old Tasks 11–12 or assume a production backend
has been selected. All product features and mandatory technologies remain.

- Engine ownership, guided creation and usability requirements: [General-purpose engine design](docs/design/2026-11-20-general-purpose-engine.md).
- Revised implementation sequence: [Engine foundation revision](docs/plans/2026-11-20-engine-foundation-revision.md).
- Decision record: [ADR-0006](docs/adr/0006-general-purpose-engine-and-editor.md).
- Historical approved plan: [M0–M1 Bootstrap & Engine Core](docs/plans/2026-11-19-m0-m1-bootstrap-and-engine.md).
  Completed work remains valid; its remaining sequence is superseded by R0–R9.

- Preserved product requirements and historical architecture: [Original SpawnKit design](docs/design/2026-11-19-spawnkit-design.md).
- Implemented architecture: [Architecture](docs/ARCHITECTURE.md).
- Decisions: [ADRs](docs/adr/).
- Current checkpoint and validation evidence: [Progress](PROGRESS.md).
- Working rules: [AGENTS.md](AGENTS.md).
