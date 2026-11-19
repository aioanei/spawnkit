# ADR-0003: IDs, coordinates, assets and bundles

- Status: accepted (2026-11-19)

Revision note (2026-11-20): IDs/assets and existing fixture conventions remain valid. Tile-based units and bundle layout
describe the legacy slice; general engine units and package persistence follow the revised requirements in
[ADR-0006](0006-general-purpose-engine-and-editor.md). They are not grounds for restricting custom gameplay.

## Context

The editor, runtime, renderer, validators and AI agents must agree on how things are identified, positioned and
packaged across screen sizes and platforms, including Kotlin/Wasm where zip handling and system fonts are missing.

## Decision

- **IDs**: stable strings, unique within their kind in a project. New entities created by AI changesets use
  temporary references (`@new:<name>`) that are resolved to real IDs when a changeset is applied; dangling references
  reject the changeset before preview.
- **Coordinates**: 1 world unit = 1 tile; x grows right, y grows down; an entity's `position` is the top-left corner
  of its axis-aligned bounding box. The game renders into a virtual viewport (`GameSettings.viewportTiles`) that is
  uniformly scaled to fit the screen and letterboxed, never cropped.
- **Tiles**: tile layers are rows of characters; the project-level `tileset` legend maps characters to tile kinds
  (`Solid`, `OneWay`, `Hazard`, `Decor`) and visuals. `.` and space are always empty.
- **Assets**: images are PNG, named by content hash in bundles, referenced by `AssetId`. Pixel art is drawn with
  nearest-neighbour filtering. A bundled OFL font is used for in-game text because Skiko on Wasm has no system fonts.
- **Bundles**: a published or exported game is `manifest.json` (the document plus metadata) and its content-hashed
  PNG files — a plain file set, not a zip, so every target can read it.
- **Templates** are embedded into code as generated sources so tests and players load them identically on all targets.

## Consequences

- Resolution independence and consistent physics across devices.
- Character-based tile layers are compact and easy for LLMs to generate and for humans to review.
