# ADR-0012: Images stored inside projects

Status: **accepted** (owner request for C15, 2026-11-23). Extends [ADR-0003](0003-ids-coordinates-assets.md) and
[ADR-0007](0007-mobile-builder-and-in-builder-release.md). Design:
[§12 of the contest additions](../design/2026-11-22-title-screens-presets-accounts-ads.md). Increment: C15b
([#89](https://github.com/aioanei/spawnkit/issues/89)).

## Context

Creators want their own logo and pictures on a home screen, and C16 (Character Forge) will add generated characters.
Sprites already name an "asset", but projects had nowhere to keep one, so every sprite drew a placeholder. A project
is one portable file: it is autosaved on the device, exported as a game package, published to the Arcade and played by
the web player, without any other files.

## Decision

1. **A project carries its images.** `Project.images` is a list of `ImageAsset(id, name, hash, data)`:
   - `id` is minted like scene and object IDs (`img-<n>`);
   - `data` is the PNG file in Base64;
   - `hash` is the SHA-256 of the PNG bytes, in lower-case hex.
   The workspace file moves to schema 2 with an `images` array. Schema 1 files migrate by gaining an empty list. The
   game package and the Arcade carry the project unchanged, so they carry its images.
2. **PNG only, checked on every path in.**
   - The signature and the `IHDR` header are read; images are at most 2048 × 2048 pixels and 512 KiB.
   - A project holds at most 4 MiB of images, and a hash that does not match its data fails decoding.
   - The project file limit rises from 4 Mi to 10 Mi characters, and the package and Arcade upload limits follow it.
   - Other formats are converted by the platform picker where possible; otherwise the import is refused with a
     plain-language reason.
3. **Editing through operations.** `AddImage`, `RenameImage` and `RemoveImage` are editor operations with catalog
   entries (`add_image`, `rename_image`, `remove_image`), so agents can do what the import button does (ADR-0009).
   - An image used by a sprite cannot be removed; the refusal names the objects using it.
   - The same picture cannot be added twice.
4. **Sprites name images by ID.**
   - `ValueKind.Text` gains an optional `asset` kind. When it is `image`, editors offer the project's images and the
     editor refuses an unknown ID.
   - `Sprite2D` gains **Pixelated**, which draws without smoothing so pixel art stays crisp.
5. **Decoded once per hash.** Each canvas host decodes images with Compose's `decodeToImageBitmap`, keyed by hash, and
   passes them to `RuntimeCanvas`: the builder viewport, builder Play, exported players and the web player (and so the
   Arcade). Simulation never sees image data, so determinism is unaffected.

## Consequences

- Project files grow with their images. Base64 adds a third, which is acceptable at these limits and keeps the file
  self-contained JSON.
- Images are not deduplicated across projects, and there is no image editor; Character Forge (C16) adds generated
  images through the same operations.
- Other asset kinds (sounds, fonts) can follow the same pattern with their own lists and limits.
