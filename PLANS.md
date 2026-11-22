# Current project plan

The owner requested a fundamental architecture revision on 2026-11-20, confirmed that SpawnKit owns its engine,
selected guided visual creation for non-programmers, removed time pressure as an architectural constraint and
requested implementation of the documentation revision. Persistence is secondary to executable capabilities.

R0, R1 and R2a–R2f are merged; see [Progress](PROGRESS.md) for links. The owner's 2026-11-20 product clarification
is reconciled in the [gap analysis](docs/design/2026-11-20-product-vision-gap-analysis.md) and
[ADR-0007](docs/adr/0007-mobile-builder-and-in-builder-release.md). There are three requirements:

- a genuine engine;
- a full builder on Android and desktop;
- release directly from the builder.

Current work is the
[contest delivery track](docs/plans/2026-11-20-engine-foundation-revision.md#contest-delivery-track) C1–C8 (deadline
7 Dec 2026). The owner reviewed and merged the [architecture reassessment](docs/design/2026-11-20-engine-alternatives-review.md)
(PR #25). All product features and mandatory technologies
remain on the roadmap.

On 2026-11-20 the owner also made the Kotlin Toolchain mandatory, for SpawnKit's own build and as the Release
Manager's build engine. That work is the plan's
[Kotlin Toolchain track (K)](docs/plans/2026-11-20-engine-foundation-revision.md#kotlin-toolchain-track-k). See
[ADR-0008](docs/adr/0008-kotlin-toolchain.md), [migration status](docs/kotlin-toolchain.md) and the
[Release Manager build architecture](docs/design/2026-11-20-release-manager-kotlin-toolchain.md).

Agentic parity ([ADR-0009](docs/adr/0009-agentic-parity.md), 2026-11-20): everything a creator does by hand, rules
first of all, must also be operable by AI agents from prompts, through the same reviewed operations.

- Engine ownership, guided creation and usability requirements: [General-purpose engine design](docs/design/2026-11-20-general-purpose-engine.md).
- Revised implementation sequence: [Engine foundation revision](docs/plans/2026-11-20-engine-foundation-revision.md).
- Decision record: [ADR-0006](docs/adr/0006-general-purpose-engine-and-editor.md).
- Product vision clarification: [gap analysis](docs/design/2026-11-20-product-vision-gap-analysis.md) and [ADR-0007](docs/adr/0007-mobile-builder-and-in-builder-release.md).
- Historical approved plan: [M0–M1 Bootstrap & Engine Core](docs/plans/2026-11-19-m0-m1-bootstrap-and-engine.md).
  Completed work remains valid; its remaining sequence is superseded by R0–R9.

- Preserved product requirements and historical architecture: [Original SpawnKit design](docs/design/2026-11-19-spawnkit-design.md).
- Implemented architecture: [Architecture](docs/ARCHITECTURE.md).
- Decisions: [ADRs](docs/adr/).
- Current checkpoint and validation evidence: [Progress](PROGRESS.md).
- Working rules: [AGENTS.md](AGENTS.md).
