# ADR-0008: Kotlin Toolchain for SpawnKit's build and the Release Manager

Status: **accepted** (owner technology decision, 2026-11-20). Refines [ADR-0007](0007-mobile-builder-and-in-builder-release.md)
for how releases are built. Tracking: [issue #58](https://github.com/aioanei/spawnkit/issues/58).

## Context

The owner made the JetBrains Kotlin Toolchain a mandatory part of SpawnKit's stack, alongside Kotlin Multiplatform,
Compose Multiplatform, Koog and Ktor. It must serve two roles:

1. **SpawnKit's own engineering.** It covers provisioning, configuration, dependencies, compilation, running, tests,
   packaging, distribution, publishing and CI. Gradle stays only where a verified limitation requires it.
2. **The product.** The Release Manager uses it to prepare, compile, test, package, sign and distribute creators'
   standalone games for Android, iOS, Windows, macOS, Linux and web. A creator never installs it or sees its CLI,
   including a creator who works only on a phone.

Facts established with Toolchain 0.14.1 are in [Kotlin Toolchain](../kotlin-toolchain.md).

- The Toolchain builds and tests every SpawnKit multiplatform library on JVM, Android and mingwX64.
- It builds the Android APK and release AAB, the desktop executable JAR, the Wasm web bundle and the Ktor server JAR.
  Each output ran correctly.
- It provisions its own JDK, Android pipeline, Kotlin/Native, Node and Skiko.
- It is Alpha. It cannot run Kotlin/JS tests, and its `js/app` product is a preview. It has no desktop installer
  formats. Its iOS apps use generated Xcode projects.

## Decision

- **Primary build system.**
  - `project.yaml`, `module.yaml` and module templates under `toolchain/` define every SpawnKit module.
  - Local plugins under `plugins/` replace custom Gradle logic (embedded templates, ktlint).
  - The committed `kotlin` / `kotlin.bat` wrappers pin the release.
  - New modules are Toolchain modules first.
- **Incremental, evidence-gated migration.**
  - Sources move once to the Toolchain layout, and Gradle reads the same directories while both builds coexist.
  - Gradle remains the required CI check until the Toolchain job matches its coverage on `main`.
  - Gradle files are deleted gate by gate.
  - Remaining Gradle uses are numbered exceptions with revisit triggers (G1–G8).
- **Release Manager build engine.** Releases that cannot be served by the universal player and package (ADR-0007)
  are built by the Kotlin Toolchain. That covers per-game identity, store formats, native SDKs such as AdMob, and
  compiled extensions.
  - A Ktor build service generates a release workspace: a Toolchain project made of a SpawnKit player template, the
    validated game package, a release profile, and vetted SpawnKit release plugins.
  - It runs `kotlin build`, `test`, `package` and `publish` in isolated remote workers: Linux for Android, web,
    Linux and JVM desktop; macOS with Xcode for iOS, TestFlight and macOS.
  - The builder, including on phones, starts, monitors and downloads releases through the service.
  - Signing credentials stay in platform secure storage or the service's secret store. They are injected into a
    worker only for the job and never written to projects, logs or exports.
  - Design: [Release Manager build architecture](../design/2026-11-20-release-manager-kotlin-toolchain.md).
- **Agent tooling.** The official Kotlin agent skills and the kotlinlang documentation MCP server are configured for
  Claude Code and Codex. AGENTS.md remains the single source of instructions.

## Consequences

- Source directories changed (`src/`, `src@jvm/`, `test/`, …). Content did not change. Older branches need rename
  detection when rebased.
- Version pins for Kotlin, Compose, serialization and Ktor live in both the catalog and Toolchain settings until Gradle is
  removed. A version change must update both.
- The Toolchain is Alpha, so upgrades are deliberate PRs with the full gate. Output paths are internal; CI and the
  build service read the paths that `package` prints.
- The Release Manager depends on remote infrastructure for compiled releases: workers, secret storage and
  queueing. The universal-player paths (web export, Arcade, Android repack) stay available without it, so local
  creation and play never require an account or network.
- Kotlin/JS tests, the JS web bundle, desktop installers, the existing iOS Xcode app and the benchmark executables
  stay on Gradle until their recorded triggers are met.
- Contest positioning: SpawnKit demonstrates KMP, Compose Multiplatform, Koog, Ktor and the Kotlin Toolchain
  together. The Toolchain is also what lets non-programmers ship their own multiplatform games. Claims are limited
  to verified capabilities.
