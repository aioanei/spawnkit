# ADR-0002: Serialization and schema evolution

- Status: accepted (2026-11-19)

Scope after the 2026-11-20 revision request: this remains the legacy v1 document codec contract. It does not define
future executable engine types or require all gameplay to fit this schema. See [ADR-0006](0006-general-purpose-engine-and-editor.md).

## Context

Game Documents are saved by creators, published to the arcade, bundled into exported games, produced by AI agents,
recorded in demo sessions and used as golden test fixtures. Breaking their format later is expensive.

## Decision

- Use kotlinx.serialization JSON through a single configured instance, `SpawnJson`:
  `ignoreUnknownKeys = true`, `encodeDefaults = true`, `explicitNulls = false`, `classDiscriminator = "kind"`,
  pretty printing off for storage. Because `null` values are omitted, every nullable property must default to `null`.
- Every polymorphic subtype declares an explicit `@SerialName` (for example `@SerialName("player")`). Fully
  qualified class names never appear in documents, so packages can be refactored freely.
- No serializable class has a property named `type` or `kind` outside the polymorphic discriminator (it would clash
  with discriminator conventions and confuse LLMs); e.g. a tile definition uses `behavior`.
- Every enum constant that appears in documents has an explicit lower-camel-case `@SerialName`.
- `GameProject.schemaVersion` exists from version 1. Decoding reads it from the raw `JsonElement` first:
  older versions run through `Migrations` (pure `JsonElement → JsonElement` functions), newer versions return
  `DecodeResult.UnsupportedVersion`. `decode` never throws: malformed input, schema mismatches and failures inside
  migration steps become `DecodeResult.Malformed`.
- IDs are `@JvmInline` value classes wrapping strings (`EntityId`, `SceneId`, …), serialized as plain strings.
- Colors serialize as `"#AARRGGBB"` strings; tile layers serialize as rows of characters with a project-level legend
  (`Map<Char, TileDef>`, JSON keys of exactly one character).
- Encoded text is not byte-identical across targets (Kotlin/JS writes `20.0` as `20`). Never hash or golden-compare
  encoded text across platforms; compare decoded values instead. A hand-written v1 fixture test pins the format.

## Consequences

- New optional properties with defaults need no migration (older readers ignore them).
- New component kinds or new enum values make older readers reject the whole document, so they require a
  `schemaVersion` bump: older clients then show "update SpawnKit" instead of a confusing decode error.
- Renames and structural changes require a version bump and a migration with a test.
- Documents stay readable and diffable, which helps reviews, AI generation and debugging.
