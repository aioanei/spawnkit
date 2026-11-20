# Current project plan

The owner requested a fundamental architecture revision on 2026-11-20, confirmed that SpawnKit owns its engine,
selected guided visual creation for non-programmers, removed time pressure as an architectural constraint and
requested implementation of the documentation revision. Persistence is secondary to executable capabilities.

Plan PR #20 and runtime PRs #22/#24 are merged; R0 repository/GitHub reconciliation is complete. The latest owner
request is to reevaluate alternatives and revise the plan before proceeding. Current work is a documentation-only
[architecture reassessment](docs/design/2026-11-20-engine-alternatives-review.md), proposed for review. Further
implementation waits for owner review and direction to continue. The merged [headless runtime foundation](docs/runtime-foundation.md)
and [native checkpoints/replay](docs/runtime-checkpoints.md) are preserved. Remaining R1 storage/program/effect
proofs and the visual editor are unfinished. Do not resume old Tasks 11–12 or assume a production backend has been
selected. All product features and mandatory technologies remain.

- Engine ownership, guided creation and usability requirements: [General-purpose engine design](docs/design/2026-11-20-general-purpose-engine.md).
- Revised implementation sequence: [Engine foundation revision](docs/plans/2026-11-20-engine-foundation-revision.md).
- Researched alternatives, recommendations and remaining decisions: [Architecture reassessment](docs/design/2026-11-20-engine-alternatives-review.md).
- Decision record: [ADR-0006](docs/adr/0006-general-purpose-engine-and-editor.md).
- Historical approved plan: [M0–M1 Bootstrap & Engine Core](docs/plans/2026-11-19-m0-m1-bootstrap-and-engine.md).
  Completed work remains valid; its remaining sequence is superseded by R0–R9.

- Preserved product requirements and historical architecture: [Original SpawnKit design](docs/design/2026-11-19-spawnkit-design.md).
- Implemented architecture: [Architecture](docs/ARCHITECTURE.md).
- Decisions: [ADRs](docs/adr/).
- Current checkpoint and validation evidence: [Progress](PROGRESS.md).
- Working rules: [AGENTS.md](AGENTS.md).
