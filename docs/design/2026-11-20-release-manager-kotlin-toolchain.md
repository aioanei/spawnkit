# Release Manager build architecture with the Kotlin Toolchain

Date: 2026-11-20. Status: **accepted direction, not implemented.** Decision: [ADR-0008](../adr/0008-kotlin-toolchain.md),
extending [ADR-0007](../adr/0007-mobile-builder-and-in-builder-release.md). Toolchain facts: [Kotlin Toolchain](../kotlin-toolchain.md).

## 1. Requirements carried forward

- Creators release independent games from the builder on Android, iOS, desktop or web. They never install the
  Kotlin Toolchain, Gradle, an IDE or a terminal, and never see a CLI.
- A creator who works only on a phone can release by using a remote build environment.
- Target roadmap: Android, iOS, Windows, macOS, Linux, web. Self publishing (the creator's own store accounts) and
  managed publishing (SpawnKit's accounts) are both supported.
- Credentials never enter projects, logs or exports. Test modes (ads, purchases) stay labelled. Nothing fabricates
  store, revenue or ad results.
- Exported games embed the SpawnKit runtime and project content and never need the creator app.

## 2. Two release paths

| Path | Used for | Needs a build? | Where it runs |
|---|---|---|---|
| **Universal player + package** (ADR-0007) | Web export, Arcade publishing, Android export by repacking a prebuilt template player | No compilation. The player is prebuilt by SpawnKit's own Toolchain CI. | In the builder, on device or desktop. Offline-capable. |
| **Toolchain release build** (this design) | Per-game application identity, store formats (AAB, IPA), native SDKs (AdMob), compiled extensions, desktop distributions, TestFlight | Yes: `kotlin build/test/package/publish` | SpawnKit Build Service workers |

The builder picks the path automatically from the destination and the game's requirements, and explains why a
remote build is needed before asking for consent or an account.

## 3. Release workspace

The build service turns a release request into a generated Kotlin Toolchain project. Creators never edit it.

```
release-<job>/
├── kotlin, kotlin.bat                 # pinned wrapper (same release as SpawnKit's own build)
├── project.yaml                       # player-<platform> modules + vetted release plugins
├── toolchain/*.module-template.yaml   # SpawnKit's templates (Kotlin/Compose pins, Android levels)
├── player-android/module.yaml         # product: android/app; applicationId, versionCode/Name, signing
├── player-ios/module.yaml             # product: ios/app (macOS workers)
├── player-desktop/module.yaml         # product: jvm/app (+ installer plugin per OS, see gap G4)
├── player-web/module.yaml             # product: wasm-js/app
├── game/                              # validated, dependency-locked game package as resources
└── plugins/spawnkit-release/          # validation, branding and SDK wiring (local plugin)
```

- **Engine and extensions.** Player modules depend on the published SpawnKit runtime and player libraries at the
  exact versions in the game's lock. Trusted compiled extensions become module dependencies. Portable programs
  need no compilation.
- **Identity, versioning and branding** come from a release profile stored in the project. It holds only non-secret
  fields: display name, `applicationId` / bundle ID, version name, icon, splash colour and orientation.
  - The service owns version codes and build numbers, so store uploads never collide.
  - The `spawnkit-release` plugin generates launcher icons (`res/mipmap-*`, the iOS `AppIcon` set), the splash,
    and a self-contained `Info.plist`, as a migrated `ios/app` module requires.
- **Validation before build.** The plugin runs SpawnKit's engine validation, program type checks, asset
  limits and licence/provenance checks as a Toolchain `check`. Failures map to friendly builder explanations.

## 4. Capability investigation (Toolchain 0.14.1)

| Capability | Toolchain support | Plan |
|---|---|---|
| Android APK | `kotlin build` (debug APK verified on the emulator) | Sideload and test builds |
| Android AAB | `kotlin package -m <app>`: R8, resource shrinking, signing from `keystore.properties` (verified unsigned) | Play uploads. Verify R8 builds on a device before release (known issue 2). |
| Android signing | `settings.android.signing` and `kotlin tool generate-keystore` | Service-generated upload key per game, held in the secret store. Play App Signing recommended. |
| iOS app packaging | `ios/app` with a generated Xcode project; `kotlin package -f ipa` (automatic or manual signing) | macOS workers with Xcode. Unverified until K3/K6 macOS evidence. |
| TestFlight | `kotlin publish testflight` (**preview**): App Store Connect API key from environment variables, automatic signing only | Self publishing: the creator's key, injected per job. Managed: SpawnKit's team key. Use fastlane if manual signing is needed. |
| Desktop | `jvm/app` executable JAR (verified). No installer formats. | An installer plugin wrapping `jpackage` on Windows, macOS and Linux workers. jpackage cannot cross-build. |
| Native desktop executables | `windows/app`, `linux/app`, `macos/app` (Kotlin/Native) | Not used for Compose games: Compose UI does not target native desktop. Possible for headless tools. |
| Web / Wasm | `wasm-js/app`: complete static bundle (Wasm, `.mjs`, Skiko, Compose resources); verified in Chrome | Arcade hosting and web export zip. `package` is unsupported for this product, so the service collects the build output. |
| Kotlin/JS web | `js/app` preview: cannot run or package | Not used. Wasm is the web target. |
| Native SDKs (Android) | Maven dependencies, `dependencies@android`, manifest in `src/` | Release plugin adds SDK dependencies and generated manifest entries. |
| Native SDKs (iOS) | Swift/Objective-C sources in `ios/app`; SwiftPM integration is visible upstream (KTC-5916) | Investigate SwiftPM import for Google Mobile Ads on a macOS worker before committing. |
| AdMob | Android: `com.google.android.gms:play-services-ads` plus the `com.google.android.gms.ads.APPLICATION_ID` manifest entry. iOS: Google Mobile Ads SDK plus `GADApplicationIdentifier` in `Info.plist`. | Behind SpawnKit's asynchronous monetization host interface (R8). Test ad units in test mode, with UMP consent. No fabricated impressions or revenue. |
| Remote cloud builds | The CLI is headless and CI-friendly. Concurrent runs are safe per upstream docs. | Ktor build service with a job queue and isolated workers |
| Environment provisioning | The wrapper provisions the CLI, JDK, Android build pipeline, Kotlin/Native, Node/pnpm and Skiko. Xcode is the only manual prerequisite (macOS). | Worker images pre-warm the shared cache. No per-job installation. |

## 5. Build service

- **API (Ktor).**
  - `POST /releases` creates a job from a game package and release profile.
  - `GET /releases/{id}` returns status, and server-sent events stream progress.
  - Artifact downloads use short-lived URLs.
  - Jobs can be cancelled.
  - Requests are authenticated per creator, with quotas and rate limits.
- **Workers.**
  - Linux containers: Android, web, JVM desktop, Linux packages.
  - Windows and macOS runners: jpackage installers.
  - macOS with Xcode: iOS, TestFlight, macOS.
  - Each job runs in a fresh sandbox with a pre-warmed read-only Toolchain cache. Network egress is limited to
    dependency mirrors and store APIs.
  - Only vetted local plugins are allowed. Untrusted creator content is data, never build logic.
- **Hermetic workspaces.** Every workspace gets its own root with no writable ancestors. Known issue 1 showed that
  the Wasm test runner's pnpm can otherwise write to ancestor directories.
- **Secrets.**
  - Keystores and App Store Connect keys live in the service secret store (managed) or are uploaded per job from the
    creator's device secure storage (self publishing).
  - They are written to the job sandbox only for signing and deleted afterwards.
  - Log redaction runs on all output.
- **Artifacts and distribution.**
  - APKs and web bundles get download links and QR codes.
  - AABs and IPAs go to stores through the creator's or SpawnKit's accounts.
  - Arcade publishing reuses the existing Ktor publish flow.

## 6. Builder experience (phone-first)

The builder shows a Release Manager screen with destination cards (Web, Arcade, Android, iOS, Desktop). For each
destination it offers:

- a requirements checklist: identity, icon, version, account or signing;
- validation results with fixes;
- a cost and time estimate;
- consent for remote build and data upload.

While a release runs it shows live progress with cancel, then artifacts, install links and store status. Errors are
translated into actions ("Your icon must be at least 512×512"). Raw build logs are an advanced, redacted view.

## 7. Phasing

- **C6 (contest).** Web export and Arcade through the universal player. The player Wasm bundle is built by the
  Kotlin Toolchain in CI and shipped into the creator. The Release Manager screen shows remote-build destinations
  as "requires build service" without faking them.
- **K5.** Build service MVP: release workspace generator, `spawnkit-release` plugin, Linux worker. Android APK/AAB
  can be released from the builder on a phone; evidence comes from an emulator install of a service-built APK.
- **K6.**
  - macOS workers: iOS IPA and TestFlight (preview command), validated with a real App Store Connect sandbox
    account by the owner.
  - jpackage installers.
  - AdMob in test mode.
  - Managed publishing.
- **Later.** Cloud signing management, store listing automation, analytics. These stay on the roadmap with
  explicit availability labels.

## 8. Risks

- **Alpha toolchain.** Mitigations: pinned versions, an upgrade PR with the full gate, and Gradle exceptions kept
  until replaced.
- **TestFlight publishing is a preview** and supports automatic signing only. fastlane is the fallback for complex
  cases.
- **Infrastructure cost and abuse.** Mitigations: quotas, creator authentication, sandboxing, and no arbitrary
  code in workspaces.
- **Store policy compliance** (ads consent, data safety, age ratings) needs owner and legal input before managed
  publishing.
