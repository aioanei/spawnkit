# Release a game

Tracking: [issue #64](https://github.com/aioanei/spawnkit/issues/64). Architecture: [ADR-0007](adr/0007-mobile-builder-and-in-builder-release.md),
[ADR-0008](adr/0008-kotlin-toolchain.md), [Release Manager build architecture](design/2026-11-20-release-manager-kotlin-toolchain.md).

A released game is a **game package** played by a **universal player**. Neither needs the creator app. The Kotlin
Toolchain builds the player; creators never see it.

## From the builder

On Android or desktop, open **Project → Release game…**. Choose a game title and the scene players should enter.
The Release Manager checks the settings and rules on every object before sending or saving the snapshot.
Return to the editor to correct a reported scene or object and try again.

- **Save web ZIP** opens the device's save picker. The ZIP includes the browser player, fonts and game package.
  You can export offline. Extract the ZIP and upload its contents together to any static web host. Give players the
  folder URL ending in `/`. Serve `.mjs` as `text/javascript` and `.wasm` as `application/wasm`.
- **Publish game** sends the package to the Arcade server address you enter. Publishing creates a new release
  with its own play URL. Open or copy the link from the result. Anyone who can reach that server can play.
- **Signed in** (Project → Account…, C11b), the Arcade card publishes to the account's server as the account's own game
  and lists **My games**: Open, **Update with this game** (replaces it, same play link) and **Unpublish**. Each update
  and removal asks first. Signed-in publishing goes through the account client, so it also works in the web and iOS
  builders.
- **Store builds** lists the identity, signing and account requirements for Android, iOS and desktop. These
  destinations depend on the planned build service (K5/K6). The web and iOS builders currently direct creators
  to the Android or desktop builder for release.

## How the player is shipped

| Piece | Module | What it does |
|---|---|---|
| Game package | `core/editing` (`GamePackage`, `GamePackageCodec`) | Versioned `spawnkit-game` envelope around the project document, with title, start scene and required components. Damaged, foreign, newer and oversized packages are explained. See [format](project-persistence.md#game-packages-for-release-c6). |
| Shared player | `player/playerKit` | `PlaySession`, the fixed 60 Hz loop, `PlaySurface` (rendering, pointer-to-world input, touch controls) and play keys. The builder's Play mode uses the same code. `GamePlayer(pkg)` refuses games whose capabilities are missing and explains why. |
| Universal web player | `player/webPlayer` (`wasm-js/app`, Kotlin Toolchain only) | Loads `game.spawnkit.json` from beside its `index.html` and plays it. A missing or invalid file shows an explanation, never a blank page. |

On a clean developer checkout, first run `./kotlin build -m webPlayer -v release`; the optimized player is what exports and the Arcade ship. Repeat this step after changes to the player
or its dependencies. The pinned Toolchain exposes its final Wasm distribution separately from JVM artifacts.
CI builds that distribution first. The `web-player-bundle` plugin then archives it at `spawnkit/web-player.zip` in
the Android APK, desktop executable JAR and Arcade JAR. The Gradle compatibility build runs the same two steps.

## Run an Arcade

After building the player, run `./kotlin run -m server`. The service listens on port 8080. `PORT` and `HOST`
override the port and bind address. On desktop enter `http://localhost:8080` in the Release Manager. An Android
emulator reaches the host at `http://10.0.2.2:8080`. Use a reachable HTTPS address when sharing a release publicly.

| Request | Response |
|---|---|
| `POST /api/games`, `application/json` with the raw game package | 201 with `slug`, `title` and a relative `playUrl`. The Location header points to the game. |
| `GET /api/games` | List of release summaries. |
| `GET /api/games/{slug}` | The validated game package. |
| `GET /play/{slug}/` | The universal player, which loads the released package from the same directory. |

Each published package survives server restarts. Set `SPAWNKIT_ARCADE_DIR` to a persistent directory for one server
process. The default is `.spawnkit/arcade` under the service account's home directory. Since C11a, a game published
while signed in belongs to that account, which alone can republish (`PUT /api/games/{slug}`, same play link) or
unpublish it (`DELETE`). Publishing without signing in still works, unowned. See [Arcade hosting](arcade-hosting.md)
for accounts, configuration and backups. The Arcade keeps at most 128 releases per directory. Uploads are limited to
12,779,520 UTF-8 bytes (three bytes per package character), and the decoded package to 4,259,840 characters, four simultaneous submissions and 30 seconds for the body. The rolling one-minute limits
are six attempts per connecting address and 60 per server process. The client displays the server's explanation
when it refuses a release. Use a separate directory for smoke testing.

## Try the web player directly

1. `./kotlin test -p jvm -m playerKit` writes a sample package to `player/playerKit/build/sample-game/`.
2. `./kotlin build -m webPlayer -v release` writes the bundle to `build/tasks/_webPlayer_buildWasmJsAppWasmJsRelease/`. Upstream
   may change this internal path.
3. Copy the bundle and `game.spawnkit.json` into one folder.
4. Serve the folder with `.mjs` as `text/javascript` and `.wasm` as `application/wasm`, then open `index.html`.
   The player loads `game.spawnkit.json` relative to the page's directory, so a hosted game must be served from a
   directory URL with a trailing slash. The Arcade redirects `/play/{slug}` to `/play/{slug}/`.

Evidence (headless Chrome, 2026-11-20): the [sample game playing](media/web-player-game.png) and the
[missing-game explanation](media/web-player-missing.png).

## Verification

| Builder | Standalone player |
|---|---|
| [Compact layout at 130% text](media/release-manager-compact.png) · [expanded layout](media/release-manager-expanded.png) | [Game published to the packaged Ktor server](media/arcade-game-playing.png) |
| [Android saved the ZIP](media/android-release-saved.png) · [Android received its Arcade link](media/android-arcade-published.png) | [Chrome playing the Android ZIP after collecting a coin](media/android-export-playing.png) |

The Android ZIP and the game at the returned Arcade URL both responded to keyboard input and reached **Score: 1**.
After the switch to the optimized player, the Android export was re-verified: a 7,357,435-byte ZIP played in Chrome.
The emulator used an adb reverse tunnel to a local test server. Reproduction is in
[`android-release-smoke.py`](../tools/android-release-smoke.py) and [`release-player-smoke.py`](../tools/release-player-smoke.py).
