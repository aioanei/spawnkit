# ADR-0005: AI writes only through typed commands; honest replay mode

- Status: accepted (2026-11-19)

Revision note (2026-11-20): typed editing, user acceptance, real-engine validation and honest replay remain. The new
direction permits agents to discover/invoke actual editor capabilities and author custom programs. Small specs remain
an optional frontend, not an exclusive JSON/configuration generation contract. See [ADR-0006](0006-general-purpose-engine-and-editor.md).
[ADR-0009](0009-agentic-parity.md) adds completeness: every manual capability, rules first, is operable by agents from prompts.

## Context

LLM output can be malformed, inconsistent or unplayable. Letting a model write raw JSON or JSON Patch against the
document would make errors hard to detect and impossible to review. Contest judges and new users may not have API
keys, but replaying AI output must never be presented as live generation.

## Decision

- Agents (built with JetBrains Koog) produce small, flat, typed specs via structured output (for example a
  `LevelSpec` with ASCII rows and a legend, or a `CharacterSpec`).
- Deterministic Kotlin code converts specs into the same typed editing commands the editor uses, with temporary
  references for new objects.
- Every changeset is validated with real tools (integrity checks, reachability solver, headless playtest, economy
  simulation). Failures feed a bounded repair loop. The user sees a diff and accepts, rejects or edits it.
- A recording prompt executor captures real model and image responses; a replaying executor feeds them back through
  the same agent strategy while validators run live. The UI always labels replays with their recording date and model.
- Tests use the replaying executor; continuous integration never calls paid APIs.

## Consequences

- AI and human edits share undo/redo, validation and review.
- Model quality issues surface as validation reports instead of corrupted projects.
- Demo and test runs are reproducible and free, without misrepresenting what is live.
