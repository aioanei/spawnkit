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

The creator app now opens a [responsive builder](docs/builder.md): add and move objects, edit generated properties,
assemble visual When / If / Do rules, and play with keyboard or touch input. Android and desktop provide
[project files, autosave and recovery](docs/project-persistence.md). General runtime, program, graphics and gameplay
modules power this builder. The legacy three-level Platformer Adventure and deterministic renderer remain a tested
baseline. Exported player shells and publishing remain unfinished. The [general-purpose architecture revision](docs/design/2026-11-20-general-purpose-engine.md) and
[revised plan](docs/plans/2026-11-20-engine-foundation-revision.md) record the accepted engine ownership and guided
creation requirements; their full runtime/editor implementation is unfinished. Existing platformer mechanics are a
demo baseline, not the target engine's universal gameplay contract.

The [architecture reassessment](docs/design/2026-11-20-engine-alternatives-review.md) compares runtime,
custom-logic and persistence alternatives. The owner reviewed and merged it; its split of the remaining R1 work
(storage comparison, then the portable program executor) is what R1c–R1d delivered.

The first [headless runtime foundation](docs/runtime-foundation.md) now runs empty/player-free worlds and custom
components/systems through public APIs, with a separately compiled extension consumer. It is an initial R1 evidence
increment. [Native checkpoints and replay](docs/runtime-checkpoints.md) add explicit capture of component/private
system state and deterministic fingerprints. The selected storage, portable behavior executor and visual builder
have since landed; broader extension, editor-depth and release work remains in the revised plan.

See [PROGRESS.md](PROGRESS.md) for the latest checkpoint and [PLANS.md](PLANS.md) for the approved plan location.
The renderer's [browser smoke check](tools/render-smoke/README.md) and [Wasm captures](docs/media/README.md)
provide reproducible evidence of the current runtime and UI.

## Build

SpawnKit builds with the [Kotlin Toolchain](https://kotlin-toolchain.org/) ([ADR-0008](docs/adr/0008-kotlin-toolchain.md)).
The committed `kotlin` wrapper (`kotlin.bat` on Windows) downloads the pinned CLI and provisions the JDK,
Kotlin/Native, Node and Skiko on first use. An Android SDK (`ANDROID_HOME`) is needed for Android targets. iOS
requires macOS with Xcode. Before building or running the Android creator, desktop creator or Arcade server, run
`./kotlin build -m webPlayer` to prepare the player they ship. Repeat after changing the player or its dependencies.

| Target | Command |
|---|---|
| Creator app (desktop) | `./kotlin run -m desktopApp` |
| Creator app (web, Wasm) | `./kotlin run -m webApp` |
| Creator app (Android) | `./kotlin run -m androidApp` (emulator or device) · APK: `./kotlin build -m androidApp` |
| Server | `./kotlin run -m server` |
| Tests | `./kotlin test -p jvm -p android` |
| Lint / format | `./kotlin check ktlint` · `./kotlin do ktlintFormat` |

During the migration, Gradle (`./gradlew`) still runs the required CI check and the few targets the Toolchain does
not cover yet (Kotlin/JS tests, desktop installers, the existing iOS Xcode app). See
[Kotlin Toolchain](docs/kotlin-toolchain.md) for the status, verified evidence and Gradle exceptions.

In the Android or desktop builder, choose **Project → Release game…** to export an offline web ZIP or publish to a
running Arcade. Players open the game in a browser. See the [release guide](docs/release.md). Compiled store
releases depend on the planned [build service](docs/design/2026-11-20-release-manager-kotlin-toolchain.md).

## License

[Apache License 2.0](LICENSE)
