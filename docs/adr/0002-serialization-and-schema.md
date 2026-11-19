# ADR-0002: Serialization and schema evolution

- Status: accepted (2026-11-19)

## Context

Game Documents are saved by creators, published to the arcade, bundled into exported games, produced by AI agents,
recorded in demo sessions and used as golden test fixtures. Breaking their format later is expensive.

## Decision

- Use kotlinx.serialization JSON through a single configured instance, `SpawnJson`:
  `ignoreUnknownKeys = true`, `encodeDefaults = true`, `classDiscriminator = "kind"`, pretty printing off for
  storage.
- Every polymorphic subtype declares an explicit `@SerialName` (for example `@SerialName("player")`). Fully
  qualified class names never appear in documents, so packages can be refactored freely.
- No serializable class has a property named `type` (it would clash with discriminator conventions and confuse LLMs).
- `GameProject.schemaVersion` exists from version 1. Decoding reads it from the raw `JsonElement` first:
  older versions run through `Migrations` (pure `JsonElement → JsonElement` functions), newer versions return a
  typed `DecodeResult.Error`, never an exception.
- IDs are `@JvmInline` value classes wrapping strings (`EntityId`, `SceneId`, …), serialized as plain strings.
- Colors serialize as `"#AARRGGBB"` strings; tile layers serialize as rows of characters with a project-level legend.

## Consequences

- Additive changes with defaults need no migration; renames and structural changes require a version bump and a
  migration with a test.
- Documents stay readable and diffable, which helps reviews, AI generation and debugging.
