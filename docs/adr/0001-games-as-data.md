# ADR-0001: Games are data, interpreted by one shared runtime

- Status: historical; accepted 2026-11-19, central premise superseded by the owner's 2026-11-20 direction change.
- The implementation remains a preserved baseline. See [ADR-0006](0006-general-purpose-engine-and-editor.md)
  for the accepted replacement requirements; do not treat the restrictions below as future engine requirements.

## Context

SpawnKit must let non-programmers create games, preview them instantly in the editor, publish them to a browser
arcade, export them to Android, iOS, desktop and web, let AI agents generate and test content, and later sell
reusable pieces in a marketplace. Generating Kotlin source per game would require compiling user code for every
platform, make AI output hard to validate, and conflict with app store rules on template-generated apps and
downloaded executable code.

## Decision

A game is a versioned, serializable document (`GameProject`). A single deterministic runtime written in Kotlin
common code compiles the document into a mutable `RuntimeWorld` when play starts and discards it when play stops.
The editor never edits the runtime world; it edits the document through typed commands.

## Consequences

- Preview, arcade and exports all run the same code path: what the creator tests is what players get.
- Exporting a game means bundling data with a generic player binary and changing its identity (name, app ID,
  icon); creators never compile code.
- AI agents produce data that can be schema-checked, simulated headlessly and shown as a diff.
- Marketplace items are document fragments with schema-version compatibility.
- A single "picker" host app can load many games as data, which is the app-store-compatible managed-publishing model.
- Expressiveness is bounded by the runtime's components and rule actions; new mechanics require runtime features.
  This is accepted and mitigated by composable generic components and an event-sheet rules engine.
