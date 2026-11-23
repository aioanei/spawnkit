# ADR-0013: Character Forge through a hosted image model

Status: **accepted** (owner request for C16, 2026-11-23; the owner provided `OPENAI_API_KEY`). Extends
[ADR-0011](0011-hosted-ai-model-proxy.md) and [ADR-0012](0012-images-in-projects.md). Design:
[§13 of the contest additions](../design/2026-11-22-title-screens-presets-accounts-ads.md). Increment: C16
([#90](https://github.com/aioanei/spawnkit/issues/90)).

## Context

Creators want to turn a photo of themselves, or of someone who agreed, into game characters in several styles (pixel
art, cartoon, chibi). Generating images needs an image model, which costs money and receives the photo. The assistant's
text model already runs behind the server with the owner's key and a $20 monthly cap (ADR-0011).

## Decision

1. **The server calls OpenAI image editing** (`POST /v1/images/edits`, `gpt-image-1-mini` by default and set with
   `SPAWNKIT_IMAGE_MODEL`, because `gpt-image-1` was retired; low quality, transparent
   background, 1024 × 1024) with `OPENAI_API_KEY` from its environment. The key never reaches clients, projects, logs
   or recordings. Without the key the route answers "not set up".
2. **`POST /api/ai/character`** takes a signed-in account's photo (PNG, at most 1 MB), an explicit consent flag and
   one to three styles.
   - Without consent the request is refused.
   - Each style is one image call, reserved in advance against the shared spend ledger: the same monthly cap,
     per-account daily limit and hourly call limit as text AI. A failed or refused call costs nothing.
3. **Privacy.**
   - The photo is resized and re-encoded on the device, which strips metadata.
   - The server sends it only to the image model and never stores or logs it.
   - The photo itself is not added to the project.
   - Refusals from the model are shown plainly.
4. **Results.**
   - Each image is scaled on the server to at most 512 × 512 so it fits the project limits.
   - Pixel art is post-processed in deterministic Kotlin into a crisp grid: a 32 × 32 box downscale, a fixed 16-colour
     palette and hard transparency, scaled up 8 × without smoothing.
   - Each result carries its provenance (provider, model, UTC date), which the project stores with the image
     (workspace schema 4). The creator owns the outputs.
5. **Builder.** "Character from photo…" in the Add panel runs the flow: pick a photo, confirm consent, choose styles,
   and make the characters.
   - The images are imported, and the chosen one becomes a Character object as one undo step: a sprite, a body, the
     player tag and walking and jumping rules.
   - The images and the object use the ordinary catalog operations, so agents can arrange them. The upload itself
     becomes an agent tool that asks the creator first (C16b, ADR-0009).
6. **Agent tool (C16b).** `make_character` takes styles and placement but never the photo, which stays in the builder
   that the creator attached it to. The session pauses until the creator confirms with the same consent checkbox, then
   sends the photo through the account's forge. The images and the Character are staged with the recipe the builder
   uses, so both paths make the same game.

## Consequences

- Images share the $20 cap with text AI; at low quality each image is a few cents.
- Tests use a fake image gateway; no live image calls run in tests. A live run needs the key on a server.
- Other providers can implement the same gateway interface.
