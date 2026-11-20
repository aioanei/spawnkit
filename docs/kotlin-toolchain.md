# Kotlin Toolchain — build system, migration status and exceptions

Decision: [ADR-0008](adr/0008-kotlin-toolchain.md). Tracking issue: [#58](https://github.com/aioanei/spawnkit/issues/58).
Release Manager use: [Release Manager build architecture](design/2026-11-20-release-manager-kotlin-toolchain.md).

The [Kotlin Toolchain](https://kotlin-toolchain.org/) (JetBrains' `kotlin` CLI with declarative `project.yaml` /
`module.yaml`) is SpawnKit's primary build, test, packaging and distribution system. Migration is incremental: both
builds read the same sources, and Gradle remains the required CI check until each gate is reproduced with evidence.

- **Pinned release:** 0.14.1 (2026-11-19), the latest stable release when adopted. The `kotlin` and `kotlin.bat`
  wrappers were downloaded from the official repository and their SHA-256 checksums matched the official installer
  scripts.
- **Status:** Alpha upstream. Do not run `kotlin update` without a reviewed PR.

## Everyday commands

The wrapper provisions the CLI, JDK, Android build pipeline, Kotlin/Native, Node/pnpm and Skiko on first use. On
Windows, use `.\kotlin.bat`. Set `KOTLIN_CLI_NO_WELCOME_BANNER=1` in scripts.

| Task | Kotlin Toolchain | Gradle (coexistence) |
|---|---|---|
| Project model | `./kotlin show modules` · `show settings -m <module>` · `show dependencies -m <module>` | — |
| Format | `./kotlin do ktlintFormat` | `./gradlew spotlessApply` |
| Lint | `./kotlin check ktlint` | `./gradlew spotlessCheck` |
| Tests per platform | `./kotlin test -p jvm -p android` · `-p mingwX64` (Windows) · `-p linuxX64` (Linux) · `-p iosSimulatorArm64` (macOS) | `./gradlew allJvmAndWebTests`, `localNativeTests` |
| One module | `./kotlin test -m runtime -p jvm` (module name = directory name) | `./gradlew :core:runtime:jvmTest` |
| Creator apps | `./kotlin run -m desktopApp` · `run -m webApp` · `run -m androidApp` | `:app:desktopApp:run` etc. |
| Packages | `./kotlin build -m androidApp` (debug APK) · `package -m androidApp` (release AAB) · `package -m desktopApp` / `-m server` (executable JAR) | `assembleDebug` etc. |
| Server | `./kotlin run -m server` | `./gradlew :server:run` |

Do not run `./kotlin test` without `-p` on a developer machine. It also starts Wasm browser tests and iOS tests,
which have the environment requirements described below.

## Project layout

- `project.yaml` lists every module and the local plugins.
- `toolchain/*.module-template.yaml` replaces the `build-logic` convention plugins:
  - `kotlin` holds the Kotlin, JDK and JVM release pins.
  - `android` holds the SDK levels and build tools.
  - `core-library` holds explicit API and warnings-as-errors.
  - `compose` and `compose-library` hold the Compose Multiplatform toolchain and UI libraries.
  - `lint` enables ktlint.
- `plugins/embedded-templates` generates `dev.spawnkit.templates.EmbeddedTemplates` from `templates/*/project.json`.
  It replaces the Gradle task of the same purpose.
- `plugins/ktlint` provides the `ktlint` check and the `ktlintFormat` command. It uses the same ktlint version and
  `.editorconfig` as Spotless.
- Multiplatform sources use the Toolchain layout: `src/`, `src@jvm/`, `src@android/`, `src@ios/`, `src@web/`,
  `test/`, `test@jvm/`, `composeResources/`.
  - Gradle reads the same directories through `ToolchainLayout.kt` in `build-logic`. It applies to modules that
    contain a `module.yaml`.
  - `app/desktopApp` and `server` keep `src/main/kotlin` through `layout: maven-like`.
- `app/androidApp` follows the `android/app` layout: `src/AndroidManifest.xml`, `src/`, `res/`.
- `app/webApp` follows the `wasm-js/app` layout. Its `index.html` uses the Toolchain's `{{kotlin.scripts}}`
  placeholder; a Gradle-only resource filter substitutes `webApp.js` until Gradle is retired.
- Versions live in `gradle/libs.versions.toml` (`$libs.*`). Kotlin, Compose, serialization and Ktor are also pinned
  in templates and module files because the Toolchain configures them as settings. Keep both in step until Gradle is
  removed; a version bump PR must change both.

## Verified compatibility (Windows 11 host, 2026-11-20)

| Area | Result with Toolchain 0.14.1 | Evidence |
|---|---|---|
| KMP engine libraries (`core/*`, `samples/runtimeProbe`) | **Supported.** Explicit API strict and warnings as errors via settings. | `kotlin test -p jvm`, `-p android` and `-p mingwX64`: 231/231 each, the same counts as Gradle's `jvmTest`, `testAndroidHostTest` and `mingwX64Test` on the same sources. `kotlin build -p linuxX64`: success. |
| Determinism golden (cross-target hash) | **Supported** on the targets above | `core/engine` golden test passes on JVM, Android host and mingwX64 |
| Kotlin 2.4.21, kotlinx.serialization 1.13.0 | **Supported** with explicit pins (Toolchain defaults: 2.4.10, 1.11.0) | `show settings`; `core/model` codec tests |
| Compose Multiplatform 1.12.2 libraries (`render`, `app/shared`) | **Supported** (default 1.12.1, pinned) | `render` 19/19 on JVM and 17/17 on Android host; `shared` 16/16 Compose UI tests on JVM. Same counts as Gradle. |
| Compose resources | **Supported.** The package is pinned to the Gradle-generated `spawnkit.render.generated.resources`. | Font present in APK assets and in the Wasm bundle |
| Generated sources (embedded templates) | **Local plugin** | Template-loading tests pass; plugin unit tests 2/2 |
| ktlint / Spotless | **Local plugin**, enabled through a template | Zero violations repository-wide. A planted violation fails the check, and `ktlintFormat` fixes it. |
| Ktor server | **Supported** (`settings.ktor`, BOM 3.6.1) | Test 1/1. The executable JAR answers `GET /health` with `200 ok`. |
| Android creator app | **Supported**: debug APK (`build`), release AAB with R8 (`package`), keystore signing settings | Toolchain APK on the API 36 emulator: rules built with taps, then played to "Score: 1" ([capture](media/android-toolchain-rules-play.png)) |
| Desktop creator app | **Supported** as an executable JAR | The JAR launches the builder ([capture](media/desktop-toolchain-builder.png)) |
| Web creator, Wasm | **Supported** (`wasm-js/app` build output) | The bundle renders the builder in headless Chrome ([capture](media/web-toolchain-builder.png)) |
| Web creator, Kotlin/JS | **Not supported**: `js/app` is an incomplete preview that cannot run or package | Exception G3 |
| Kotlin/JS tests | **Not supported** in 0.14.1 | `kotlin test -p js`: "No test tasks were found for platforms: JS". Exception G1. |
| Wasm tests | **Runs in a headless browser via Playwright**, unlike Gradle's Node runner. Blocked on this host by a provisioning defect (known issue 1). | Verified in the `kotlin-toolchain` CI job on Linux. Exception G2 until green. |
| linuxX64 / iOS simulator tests | Compile verified on Windows; execution needs Linux / macOS | CI `kotlin-toolchain` (Linux) and `ios` (macOS) jobs |
| iOS framework for `app/iosApp` | **Different model.** The Toolchain builds iOS apps from an `ios/app` module and generates its own Xcode project; a `kmp/lib` does not export frameworks to an external Xcode project. | Exception G5 |
| Desktop installers (MSI, DMG, DEB) | **Not supported** (package formats: `jar`, `executable-jar`, `aab`, `maven-central-bundle`, `ipa`) | Exception G4 |
| Multi-target benchmark executables | Application products are single-platform, and JS applications cannot run | Exception G6 |
| Koog | Not in the repository yet; the `ai` module arrives with C7. Koog is a plain multiplatform library with no build plugin, so `$libs` dependencies suffice. | To be verified in the C7 PR |
| AI tooling | Official skills and the kotlinlang MCP server are configured for Claude Code and Codex. The Toolchain's Compose Hot Reload MCP server is available on demand. | See [AI-assisted development](#ai-assisted-development) |

## Gradle exceptions (each has a revisit trigger)

| # | Kept on Gradle | Verified reason | Revisit when |
|---|---|---|---|
| G1 | Kotlin/JS tests (`jsNodeTest`) | No JS test tasks in Toolchain 0.14.1 | Every Toolchain release; drop when `-p js` tests run |
| G2 | Wasm tests under Node (`wasmJsNodeTest`) | Toolchain runs Wasm tests in a browser; parity is not yet proven in CI | After the CI job's Wasm step has been green on `main` |
| G3 | Kotlin/JS creator bundle (`app/webApp` JS target) | `js/app` cannot run or package | When `js/app` leaves preview; the Wasm creator is the primary web target |
| G4 | Desktop installers (`compose.desktop.nativeDistributions`) | No installer formats | Replace with a `jpackage` local plugin per OS worker (Release Manager K6) |
| G5 | iOS framework for `app/iosApp` (`linkDebugFrameworkIosSimulatorArm64`) | Toolchain iOS apps use an `ios/app` module with a generated Xcode project | K3, on macOS, with a self-contained `Info.plist` |
| G6 | `benchmarks/runtimeStorage` multi-target executables | Single-platform app products; JS apps cannot run | When restructured into per-platform app modules, or when the Toolchain adds multi-platform runnable products |
| G7 | Required CI check `build` | Toolchain parity must be proven on `main` first | K2 exit criteria in the [plan](plans/2026-11-20-engine-foundation-revision.md#kotlin-toolchain-track-k) |
| G8 | Spotless for `build-logic` and `*.gradle.kts` | These files exist only for Gradle | Deleted together with Gradle (K4) |

## Known issues

1. **Wasm test provisioning writes outside the project.** Before Wasm browser tests, the Toolchain runs
   `pnpm add playwright@<version>` without a project-local `package.json`. pnpm then uses the nearest ancestor
   `package.json`. On a machine whose home directory holds a Node project, that project gained a Playwright
   dependency, `pnpm-lock.yaml`, `pnpm-workspace.yaml`, and a pnpm-managed `node_modules`.
   - Observed on the Windows development machine on 2026-11-20. The project was restored from its npm lockfile.
   - Build-time `npmInstall` for `wasm-js/app` is not affected: it uses its own directory.
   - Do not run `kotlin test -p wasmJs` where any ancestor directory of the repository contains a `package.json`.
   - CI runners are unaffected.
   - To be reported upstream (YouTrack `KTC`) with a minimal reproduction.
2. **Android release builds always run R8.** Shrinking and resource shrinking are on, whereas Gradle has minify off.
   Release AAB packaging succeeds; runtime of a shrunk release build has not yet been verified on a device. Add
   `proguard-rules.pro` keep rules if reflection-based code appears.
3. **Default Android build tools.** The Toolchain default (37.0.0) was not installed locally, so the `android`
   template pins 36.0.0, which AGP 9.1.1 also uses.
4. **Output paths are internal.** Examples: `build/tasks/_androidApp_buildAndroidDebug/*.apk`,
   `build/tasks/_webApp_buildWasmJsAppWasmJsDebug/`. Upstream marks them as subject to change, so CI and the
   Release Manager must read the path that `package` prints, or use a staging plugin.
5. **Long Windows paths.** The Android pipeline nests intermediates deeply under `build/tasks/` (for example
   `_androidApp_buildAndroidDebug/gradle-project/build/_app_androidApp/intermediates/...`), beyond 260 characters
   in this checkout. Builds succeeded, but Git and some tools warn. On Windows, enable long paths (`git config
   core.longpaths true` and the OS `LongPathsEnabled` policy), or clone to a short path.
6. **Serving Wasm bundles locally.** Some static servers (Python's `http.server` on Windows) serve `.mjs` as
   `text/plain`, and browsers refuse to load it. Serve `.mjs` as `text/javascript`.

## AI-assisted development

- **Skills.** The official [Kotlin agent skills](https://github.com/Kotlin/kotlin-agent-skills) (Apache-2.0) are
  vendored and locked by `skills-lock.json`. Claude Code reads them from `.claude/skills/` and Codex from
  `.agents/skills/`. Restore or update them with `npx skills experimental_install` / `npx skills update -p`.
  Installed skills:
  - `kotlin-tooling-kotlin-toolchain`: everyday Toolchain work.
  - `kotlin-tooling-gradle-to-kotlin-toolchain-project` and `kotlin-tooling-gradle-to-kotlin-toolchain-plugin`:
    migration.
  - `kotlin-tooling-kotlin-toolchain-plugin-authoring`: local plugins.
  - `kotlin-tooling-native-build-performance`: slow iOS and native builds.
  - `kotlin-tooling-java-to-kotlin`.
- **Kotlin documentation MCP server** (`https://mcp.kotlinlang.org/mcp`). Configured in `.mcp.json` for Claude Code
  and `.codex/config.toml` for Codex. Each tool asks once before trusting project servers.
- **Compose Hot Reload MCP server** (`kotlin compose-hot-reload-mcp-server`). It talks to a running
  `kotlin run --compose-hot-reload` desktop session. It is useful while iterating on builder UI, but it is only
  meaningful with a live session and its command differs on Windows (`kotlin.bat`). Add it locally when needed
  (`claude mcp add --scope local ...`) instead of committing it.
- **Token budget.** Prefer `kotlin show settings -m <module>` and `show dependencies` over reading generated files.
  Prefer the skills' migration checklists over re-deriving Toolchain semantics.

## Migration log

- **K1 (this increment).**
  - Wrappers pinned.
  - Every Gradle module except `benchmarks/runtimeStorage` has a `module.yaml`; all sources were moved to the
    Toolchain layout without content changes.
  - Templates and local plugins added.
  - Gradle reads the new layout.
  - The CI `kotlin-toolchain` job and an iOS Toolchain step were added.
  - Skills and MCP were configured.
- **Remaining:**
  - K2: Toolchain becomes the required CI check.
  - K3: `ios/app`.
  - K4: Gradle removal, except recorded exceptions.
  - K5–K6: Release Manager build service.

  See the [plan](plans/2026-11-20-engine-foundation-revision.md#kotlin-toolchain-track-k).
