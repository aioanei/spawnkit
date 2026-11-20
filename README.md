# SpawnKit

**A Kotlin Multiplatform app that builds Kotlin Multiplatform games — where AI agents don't just generate content, they playtest it.**

SpawnKit is building a general-purpose, extensible Kotlin Multiplatform 2D engine with an integrated no-code visual
editor. The product combines reusable engine capabilities, custom gameplay, [JetBrains Koog](https://github.com/JetBrains/koog)
agents, publishing, monetization and a marketplace. Compose Multiplatform provides the editor and presentation;
Ktor provides server services. SpawnKit owns its engine and extension APIs. Guided visual creation will let users
build games without programming knowledge; AI assistance is optional. These are product goals; the current
implemented slice is described below.

> Status: under active development. Capability and usability milestones guide delivery; the Kotlin Multiplatform
> Contest is a showcase opportunity rather than an architectural constraint.
> This README will grow a "try it in 2 minutes" section as features land.

The current M1 work includes the versioned Game Document, the three-level Platformer Adventure template,
the deterministic runtime, and a shared Compose `GameView` with keyboard/touch input and a HUD.
The creator app still opens its initial shell. Player-shell work is checkpointed separately; browser deployment
is unfinished. The [general-purpose architecture revision](docs/design/2026-11-20-general-purpose-engine.md) and
[revised plan](docs/plans/2026-11-20-engine-foundation-revision.md) record the accepted engine ownership and guided
creation requirements; their full runtime/editor implementation is unfinished. Existing platformer mechanics are a
demo baseline, not the target engine's universal gameplay contract.

The latest [architecture reassessment](docs/design/2026-11-20-engine-alternatives-review.md) compares runtime,
custom-logic and persistence alternatives and updates the plan for review. Further implementation waits for that
review and owner direction to continue; all merged runtime evidence and approved product features are preserved.

The first [headless runtime foundation](docs/runtime-foundation.md) now runs empty/player-free worlds and custom
components/systems through public APIs, with a separately compiled extension consumer. It is an initial R1 evidence
increment. [Native checkpoints and replay](docs/runtime-checkpoints.md) add explicit capture of component/private
system state and deterministic fingerprints; backend selection, portable behaviors and the visual editor remain future work.

See [PROGRESS.md](PROGRESS.md) for the latest checkpoint and [PLANS.md](PLANS.md) for the approved plan location.
The renderer's [browser smoke check](tools/render-smoke/README.md) and [Wasm captures](docs/media/README.md)
provide reproducible evidence of the current runtime and UI.

## Build

Requirements: JDK 21, Android SDK (for Android targets). iOS builds require macOS with Xcode.

| Target | Command |
|---|---|
| Creator app (desktop) | `./gradlew :app:desktopApp:run` |
| Creator app (web, Wasm) | `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun` |
| Creator app (Android) | `./gradlew :app:androidApp:installDebug` |
| Server | `./gradlew :server:run` |

## License

[Apache License 2.0](LICENSE)
