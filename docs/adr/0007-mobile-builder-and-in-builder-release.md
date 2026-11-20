# ADR-0007: Mobile-first-class builder and in-builder release

Status: **accepted** (owner product-vision clarification, 2026-11-20). Extends [ADR-0006](0006-general-purpose-engine-and-editor.md).

## Context

The owner clarified three non-negotiable requirements:

1. SpawnKit is a genuine game engine, not a JSON-driven generator.
2. The complete builder runs on Android, iOS, desktop and web, and a creator can make a whole game on a phone.
3. Creators release independent games directly from the builder, without Gradle, an IDE or a terminal.

The earlier contest design scoped the creator to desktop and web, ran AI only on desktop, and produced Android
players through Gradle. [Gap analysis](../design/2026-11-20-product-vision-gap-analysis.md).

## Decision

- **One creator codebase for every platform.**
  - `app/shared` (Compose Multiplatform) holds all editor UI, layout adaptation and gesture handling.
  - Platform modules only host entry points and platform services: files, sharing, secure key storage.
  - Layouts adapt by window size class: compact gets bottom sheets and a tool rail; expanded gets docked panels.
  - Touch and pointer input are both first-class.
- **Editor state lives in shared Kotlin** (`core/editing`), over `core/runtime`, `core/program` and descriptors.
  The UI, Koog tools and tests all use the same typed operations, undo/redo and play-world instantiation.
- **Every editor increment is verified on Android and desktop.** iOS compiles in CI; the web creator follows.
- **Release uses a universal player plus a prepared project package.**
  - The universal player runs content, portable programs and the capabilities compiled into it. Games that need
    additional compiled native extensions require a target build of a player including them; that build is remote
    for platforms needing official toolchains.  - Exported games embed the SpawnKit runtime and the project content, and never need the creator app.
  - Web export and Arcade publishing are implemented first. Android standalone export comes next, by repacking and
    signing a prebuilt template player APK on the device.
  - Platforms that need official toolchains (iOS, desktop native, store submission) use remote build services
    later. No credentials are ever written into projects, logs or exports.
- **AI agents (Koog) run in-process** on Android and desktop with on-device keys. Labelled replay serves demos. They
  call editor operations, never raw file formats.

## Consequences

- The editor UI must be designed for compact screens from the start. A layout that only works on desktop fails
  review.
- The player runtime must be buildable as a self-contained web bundle that the creator app can ship as resources.
- The legacy platformer stays a regression baseline. Demonstrations are rebuilt on the general runtime.
- iOS on-device creator verification and remote builds are explicit evidence gaps until macOS and cloud
  infrastructure exist.
