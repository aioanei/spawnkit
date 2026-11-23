# C16a Character Forge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The first half of C16 ([#90](https://github.com/aioanei/spawnkit/issues/90), [ADR-0013](../adr/0013-character-forge-image-model.md)):
a photo becomes game characters in pixel art, cartoon and chibi styles through the server's image route, and the
chosen one becomes a playable Character object. C16b adds the agent tool with confirmation and live evidence.

## Tasks

### Task 1: Project support
- `ImageAsset.provenance` (workspace schema 4); `ProjectImages.stripMetadata` keeps only the PNG chunks needed to draw.
- Tests: provenance round trip and migration; metadata chunks removed and the image still decodes.

### Task 2: Server
- `PixelArt` (deterministic post-processing), `ImageGateway` with the OpenAI implementation over the JDK HTTP client,
  `CharacterForge` and `POST /api/ai/character`, `characterAvailable` in `/api/server`.
- Tests with a fake gateway: consent required, signed in, cap and per-account limits, refusals and failures cost
  nothing, deterministic pixels, the photo is never logged.

### Task 3: Client and builder
- `AccountClient.character`; the forge dialog in both layouts; importing the results with provenance and the Character
  object as one undo step.
- UI test with a fake service on phone and desktop; docs and `PROGRESS.md`.
