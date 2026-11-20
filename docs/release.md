# Releasing games — C6 progress

Tracking: [issue #64](https://github.com/aioanei/spawnkit/issues/64). Architecture: [ADR-0007](adr/0007-mobile-builder-and-in-builder-release.md),
[ADR-0008](adr/0008-kotlin-toolchain.md), [Release Manager build architecture](design/2026-11-20-release-manager-kotlin-toolchain.md).

A released game is a **game package** played by a **universal player**. Neither needs the creator app. The Kotlin
Toolchain builds the player; creators never see it.

## Pieces that exist

| Piece | Module | What it does |
|---|---|---|
| Game package | `core/editing` (`GamePackage`, `GamePackageCodec`) | Versioned `spawnkit-game` envelope around the project document, with title, start scene and required components. Damaged, foreign, newer and oversized packages are explained. See [format](project-persistence.md#game-packages-for-release-c6). |
| Shared player | `player/playerKit` | `PlaySession`, the fixed 60 Hz loop, `PlaySurface` (rendering, pointer-to-world input, touch controls) and play keys. The builder's Play mode uses the same code. `GamePlayer(pkg)` refuses games whose capabilities are missing and explains why. |
| Universal web player | `player/webPlayer` (`wasm-js/app`, Kotlin Toolchain only) | Loads `game.spawnkit.json` from beside its `index.html` and plays it. A missing or invalid file shows an explanation, never a blank page. |

## Try the web player

1. `./kotlin test -p jvm -m playerKit` writes a sample package to `player/playerKit/build/sample-game/`.
2. `./kotlin build -m webPlayer` writes the bundle to `build/tasks/_webPlayer_buildWasmJsAppWasmJsDebug/`. Upstream
   may change this internal path.
3. Copy the bundle and `game.spawnkit.json` into one folder.
4. Serve the folder with `.mjs` as `text/javascript` and `.wasm` as `application/wasm`, then open `index.html`.
   The player loads `game.spawnkit.json` relative to the page's directory, so a hosted game must be served from a
   directory URL with a trailing slash. The Arcade will serve `/play/{slug}/`, not `/play/{slug}`.

Evidence (headless Chrome, 2026-11-20): the [sample game playing](media/web-player-game.png) and the
[missing-game explanation](media/web-player-missing.png).

## Not yet implemented

- **Release Manager screen.** Web export writes the player bundle plus the package to a folder or zip on Android and
  desktop.
- **Arcade.** Ktor publish/list/get with limits, and `/play/{slug}`.
- **Store builds.** Android AAB/APK identity, iOS and desktop installers through the Kotlin Toolchain build service
  (K5/K6, issues #62/#63). These are shown as planned and never simulated.
